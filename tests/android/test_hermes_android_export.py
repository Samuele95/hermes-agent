"""The app's export of a stored session (sessions-12, batch-3 design F8 / bet 9, DC-M13).

``session_export`` writes the session's file in the Python half, into the app's cache
(``TMPDIR/exports/<title>-<id8>.json``), exactly as the core's ``GET /api/sessions/{id}/export`` route streams
it: the stored row, then every message incl. inactive ones, read in keyset pages of 500. The previous export
is deleted first (only the previous session export is deleted; a backup zip in the same folder stays), a session with no stored row is refused in the app's words with nothing written, and a read
that fails leaves no file behind. Every test goes through ``hermes_android.call``, so the contract's
declaration judges each reply, and runs against the real core store of the hermetic conftest's per-test home.
"""

from __future__ import annotations

import importlib
import json
import zipfile
from pathlib import Path

import pytest

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest

NOT_SAVED = "Nothing to export yet: Hermes has not saved this conversation."


@pytest.fixture
def cache(tmp_path, monkeypatch):
    monkeypatch.setenv("TMPDIR", str(tmp_path))  # the app's cache dir, as hermes_android.prepare() sets it
    return tmp_path


@pytest.fixture
def db():
    import hermes_state_registry

    handle = hermes_state_registry.acquire()
    try:
        yield handle
    finally:
        hermes_state_registry.release_or_close(handle)


def ask(function: str, request: dict) -> dict:
    return json.loads(hermes_android.call(function, json.dumps(request)))


def _turns(n: int) -> list[dict]:
    return [{"role": "user" if i % 2 == 0 else "assistant", "content": f"message {i}"} for i in range(n)]


def _exported(cache: Path) -> list[str]:
    return sorted(p.name for p in (cache / "exports").iterdir())


def test_an_export_is_the_sessions_row_and_every_message_as_the_route_streams_them(cache, db):
    import hermes_state_registry
    from hermes_cli.web_routers.sessions import _compact_json  # the route's own encoder (D2: same bytes)

    sid = "20261115_101500_a1b2c3"
    db.create_session(sid, source="android")
    db.append_message(sid, "user", "What is the capital of France?")
    rewound = db.append_message(sid, "assistant", "Lyon, I think.")
    db.append_message(sid, "assistant", "Paris — «la Ville Lumière».")
    db.deactivate_message(sid, rewound)
    db.set_session_title(sid, "Capitals")
    handles = hermes_state_registry.stats()["total_refcounts"]

    reply = ask("session_export", {"session_id": sid})

    assert hermes_state_registry.stats()["total_refcounts"] == handles, "a state.db handle leaked"
    target = cache / "exports" / "capitals-20261115.json"
    assert reply == {"path": str(target), "name": "capitals-20261115.json", "bytes": target.stat().st_size}
    expected = (_compact_json(db.get_session(sid))[:-1] + ',"messages":['
                + ",".join(_compact_json(m) for m in db.get_messages(sid, include_inactive=True)) + "]}")
    assert target.read_bytes() == expected.encode("utf-8")
    parsed = json.loads(target.read_text(encoding="utf-8"))
    assert parsed["id"] == sid and parsed["title"] == "Capitals"
    assert [m["content"] for m in parsed["messages"]] == [
        "What is the capital of France?", "Lyon, I think.", "Paris — «la Ville Lumière»."]
    assert [m["active"] for m in parsed["messages"]] == [1, 0, 1]


def test_a_long_session_is_read_in_pages_of_500_and_comes_whole(cache, db, monkeypatch):
    from hermes_state import SessionDB

    sid = "20261115_102000_long01"
    db.create_session(sid, source="android")
    db.append_messages_batch(sid, _turns(1001))
    seen: list[tuple] = []
    real = SessionDB.get_messages

    def spy(self, session_id, *args, **kwargs):
        seen.append((kwargs.get("limit"), kwargs.get("after_id"), kwargs.get("include_inactive")))
        return real(self, session_id, *args, **kwargs)

    monkeypatch.setattr(SessionDB, "get_messages", spy)

    reply = ask("session_export", {"session_id": sid})

    assert len(seen) == 3 and seen[0] == (500, 0, True)
    assert all(limit == 500 and inactive is True for limit, _, inactive in seen)
    messages = json.loads(Path(reply["path"]).read_text(encoding="utf-8"))["messages"]
    assert [m["content"] for m in messages] == [f"message {i}" for i in range(1001)]
    assert [m["id"] for m in messages] == sorted(m["id"] for m in messages)


