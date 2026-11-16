"""Back up and restore exactly five parts of Hermes data, never a key or a sign-in (row settings-24; batch-4 F3, F4,
F15; D49 "No secrets", D8).

``backup_create`` zips sessions (``state.db``, a SQLite backup-API snapshot), memories, skills (with the archive),
``config.yaml`` with every key field blanked, and cron without its lock files into the app's cache; ``backup_restore``
takes only those five parts out of a Hermes backup zip (the app's, or ``hermes backup``'s), with config.yaml's key
fields blanked again and ``state.db`` written page by page into the open file, and counts every other member as
skipped. Both directions use one closed allow-list (``hermes_android.backup.part_of``): ``.env``, ``auth.json``, the
vault, ``mcp-tokens/``, profiles and runtime files are outside every part by construction, and a secret basename
inside a part is dropped too. A restore is refused in words, before anything is written, while a turn runs, while the
always-on gateway holds its runtime lock, or when the file holds nothing to restore. Every test goes through
``hermes_android.call``, against the hermetic conftest's per-test home.
"""

from __future__ import annotations

import hashlib
import importlib
import json
import os
import sqlite3
import subprocess
import sys
import zipfile
from pathlib import Path

import hermes_yaml as yaml  # the core's YAML (ruamel, YAML 1.1 booleans)
import pytest

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest

BUSY = "Hermes is still answering. Stop the reply or let it finish, then restore the backup."
GATEWAY = "The always-on gateway is running. Turn it off, then restore the backup."
NOT_ZIP = "This file is not a zip archive."
NOTHING = "This backup holds no sessions, memories, skills, settings or jobs."
BAD_CONFIG = "The backup's settings (config.yaml) could not be read."
BAD_SESSIONS = "The backup's session database could not be read."
NOT_HERMES = "zip does not appear to be a Hermes backup (no config.yaml, .env, or state databases found)"
PARTS = ["sessions", "memories", "skills", "config", "jobs"]

SESSIONS = ["20261116_090000_aaaaaa", "20261116_090100_bbbbbb", "20261116_090200_cccccc"]
WAL_SESSION = "20261116_090300_dddddd"  # committed, still in the WAL, its connection open

CONFIG = {
    "model": {"default": "my-model", "provider": "custom", "api_key": "canary-cfg-6"},
    "approvals": {"timeout": 77},
    "auxiliary": {"vision": {"provider": "openrouter", "api_key": "canary-cfg-7"}},
    "delegation": {"model": "m", "api_key": "canary-cfg-9"},
    "dashboard": {"basic_auth": {"username": "me", "password": "canary-cfg-10", "password_hash": "canary-cfg-11",
                                 "secret": "canary-cfg-12"}},
    "browser": {"camofox": {"session_key": "canary-cfg-13"}},
    "platforms": {"telegram": {"enabled": True, "token": "canary-cfg-14"},
                  "api_server": {"extra": {"key": "canary-cfg-15", "port": 8642}}},
    "custom_providers": [{"name": "x", "base_url": "http://10.0.0.9/v1", "api_key": "canary-cfg-8",
                          "key_env": "HERMES_CUSTOM_X_API_KEY"}],
}
# Each key field of CONFIG, as a path.
KEY_FIELDS = [("model", "api_key"), ("auxiliary", "vision", "api_key"), ("delegation", "api_key"),
              ("dashboard", "basic_auth", "password"), ("dashboard", "basic_auth", "password_hash"),
              ("dashboard", "basic_auth", "secret"), ("browser", "camofox", "session_key"),
              ("platforms", "telegram", "token"), ("platforms", "api_server", "extra", "key"),
              ("custom_providers", 0, "api_key")]

