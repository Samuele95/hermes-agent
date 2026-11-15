"""The settings ``config.set`` refuses, through the app's own bridge pair (batch-3 F3, F4, bet 7).

``config.set`` answers 4002 for ``approvals.timeout``, ``command_allowlist`` and ``memory.*``, so the app reads
and writes exactly those keys with ``settings_read`` / ``settings_write``: a closed key set (DC-M15), every write
one ``_config.mutate`` (E5), never ``.env``. Removing an always-allowed command also reconciles this process's
governing allowlist through the core's ``save_permanent_allowlist(set())`` (DC-M16); the ``:gateway`` follows at
its next start (Dv31). Personality and ``approvals.mode`` stay on ``config.set``.
"""

from __future__ import annotations

import importlib
import json
import threading

import pytest

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest

UNTOUCHED = {"approvals_timeout_s": None, "memory_enabled": None, "user_profile_enabled": None,
             "memory_char_limit": None, "user_char_limit": None, "allowlist_remove": []}

SEEDED = """\
model:
  default: m1
approvals:
  mode: manual
  timeout: 300
memory:
  provider: honcho
display:
  personality: pirate
command_allowlist:
- git status
"""


@pytest.fixture
def home():
    from hermes_constants import get_hermes_home

    path = get_hermes_home()  # the hermetic conftest's per-test home
    path.mkdir(parents=True, exist_ok=True)
    return path


@pytest.fixture
def approval_state():
    """This process's approval state, saved, cleared and given back (the shape of the core's own reconcile test,
    tests/tools/test_permanent_allowlist_reconcile.py), with the session approvals the test made dropped."""
    from tools import approval

    saved_approved = set(approval._permanent_approved)
    saved_baseline = dict(approval._permanent_baseline_by_home)
    approval._permanent_approved.clear()
    approval._permanent_baseline_by_home.clear()
    try:
        yield approval
    finally:
        for session in ("s1", "s2"):
            approval.clear_session(session)
        approval._permanent_approved.clear()
        approval._permanent_approved.update(saved_approved)
        approval._permanent_baseline_by_home.clear()
        approval._permanent_baseline_by_home.update(saved_baseline)


def _read() -> dict:
    return json.loads(hermes_android.call("settings_read", ""))


def _write(**fields) -> dict:
    return json.loads(hermes_android.call("settings_write", json.dumps({**UNTOUCHED, **fields})))


def _raw() -> dict:
    from hermes_cli.config import read_user_config_raw

    return read_user_config_raw()


def test_a_fresh_home_reads_the_cores_defaults(home):
    answer = _read()

    personalities = answer.pop("personalities")
    assert answer == {"approvals_timeout_s": 300, "command_allowlist": [], "memory_enabled": True,
                      "user_profile_enabled": True, "memory_char_limit": 2200, "user_char_limit": 1375}
    assert len(personalities) == 14
    assert personalities[0] == {"name": "helpful", "description": "You are a helpful, friendly AI assistant."}
    kawaii = next(p for p in personalities if p["name"] == "kawaii")
    assert kawaii["description"].endswith("...")  # the core's preview line, not the whole prompt
    assert len(kawaii["description"]) == 53


def test_the_read_is_what_config_yaml_holds(home):
    (home / "config.yaml").write_text(
        "approvals:\n  mode: manual\n  timeout: 90\n"
        "command_allowlist:\n- git status\n- ls *\n"
        "memory:\n  memory_enabled: false\n  user_char_limit: 500\n"
        "agent:\n  personalities:\n    tutor:\n      description: A patient tutor\n      system_prompt: Teach.\n",
        encoding="utf-8")

    answer = _read()

    assert answer["approvals_timeout_s"] == 90
    assert answer["command_allowlist"] == ["git status", "ls *"]
    assert answer["memory_enabled"] is False
    assert answer["user_profile_enabled"] is True
    assert answer["user_char_limit"] == 500
    assert answer["memory_char_limit"] == 2200
    assert answer["personalities"][-1] == {"name": "tutor", "description": "A patient tutor"}


@pytest.mark.parametrize(("block", "field", "expected"), [
    ("approvals:\n  timeout: soon\n", "approvals_timeout_s", 300),
    ("command_allowlist: git status\n", "command_allowlist", []),
    ("memory:\n  memory_char_limit: lots\n", "memory_char_limit", 2200),
], ids=["timeout", "allowlist", "limit"])
def test_a_malformed_value_reads_as_the_core_reads_it(home, block, field, expected):
    (home / "config.yaml").write_text(block, encoding="utf-8")

    assert _read()[field] == expected


@pytest.mark.parametrize(("field", "value", "path"), [
    ("approvals_timeout_s", 120, ("approvals", "timeout")),
    ("memory_enabled", False, ("memory", "memory_enabled")),
    ("user_profile_enabled", False, ("memory", "user_profile_enabled")),
    ("memory_char_limit", 3000, ("memory", "memory_char_limit")),
    ("user_char_limit", 0, ("memory", "user_char_limit")),
], ids=["timeout", "memory", "profile", "memory-limit", "profile-limit"])
def test_each_field_is_written_alone_and_keeps_the_rest(home, field, value, path):
    (home / "config.yaml").write_text(SEEDED, encoding="utf-8")

    assert _write(**{field: value}) == {"applied": True}

    raw = _raw()
    assert raw[path[0]][path[1]] == value
    assert raw["approvals"]["mode"] == "manual"
    assert raw["memory"]["provider"] == "honcho"
    assert raw["model"]["default"] == "m1"
    assert raw["display"]["personality"] == "pirate"
    assert raw["command_allowlist"] == ["git status"]
    assert not (home / ".env").exists()
    assert _read()[field] == value