def test_a_very_large_session_exports_whole(cache, db):
    sid = "20261115_103000_huge01"
    db.create_session(sid, source="android")
    db.append_messages_batch(sid, _turns(50_000), chunk_rows=5000)

    reply = ask("session_export", {"session_id": sid})

    path = Path(reply["path"])
    assert reply["bytes"] == path.stat().st_size
    messages = json.loads(path.read_text(encoding="utf-8"))["messages"]
    assert len(messages) == 50_000 and messages[-1]["content"] == "message 49999"


def test_the_name_is_the_titles_safe_form_and_the_ids_start(cache, db):
    titled, untitled = "Ab:Cd/Ef_20261115", "20261115_104000_plain1"
    db.create_session(titled, source="android")
    db.set_session_title(titled, "  Trip / Plans: Été 2026  ")
    db.create_session(untitled, source="android")

    first = ask("session_export", {"session_id": titled})
    second = ask("session_export", {"session_id": untitled})

    assert first["name"] == "trip-plans-t-2026-ab-cd-ef.json"
    assert second["name"] == "session-20261115.json"
    for reply in (first, second):
        assert "/" not in reply["name"] and Path(reply["path"]).parent == cache / "exports"


def test_a_second_export_replaces_the_first(cache, db):
    db.create_session("a1111111_session", source="android")
    db.create_session("b2222222_session", source="android")

    first = ask("session_export", {"session_id": "a1111111_session"})
    second = ask("session_export", {"session_id": "b2222222_session"})

    assert first["name"] != second["name"]
    assert _exported(cache) == [second["name"]] == ["session-b2222222.json"]


def test_an_export_deletes_only_the_previous_session_export_never_a_waiting_backup(cache, db):
    folder = cache / "exports"
    folder.mkdir()
    backups = {
        "hermes-backup-2026-11-20-101500.zip": b"PK zip waiting for the share sheet",
        "hermes-backup-2026-11-20-101500.zip.part": b"a backup still being written",  # on hermes-python-net, at once
    }
    for name, data in backups.items():
        (folder / name).write_bytes(data)
    (folder / "older-a1111111.json").write_text("{}", encoding="utf-8")
    (folder / "older-a1111111.json.part").write_text("{", encoding="utf-8")  # an export that was killed
    (folder / "hermes-backup-notes-c3333333.json").write_text("{}", encoding="utf-8")  # titled 'Hermes backup notes'
    db.create_session("b2222222_session", source="android")

    reply = ask("session_export", {"session_id": "b2222222_session"})

    assert reply["name"] == "session-b2222222.json"
    assert _exported(cache) == sorted([reply["name"], *backups])
    for name, data in backups.items():
        assert (folder / name).read_bytes() == data, name


def test_a_backup_made_by_the_app_survives_a_session_export(cache, db):
    db.create_session("a1111111_session", source="android")
    db.set_session_title("a1111111_session", "Trip")
    made = ask("backup_create", {})
    assert "error" not in made, made
    zipped = Path(made["path"]).read_bytes()

    ask("session_export", {"session_id": "a1111111_session"})

    assert Path(made["path"]).read_bytes() == zipped
    assert zipfile.is_zipfile(made["path"])
    assert _exported(cache) == sorted([made["name"], "trip-a1111111.json"])


def test_a_session_with_no_stored_row_is_refused_in_the_apps_words_and_nothing_is_written(cache, db):
    db.create_session("a1111111_session", source="android")
    first = ask("session_export", {"session_id": "a1111111_session"})

    assert ask("session_export", {"session_id": "20261115_105000_norow1"}) == {"error": NOT_SAVED}

    assert _exported(cache) == [first["name"]]


def test_a_read_that_fails_leaves_no_file_behind(cache, db, monkeypatch):
    import hermes_state_registry
    from hermes_state import SessionDB

    sid = "20261115_106000_fails1"
    db.create_session(sid, source="android")
    db.append_messages_batch(sid, _turns(1001))
    calls = []
    real = SessionDB.get_messages

    def failing(self, session_id, *args, **kwargs):
        calls.append(1)
        if len(calls) == 2:
            raise OSError("disk I/O error")
        return real(self, session_id, *args, **kwargs)

    monkeypatch.setattr(SessionDB, "get_messages", failing)
    handles = hermes_state_registry.stats()["total_refcounts"]

    with pytest.raises(OSError):
        hermes_android.call("session_export", json.dumps({"session_id": sid}))

    assert hermes_state_registry.stats()["total_refcounts"] == handles, "a state.db handle leaked"
    assert len(calls) == 2
    folder = cache / "exports"
    assert not list(folder.glob("*.json")) and not list(folder.glob("*.part"))