SECRETS = {  # file -> its canary: none may ever enter an archive or leave one
    ".env": "OPENAI_API_KEY=canary-env-1\n",
    "auth.json": json.dumps({"providers": {"nous": {"access_token": "canary-auth-2"}}}),
    "vault.key": "canary-vault-16",
    "vault.json.enc": "canary-vault-17",
    "mcp-tokens/srv.json": json.dumps({"access_token": "canary-mcp-3"}),
    "profiles/work/.env": "OPENAI_API_KEY=canary-profile-4\n",
    "skills/research/arxiv/.env": "ARXIV_KEY=canary-skill-5\n",
    "browser-profile/Cookies": "canary-cookie-18",
    "gateway.pid": "4242",
    "gateway_state.json": json.dumps({"state": "running", "token": "canary-state-19"}),
}
KEPT = {  # the five parts' files, as the fixture writes them
    "memories/MEMORY.md": "The user likes tea.\n",
    "memories/USER.md": "Name: Sam.\n",
    "skills/research/arxiv/SKILL.md": "---\nname: arxiv\ndescription: Search arXiv.\n---\n\n# arxiv\n",
    "skills/.archive/old/SKILL.md": "---\nname: old\ndescription: An old skill.\n---\n\n# old\n",
    "skills/.usage.json": json.dumps({"arxiv": {"use_count": 2}}),
    "cron/jobs.json": json.dumps({"jobs": [{"id": "j1", "prompt": "Say hello."}]}),
    "cron/output/j1/run.md": "Hello.\n",
}
RUNTIME = {"cron/.jobs.lock": "", "cron/.tick.lock": ""}
CANARY = "canary-"


def _canaries_in(data: bytes) -> bool:
    return CANARY.encode() in data


@pytest.fixture
def cache(tmp_path, monkeypatch):
    path = tmp_path / "cache"
    path.mkdir()
    monkeypatch.setenv("TMPDIR", str(path))  # the app's cache dir, as hermes_android.prepare() sets it
    return path


def _sessions_db(path: Path, sids) -> None:
    import hermes_state_registry

    db = hermes_state_registry.acquire(path)
    try:
        for sid in sids:
            db.create_session(sid, source="android")
            db.append_message(sid, "user", f"hello from {sid}")
    finally:
        hermes_state_registry.release_or_close(db)


@pytest.fixture
def home(cache):
    """Home A, seeded with every part, every secret and the runtime files; a WAL row stays in the WAL."""
    from hermes_constants import get_hermes_home

    path = get_hermes_home()
    path.mkdir(parents=True, exist_ok=True)
    _sessions_db(path / "state.db", SESSIONS)
    live = sqlite3.connect(path / "state.db")
    assert live.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
    live.execute("PRAGMA wal_autocheckpoint=0")
    live.execute("INSERT INTO sessions (id, source, started_at) VALUES (?, 'android', 1794562200.0)", (WAL_SESSION,))
    live.commit()
    assert (path / "state.db-wal").stat().st_size > 0
    for rel, text in {**KEPT, **RUNTIME, **SECRETS}.items():
        (path / rel).parent.mkdir(parents=True, exist_ok=True)
        (path / rel).write_text(text, encoding="utf-8")
    (path / "config.yaml").write_text(yaml.safe_dump(CONFIG, sort_keys=False), encoding="utf-8")
    try:
        yield path
    finally:
        live.close()


def _switch(monkeypatch, path: Path) -> Path:
    """Points HERMES_HOME at ``path`` (home B), as a new phone's app would."""
    import hermes_state

    path.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("HERMES_HOME", str(path))
    monkeypatch.setattr(hermes_state, "DEFAULT_DB_PATH", path / "state.db")  # the conftest pins it to home A
    return path


def create() -> dict:
    return json.loads(hermes_android.call("backup_create", ""))


def restore(path: Path) -> dict:
    return json.loads(hermes_android.call("backup_restore", json.dumps({"path": str(path)})))


def _members(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as zf:
        return {name: zf.read(name) for name in zf.namelist()}


def _get(cfg, path):
    for step in path:
        cfg = cfg[step]
    return cfg


def _tree(root: Path) -> dict[str, str]:
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(root.rglob("*")) if p.is_file()}


def _count(db: Path) -> int:
    conn = sqlite3.connect(db)
    try:
        return conn.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
    finally:
        conn.close()


# --- backup_create ---