def test_an_empty_edit_or_the_same_values_write_nothing(home):
    (home / "config.yaml").write_text(SEEDED, encoding="utf-8")
    before = (home / "config.yaml").stat()

    assert _write() == {"applied": False}
    assert _write(approvals_timeout_s=300) == {"applied": False}

    after = (home / "config.yaml").stat()
    assert (after.st_ino, after.st_mtime_ns) == (before.st_ino, before.st_mtime_ns)


@pytest.mark.parametrize(("field", "value", "words"), [
    ("approvals_timeout_s", 0, "The approval timeout must be at least 1 second."),
    ("approvals_timeout_s", -5, "The approval timeout must be at least 1 second."),
    ("memory_char_limit", -1, "A memory size limit cannot be negative."),
    ("user_char_limit", -1, "A memory size limit cannot be negative."),
], ids=["timeout-0", "timeout-negative", "memory-limit", "profile-limit"])
def test_an_out_of_range_value_is_refused_and_nothing_is_written(home, approval_state, field, value, words):
    (home / "config.yaml").write_text(SEEDED, encoding="utf-8")
    approval_state.load_permanent_allowlist()
    before = (home / "config.yaml").read_bytes()

    # The refused edit also names a removal: nothing of it may happen, the reconcile included.
    assert _write(**{field: value}, allowlist_remove=["git status"]) == {"error": words}

    assert (home / "config.yaml").read_bytes() == before
    assert approval_state._is_permanently_approved("git status")


def test_a_key_the_pair_does_not_name_is_refused_before_any_write(home):
    (home / "config.yaml").write_text(SEEDED, encoding="utf-8")
    before = (home / "config.yaml").read_bytes()

    for extra in ({"approvals_mode": "off"}, {"personality": "helpful"}):
        with pytest.raises(ValueError):
            hermes_android.call("settings_write", json.dumps({**UNTOUCHED, "approvals_timeout_s": 60, **extra}))
    missing = {key: value for key, value in UNTOUCHED.items() if key != "allowlist_remove"}
    with pytest.raises(ValueError):
        hermes_android.call("settings_write", json.dumps({**missing, "approvals_timeout_s": 60}))

    assert (home / "config.yaml").read_bytes() == before


def test_removing_a_pattern_keeps_the_others_in_order(home, approval_state):
    (home / "config.yaml").write_text("command_allowlist:\n- git status\n- ls *\n- npm test\n", encoding="utf-8")

    assert _write(allowlist_remove=["ls *", "not there"]) == {"applied": True}
    assert _raw()["command_allowlist"] == ["git status", "npm test"]

    assert _write(allowlist_remove=["not there"]) == {"applied": False}
    assert _raw()["command_allowlist"] == ["git status", "npm test"]


def test_a_removed_always_allowed_command_asks_again_in_another_chat(home, approval_state):
    approval = approval_state
    (home / "config.yaml").write_text("command_allowlist:\n- git status\n", encoding="utf-8")
    approval.load_permanent_allowlist()  # the import-time load, against this home
    # "Always allow" pressed in the chat s1, exactly as _persist_choice records it.
    approval.approve_session("s1", "docker *")
    approval.approve_permanent("docker *")
    approval.save_permanent_allowlist(set(approval._permanent_approved))
    assert "docker *" in _raw()["command_allowlist"]
    assert approval.is_approved("s2", "docker *"), "the standing approval never took, so the test proves nothing"

    from hermes_android import settings
    assert json.loads(settings.write(json.dumps({**UNTOUCHED, "allowlist_remove": ["docker *"]}))) == {"applied": True}

    assert "docker *" not in _raw()["command_allowlist"]
    assert not approval.is_approved("s2", "docker *")
    assert not approval._is_permanently_approved("docker *")
    assert approval.is_approved("s2", "git status")
    # D7: the chat that pressed "Always allow" keeps its session approval until it ends; the core's only public
    # way to drop it, clear_session, would drop that chat's other choices too.
    assert approval.is_approved("s1", "docker *")


def test_a_write_waits_for_the_apps_config_lock(home):
    from hermes_android import _config, settings

    a_in, release = threading.Event(), threading.Event()

    def fn_a(cfg):
        a_in.set()
        release.wait(timeout=10)
        cfg.setdefault("providers", {})["a"] = {"name": "a", "base_url": "http://10.0.0.9/v1", "model": "m"}

    a = threading.Thread(target=_config.mutate, args=(fn_a,))
    a.start()
    assert a_in.wait(timeout=10)
    b = threading.Thread(target=settings.write, args=(json.dumps({**UNTOUCHED, "approvals_timeout_s": 120}),))
    b.start()
    try:
        b.join(timeout=1.0)
        approvals = (_raw() or {}).get("approvals") or {}
        assert b.is_alive() and "timeout" not in approvals, "the write landed while another app write held the span"
    finally:
        release.set()
        a.join(timeout=10)
        b.join(timeout=10)

    raw = _raw()
    assert "a" in raw["providers"]
    assert raw["approvals"]["timeout"] == 120