def test_a_backup_holds_only_the_five_parts_and_no_secret_in_any_byte(home, cache):
    reply = create()

    assert set(reply) == {"path", "name", "bytes", "parts"}
    zipped = _members(Path(reply["path"]))
    assert set(zipped) == {"state.db", "config.yaml", *KEPT}
    for name in zipped:
        parts = name.split("/")
        assert parts[-1] not in {".env", "auth.json", "vault.key", "vault.json.enc", "gateway.pid",
                                 "gateway_state.json"}, name
        assert not {"profiles", "mcp-tokens", "browser-profile"} & set(parts), name
        assert not (parts[0] == "cron" and parts[-1].endswith(".lock")), name
    for name, data in zipped.items():
        assert not _canaries_in(data), name
    assert reply["parts"] == PARTS
    assert Path(reply["path"]).parent == cache / "exports"
    assert reply["name"] == Path(reply["path"]).name and reply["name"].startswith("hermes-backup-")
    assert reply["bytes"] == Path(reply["path"]).stat().st_size


def test_the_archived_settings_keep_every_setting_and_blank_every_key_field(home, cache):
    archived = yaml.safe_load(_members(Path(create()["path"]))["config.yaml"])

    assert archived["model"]["default"] == "my-model"
    assert archived["approvals"]["timeout"] == 77
    assert archived["custom_providers"][0]["key_env"] == "HERMES_CUSTOM_X_API_KEY"
    assert archived["platforms"]["api_server"]["extra"]["port"] == 8642
    for path in KEY_FIELDS:
        assert _get(archived, path) == "", path


# Names in DEFAULT_CONFIG that carry key/token/secret/password/credential but hold no secret, one reason each.
NOT_SECRET = {
    "credential_pool_strategies": "how the credential pool rotates, a strategy name per provider",
    "credential_source": "where a credential is read from (a source kind), not the credential",
    "docker_shared_container_key": "a container-sharing grouping label",
    "keyless_fallback": "a switch for keyless providers",
    "keyless_rescue": "a switch for keyless providers",
    "record_key": "which field names a record, not a secret",
    "keyword": "a search word",
    "threshold_tokens": "a token count",
    "proactive_prune_tokens": "a token count",
    "proactive_prune_min_reclaim_tokens": "a token count",
    "micro_compact_defrag_threshold_tokens": "a token count",
    "compression_threshold_tokens": "a token count",
    "listing_max_tokens": "a token count",
    "spinner_token_flow": "a display switch",
    "show_token_analytics": "a display switch",
    "min_secret_chars": "the redactor's minimum length, a count",
    "redact_secrets": "the redactor's switch",
    "password_store": "which keyring backend stores a password, not the password",
    "service_account_token_env": "names the environment slot, not the value",
    "access_token_env": "names the environment slot, not the value",
    "api_key_env": "names the environment slot, not the value",
    "key_env": "names the environment slot, not the value",
    "onepassword": "the 1Password section (a switch, the account, the binary, env slot names); its leaves are walked",
    "secrets": "the secret-manager section (bitwarden, onepassword: switches and env slot names); its leaves are walked",
}
SECRET_WORDS = ("key", "token", "secret", "password", "credential")


def _walk(original, blanked, path=()):
    if isinstance(original, dict):
        for name, value in original.items():
            yield path + (name,), name, value, blanked[name]
            if blanked[name] != "":
                yield from _walk(value, blanked[name], path + (name,))
    elif isinstance(original, list):
        for i, (a, b) in enumerate(zip(original, blanked)):
            yield from _walk(a, b, path + (i,))


def _filled(value):
    """DEFAULT_CONFIG's shape with every leaf set (most defaults are empty, and an empty leaf reads as blanked)."""
    if isinstance(value, dict):
        return {k: _filled(v) if isinstance(v, (dict, list)) and v else "set" for k, v in value.items()}
    return [_filled(v) if isinstance(v, (dict, list)) and v else "set" for v in value]


def test_every_key_bearing_default_is_blanked_or_reviewed_as_no_secret():
    from hermes_android.backup import blank_keys
    from hermes_cli.config_defaults import DEFAULT_CONFIG

    filled = _filled(DEFAULT_CONFIG)
    blanked = blank_keys(filled)
    unreviewed = []
    for path, name, _value, after in _walk(filled, blanked):
        lower = str(name).lower()
        if any(word in lower for word in SECRET_WORDS) and after != "" and lower not in NOT_SECRET:
            unreviewed.append(".".join(map(str, path)))
    assert unreviewed == []
    blanked_names = {str(name) for _p, name, value, after in _walk(filled, blanked) if after == "" and value != ""}
    assert {"api_key", "password", "password_hash", "secret", "session_key"} <= blanked_names
    assert blank_keys(DEFAULT_CONFIG) is not DEFAULT_CONFIG
    for name in ("api_key_env", "key_env", "access_token_env", "min_secret_chars", "redact_secrets"):
        assert blank_keys({name: "kept"}) == {name: "kept"}, name


def test_blank_keys_blanks_any_shape_and_recurses_through_lists():
    from hermes_android.backup import blank_keys

    given = {"a": [{"api_key": {"nested": 1}}, {"token": [1, 2]}], "openai_api_key": 3, "bot_token": "t",
             "client_secret": None, "db_password": "p", "Key": "k", "keep": {"key_env": "X", "max_tokens": 9}}
    assert blank_keys(given) == {"a": [{"api_key": ""}, {"token": ""}], "openai_api_key": "", "bot_token": "",
                                 "client_secret": "", "db_password": "", "Key": "",
                                 "keep": {"key_env": "X", "max_tokens": 9}}
    assert given["a"][0]["api_key"] == {"nested": 1}  # pure: the input is untouched


def test_the_sessions_are_a_snapshot_with_the_wal_row_and_no_sidecar(home, cache, tmp_path):
    zipped = _members(Path(create()["path"]))

    assert not {"state.db-wal", "state.db-shm"} & set(zipped)
    copy = tmp_path / "snapshot.db"
    copy.write_bytes(zipped["state.db"])
    assert _count(copy) == 4


def test_a_new_backup_replaces_only_the_previous_backup(home, cache):
    exports = cache / "exports"
    exports.mkdir()
    (exports / "hermes-backup-2020-01-01-000000.zip").write_bytes(b"old")
    (exports / "hermes-backup-2020-01-01-000000.zip.part").write_bytes(b"left over")
    (exports / "foo-12345678.json").write_text("{}", encoding="utf-8")

    first = create()
    second = create()

    assert sorted(p.name for p in exports.iterdir()) == sorted({"foo-12345678.json", second["name"]})
    assert first["bytes"] > 0 and Path(second["path"]).is_file()


def test_a_failed_snapshot_answers_in_words_and_leaves_nothing(home, cache, monkeypatch):
    from hermes_cli import backup as core

    monkeypatch.setattr(core, "_safe_copy_db", lambda src, dst, **kw: False)

    reply = create()

    assert set(reply) == {"error"} and reply["error"].startswith("The backup could not be written: ")
    assert [p.name for p in (cache / "exports").iterdir()] == []


# --- backup_restore ---

def _key_fields_blank(cfg: dict) -> None:
    for path in KEY_FIELDS:
        try:
            value = _get(cfg, path)
        except (KeyError, IndexError):
            continue  # a blank equal to the default is not written at all
        assert value == "", path


def _no_canary_under(root: Path) -> None:
    for p in root.rglob("*"):
        if p.is_file():
            assert not _canaries_in(p.read_bytes()), p


def test_a_round_trip_restores_the_five_parts_into_an_empty_home(home, cache, tmp_path, monkeypatch):
    archive = Path(create()["path"])
    b = _switch(monkeypatch, tmp_path / "home_b")

    reply = restore(archive)

    assert reply == {"restored": PARTS, "skipped": 0, "sessions": 4}
    assert _count(b / "state.db") == 4
    for rel, text in KEPT.items():
        assert (b / rel).read_text(encoding="utf-8") == text, rel
    restored = yaml.safe_load((b / "config.yaml").read_text(encoding="utf-8"))
    assert restored["model"]["default"] == "my-model" and restored["approvals"]["timeout"] == 77
    _key_fields_blank(restored)
    _no_canary_under(b)


def _desktop_archive(home: Path, out: Path) -> Path:
    from hermes_cli.backup import _write_full_zip_backup

    assert _write_full_zip_backup(out, home) is not None
    names = set(zipfile.ZipFile(out).namelist())
    assert {".env", "auth.json", "vault.key", "vault.json.enc", "mcp-tokens/srv.json", "profiles/work/.env"} <= names
    return out


def _prefixed(src: Path, out: Path, prefix: str) -> Path:
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            if not info.is_dir():
                zout.writestr(prefix + info.filename, zin.read(info))
    return out


@pytest.mark.parametrize("prefix", ["", ".hermes/"])
def test_a_desktop_archive_restores_no_secret_and_counts_what_it_skips(home, cache, tmp_path, monkeypatch, prefix):
    archive = _desktop_archive(home, tmp_path / "desktop.zip")
    if prefix:
        archive = _prefixed(archive, tmp_path / "prefixed.zip", prefix)
    b = _switch(monkeypatch, tmp_path / "home_b")

    reply = restore(archive)

    for rel in (".env", "auth.json", "vault.key", "vault.json.enc", "mcp-tokens", "profiles", "browser-profile",
                "gateway_state.json", "skills/research/arxiv/.env"):
        assert not (b / rel).exists(), rel
    assert reply["skipped"] >= 6
    assert reply["sessions"] == 4
    assert {"sessions", "config", "memories", "skills", "jobs"} <= set(reply["restored"])
    _no_canary_under(b)
    _key_fields_blank(yaml.safe_load((b / "config.yaml").read_text(encoding="utf-8")))
    assert (b / "memories" / "MEMORY.md").read_text(encoding="utf-8") == KEPT["memories/MEMORY.md"]


@pytest.fixture
def target(home, cache, tmp_path, monkeypatch):
    """A valid backup of home A, and home B with its own files, the restore's target."""
    archive = Path(create()["path"])
    b = _switch(monkeypatch, tmp_path / "home_b")
    (b / "memories").mkdir()
    (b / "memories" / "MEMORY.md").write_text("B's own memory.\n", encoding="utf-8")
    (b / "config.yaml").write_text("model:\n  default: b-model\n", encoding="utf-8")
    _sessions_db(b / "state.db", ["20261116_100000_eeeeee"])
    return archive, b


def _zip(path: Path, members: dict[str, bytes | str]) -> Path:
    with zipfile.ZipFile(path, "w") as zf:
        for name, data in members.items():
            zf.writestr(zipfile.ZipInfo(name), data)
    return path


@pytest.fixture
def turn_running(monkeypatch):
    monkeypatch.setattr(sys, "stdout", sys.stdout)  # the module points stdout at stderr when imported
    from tui_gateway import server

    with server._sessions_lock:
        server._sessions["p2-probe"] = {"running": True}
    try:
        yield
    finally:
        with server._sessions_lock:
            server._sessions.pop("p2-probe", None)


@pytest.fixture
def gateway_running(target):
    from gateway.status import _get_gateway_lock_path

    _, b = target
    lock = b / "gateway.lock"
    assert _get_gateway_lock_path() == lock
    child = subprocess.Popen(
        [sys.executable, "-c", "import fcntl, sys\nf = open(sys.argv[1], 'a+')\nfcntl.flock(f, fcntl.LOCK_EX)\n"
                               "print('locked', flush=True)\nsys.stdin.read()\n", str(lock)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        assert child.stdout.readline().strip() == "locked"
        yield
    finally:
        child.kill()
        child.wait(timeout=10)


def test_a_restore_while_a_turn_runs_is_refused_and_writes_nothing(target, turn_running):
    archive, b = target
    before = _tree(b)

    reply = restore(archive)

    assert (reply, _tree(b)) == ({"error": BUSY}, before)


def test_a_restore_while_the_gateway_runs_is_refused_and_writes_nothing(target, gateway_running):
    archive, b = target
    before = _tree(b)

    reply = restore(archive)

    assert _tree(b) == before
    assert reply == {"error": GATEWAY}


@pytest.mark.parametrize(("members", "words"), [
    (None, NOT_ZIP),
    ({"notes.txt": "hi"}, NOT_HERMES),
    ({".env": "OPENAI_API_KEY=canary-env-1\n", "auth.json": "{}"}, NOTHING),
    ({"config.yaml": "- a\n- b\n"}, BAD_CONFIG),
    ({"config.yaml": "model:\n  default: x\n", "state.db": os.urandom(4096)}, BAD_SESSIONS),
])
def test_a_file_with_nothing_good_to_restore_is_refused_and_writes_nothing(target, tmp_path, members, words):
    _, b = target
    picked = tmp_path / "picked.zip"
    if members is None:
        picked.write_text("not a zip at all\n", encoding="utf-8")
    else:
        _zip(picked, members)
    before = _tree(b)

    reply = restore(picked)

    assert (reply, _tree(b)) == ({"error": words}, before)


def test_a_traversing_or_absolute_member_is_skipped_and_nothing_lands_outside(target, tmp_path):
    _, b = target
    absolute = tmp_path / "abs-landing.txt"
    picked = _zip(tmp_path / "picked.zip", {"config.yaml": "model:\n  default: x\n",
                                            "skills/../../outside.txt": "out", str(absolute): "abs"})

    reply = restore(picked)

    assert reply == {"restored": ["config"], "skipped": 2, "sessions": 0}
    assert not (tmp_path / "outside.txt").exists() and not (b.parent / "outside.txt").exists()
    assert not absolute.exists()


def test_the_restored_sessions_reach_a_handle_that_stayed_open(target):
    import hermes_state_registry

    archive, b = target
    db = hermes_state_registry.acquire()
    try:
        inode = os.stat(b / "state.db").st_ino
        assert db.get_session(SESSIONS[0]) is None

        assert restore(archive)["sessions"] == 4

        assert os.stat(b / "state.db").st_ino == inode
        for sid in [*SESSIONS, WAL_SESSION]:
            assert db.get_session(sid) is not None, sid
    finally:
        hermes_state_registry.release_or_close(db)


def test_a_picked_copy_in_the_imports_folder_is_deleted_and_any_other_file_is_kept(target, cache, tmp_path):
    archive, _ = target
    imports = cache / "imports"
    imports.mkdir()
    picked = imports / "picked.zip"
    picked.write_bytes(archive.read_bytes())
    assert "restored" in restore(picked)
    assert not picked.exists()

    refused = imports / "refused.zip"
    refused.write_text("not a zip\n", encoding="utf-8")
    assert restore(refused) == {"error": NOT_ZIP}
    assert not refused.exists()

    elsewhere = tmp_path / "elsewhere.zip"
    elsewhere.write_bytes(archive.read_bytes())
    assert "restored" in restore(elsewhere)
    assert elsewhere.is_file()
    assert sorted(p.name for p in imports.iterdir()) == []  # no temporary copy is left either


def test_the_one_rule_maps_both_directions():
    from hermes_android.backup import part_of

    assert part_of("state.db") == "sessions"
    assert part_of("config.yaml") == "config"
    assert part_of("memories/MEMORY.md") == "memories"
    assert part_of("skills/.archive/old/SKILL.md") == "skills"
    assert part_of("skills/.usage.json") == "skills"
    assert part_of("cron/jobs.json") == "jobs"
    assert part_of("cron/output/j1/run.md") == "jobs"
    for rel in (".env", "auth.json", "vault.key", "vault.json.enc", "mcp-tokens/srv.json", "profiles/work/.env",
                "_external/.honcho/x", "browser-profile/Cookies", "gateway.pid", "gateway_state.json", "gateway.lock",
                "cron/.jobs.lock", "cron/cron.pid", "skills/research/arxiv/.env", "skills/a/mcp-tokens/t.json",
                "memories", "skills", "state.db-wal", "skills/../../x", "/tmp/x", "", "other.yaml",
                "skills/x/__pycache__/m.pyc"):
        assert part_of(rel) is None, rel


def test_members_walks_only_the_five_roots(home):
    from hermes_android.backup import members

    found = members(home)

    assert [str(p.relative_to(home)) for p, _ in found] == sorted(["state.db", "config.yaml", *KEPT])
    assert dict((str(p.relative_to(home)), part) for p, part in found)["skills/.usage.json"] == "skills"


def test_an_io_error_while_writing_is_answered_in_words(target, monkeypatch):
    from hermes_cli import backup_restore

    archive, _ = target

    def broken(*args, **kwargs):
        raise OSError("disk full")

    monkeypatch.setattr(backup_restore, "_extract_member_atomically", broken)

    assert restore(archive) == {"error": "The backup could not be restored: disk full"}

