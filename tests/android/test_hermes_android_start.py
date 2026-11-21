"""What the app's embedded interpreter runs before the core.

The interpreter lives inside the app process: ``sys.executable`` would otherwise name a binary that
is not Python (``app_process64``), and every subprocess spawner in the core would exec it. The
runtime is a directory the app extracted, so the core and the wheels are put on ``sys.path`` here.
"""

from __future__ import annotations

import importlib
import json
import logging
import os
import re
import sys
import time
from pathlib import Path

import pytest

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest


@pytest.fixture
def paths(tmp_path, monkeypatch):
    for name in ("HOME", "HERMES_HOME", "TMPDIR"):
        monkeypatch.setenv(name, os.environ.get(name, ""))
    monkeypatch.setattr(sys, "path", list(sys.path))
    monkeypatch.setattr(sys, "executable", sys.executable)
    monkeypatch.setattr(sys, "_base_executable", getattr(sys, "_base_executable", ""))
    runtime = tmp_path / "runtime"
    (runtime / "site-packages" / "wheel_pkg").mkdir(parents=True)
    (runtime / "site-packages" / "extra.pth").write_text("pth-dir\n", encoding="utf-8")
    (runtime / "site-packages" / "pth-dir").mkdir()
    (runtime / "app").mkdir()
    (tmp_path / "lib").mkdir()  # the app's nativeLibraryDir; a test puts the launcher there when it needs one
    return {"runtime": str(runtime), "home": str(tmp_path / "files"), "hermes_home": str(tmp_path / "files/.hermes"),
            "tmp": str(tmp_path / "cache"), "native_libs": str(tmp_path / "lib")}


def test_the_app_owns_the_home_and_temp_directories(paths):
    hermes_android.prepare(paths)

    assert (os.environ["HOME"], os.environ["HERMES_HOME"], os.environ["TMPDIR"]) == (
        paths["home"], paths["hermes_home"], paths["tmp"])


def test_no_interpreter_is_offered_to_subprocess_spawners(paths):
    hermes_android.prepare(paths)

    assert sys.executable == "" and getattr(sys, "_base_executable", None) == ""


def test_the_wheels_openssl_legacy_provider_is_declared_off(paths, monkeypatch):
    # The wheels' OpenSSL (Termux-built) looks for its legacy provider under the Termux prefix, which an
    # app cannot read, so every `import cryptography` warned on stderr — a logcat stream in the app. The
    # algorithms were unavailable either way; declaring it keeps the log quiet and changes nothing else.
    monkeypatch.delenv("CRYPTOGRAPHY_OPENSSL_NO_LEGACY", raising=False)

    hermes_android.prepare(paths)

    assert os.environ["CRYPTOGRAPHY_OPENSSL_NO_LEGACY"] == "1"


def test_lazy_dependency_installs_are_off_in_the_app(paths, monkeypatch):
    # The core's package manager would fetch a Termux binary (uvx, npx, node) the app can never exec (bet 6, F3).
    # tests/conftest.py sets the variable for every host test: start from an unset one, then from a "0".
    monkeypatch.delenv("HERMES_DISABLE_LAZY_INSTALLS", raising=False)
    hermes_android.prepare(paths)
    assert os.environ["HERMES_DISABLE_LAZY_INSTALLS"] == "1"

    monkeypatch.setenv("HERMES_DISABLE_LAZY_INSTALLS", "0")
    hermes_android.prepare(paths)
    assert os.environ["HERMES_DISABLE_LAZY_INSTALLS"] == "1"


def test_prepare_points_home_bin_python3_at_the_extracted_launcher(paths):
    # Both of the app's interpreters run prepare(): each start rewrites the link the MCP config names (D54 row 1).
    launcher = Path(paths["native_libs"]) / "libhermes_pyexec.so"
    launcher.write_bytes(b"\x7fELF")

    hermes_android.prepare(paths)

    link = Path(paths["home"]) / "bin" / "python3"
    assert link.is_symlink() and os.readlink(link) == str(launcher)
    assert (link.parent / ".hermes-runtime").read_text(encoding="utf-8").strip() == paths["runtime"]


def test_tls_verifies_against_certifis_authorities_unless_the_host_chose_a_bundle(paths, monkeypatch):
    # python.org's Android CPython finds no system CA store, so every HTTPS request through ssl's default context
    # (urllib: the model catalog, Nous account, the Telegram check, ...) failed verification on the phone.
    import certifi

    monkeypatch.delenv("SSL_CERT_FILE", raising=False)
    hermes_android.prepare(paths)
    assert os.environ["SSL_CERT_FILE"] == certifi.where()

    monkeypatch.setenv("SSL_CERT_FILE", "/etc/ssl/corporate.pem")
    hermes_android.prepare(paths)
    assert os.environ["SSL_CERT_FILE"] == "/etc/ssl/corporate.pem"


def test_the_core_and_the_wheels_are_importable_with_pth_files_honoured(paths):
    runtime = Path(paths["runtime"])

    hermes_android.prepare(paths)

    assert str(runtime / "app") in sys.path and str(runtime / "site-packages") in sys.path
    assert str(runtime / "site-packages" / "pth-dir") in sys.path
    # The core's own top-level packages win over a same-named distribution in site-packages.
    assert sys.path.index(str(runtime / "app")) < sys.path.index(str(runtime / "site-packages"))


@pytest.fixture
def isolated_home(paths, monkeypatch):
    # start() registers the keystore source and enables it in this profile's config.yaml.
    monkeypatch.setenv("HERMES_HOME", paths["hermes_home"])
    yield
    from agent.secret_sources import registry
    registry._reset_registry_for_tests()


def test_start_connects_the_host_as_a_gateway_peer(paths, isolated_home, monkeypatch):
    frames: list[dict] = []
    prepared: list[dict] = []
    monkeypatch.setattr(hermes_android, "prepare", prepared.append)  # host test: keep this interpreter's paths

    class Native:
        def secrets(self) -> str:
            return "{}"

        def emit(self, line: str, /) -> bool:
            frames.append(json.loads(line))
            return True

    hermes_android.start(paths, Native())
    try:
        hermes_android.submit(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}))
    finally:
        hermes_android.stop()

    from agent.secret_sources import registry

    assert prepared == [paths]
    assert registry.get_source("android_keystore") is not None  # before the core read its environment
    assert frames[0]["params"]["type"] == "gateway.ready"
    assert {"jsonrpc": "2.0", "id": 1, "result": {"pong": True}} in frames


def test_start_seeds_the_bundled_skills_into_a_home_that_has_none(paths, isolated_home, monkeypatch):
    # The CLI seeds them at its startup (hermes_cli.main); the app's host never runs it.
    frames: list[dict] = []
    monkeypatch.setattr(hermes_android, "prepare", lambda _paths: None)

    class Native:
        def secrets(self) -> str:
            return "{}"

        def emit(self, line: str, /) -> bool:
            frames.append(json.loads(line))
            return True

    hermes_android.start(paths, Native())
    try:
        hermes_android.submit(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "skills.manage", "params": {}}))
        deadline = time.monotonic() + 30  # skills.manage answers from the RPC pool
        while not any(f.get("id") == 1 for f in frames) and time.monotonic() < deadline:
            time.sleep(0.05)
    finally:
        hermes_android.stop()

    from hermes_constants import get_hermes_home
    assert next((get_hermes_home() / "skills").rglob("SKILL.md"), None) is not None
    listed = next(f["result"]["skills"] for f in frames if f.get("id") == 1)
    assert "github" in [name for names in listed.values() for name in names]


def test_importing_the_bootstrap_imports_no_core_module():
    # On the device the core is not on sys.path until prepare() runs, and secret sources must be
    # registered before the core's first import reads them.
    import subprocess

    from tests.android.conftest import ANDROID_PYTHON

    probe = ("import sys; sys.path.insert(0, sys.argv[1]); import hermes_android; "
             "print(sorted({m.split('.')[0] for m in sys.modules} & {'tui_gateway', 'agent', 'tools', 'hermes_cli', 'run_agent'}))")
    out = subprocess.run([sys.executable, "-I", "-c", probe, str(ANDROID_PYTHON)], capture_output=True, text=True,
                         encoding="utf-8", timeout=60, check=True).stdout

    assert out.strip() == "[]"


REPOSITORY = Path(__file__).resolve().parents[2]

# What a fresh interpreter needs to run the bootstrap as the bridge does, with the file's two-method Native.
_PROBE_START = """
import json, sys
sys.path[:0] = [sys.argv[1], sys.argv[2]]
import hermes_android
frames = []
class Native:
    def secrets(self):
        return "{}"
    def emit(self, line, /):
        frames.append(json.loads(line))
        return True
ADAPTER = "plugins.platforms.telegram.adapter"  # one of the platform adapters the first read used to load
def report(value):  # tui_gateway.server points sys.stdout at stderr when it is imported
    sys.__stdout__.write(json.dumps(value) + "\\n")
"""


def _probe_run(tmp_path, body: str):
    """Runs ``body`` after :data:`_PROBE_START` in a fresh interpreter (pytest's own already holds the core's
    modules) against a home under ``tmp_path``, and returns the finished process with its raw stdout."""
    import subprocess

    from tests.android.conftest import ANDROID_PYTHON

    run = subprocess.run([sys.executable, "-I", "-c", _PROBE_START + body, str(REPOSITORY), str(ANDROID_PYTHON)],
                         capture_output=True, text=True, encoding="utf-8", timeout=300, check=False,
                         env={**os.environ, "HERMES_HOME": str(tmp_path / ".hermes"), "HOME": str(tmp_path)})
    assert run.returncode == 0, run.stderr[-3000:]
    return run


def _probe(tmp_path, body: str):
    """:func:`_probe_run`, for a ``body`` that reports one JSON line: returns that report."""
    return json.loads(_probe_run(tmp_path, body).stdout.strip().splitlines()[-1])


def test_prepare_imports_no_core_module(tmp_path):
    # prepare() runs before the keystore source is registered and before the core's first env load, and in the
    # :gateway process too: no core import (nor the delivery-targets warm) belongs in it. The repository root is on
    # the probe's path, so a prepare() that imported the core would succeed and show here.
    runtime = tmp_path / "runtime"
    (runtime / "site-packages").mkdir(parents=True)
    (runtime / "app").mkdir()
    paths = {"runtime": str(runtime), "home": str(tmp_path / "files"), "hermes_home": str(tmp_path / "files/.hermes"),
             "tmp": str(tmp_path / "cache"), "native_libs": str(tmp_path / "lib")}
    body = f"""
hermes_android.prepare({paths!r})
core = {{"cron", "gateway", "tui_gateway", "agent", "tools", "hermes_cli", "run_agent"}}
report(sorted({{m.split('.')[0] for m in sys.modules}} & core))
"""
    assert _probe(tmp_path, body) == []


def test_start_warms_the_job_forms_delivery_targets_beside_the_frames_thread(tmp_path):
    # The job form's first cron_delivery_targets loaded every plugin and platform adapter (about 2.8 s on the phone)
    # on hermes-python, the thread that carries the chat's frames (/architect 20261113T220113Z #2).
    body = """
import time
hermes_android.prepare = lambda paths: None  # host probe: keep this interpreter's paths
before = ADAPTER in sys.modules
hermes_android.start({"role": "chat"}, Native())
imported = "cron.scheduler_delivery" in sys.modules
held = ADAPTER in sys.modules
warm = getattr(hermes_android, "_warm", None)
hermes_android.submit(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}))
deadline = time.monotonic() + 30
while not any(f.get("id") == 1 for f in frames) and time.monotonic() < deadline:
    time.sleep(0.01)
pong = {"jsonrpc": "2.0", "id": 1, "result": {"pong": True}} in frames
hermes_android.call("cron_warm_delivery_targets", "")  # what composeCore asks after its startup calls
hermes_android.stop()
report({"before": before, "imported": imported, "held": held, "pong": pong,
        "thread": [warm.name, warm.daemon] if warm is not None else None,
        "alive": warm is not None and warm.is_alive(), "adapters": ADAPTER in sys.modules})
"""
    seen = _probe(tmp_path, body)

    assert seen["before"] is False, "the adapter was loaded before start(): the probe proves nothing"
    assert seen["imported"] is True, "cron.scheduler_delivery is not in sys.modules when start() returns"
    assert seen["held"] is False, "the warm held the frames thread"
    assert seen["thread"] == ["hermes-prewarm", True]
    assert seen["pong"] is True, "the chat did not answer while the warm ran"
    assert seen["alive"] is False, "the warm outlived stop()"
    assert seen["adapters"] is True, "the warm did not load what the first cron_delivery_targets used to pay for"


# Records the warm's load instead of running it: these tests measure the gate, not the 2 s load.
_RECORDED_WARM = """
import threading, time
hermes_android.prepare = lambda paths: None  # host probe: keep this interpreter's paths
calls = []
done = threading.Event()
def load():
    calls.append(1)
    done.set()
hermes_android._load_delivery_targets = load
"""


def test_the_warm_waits_until_the_apps_startup_calls_are_done(tmp_path):
    # Started at once, the warm's plugin loads slowed composeCore's startup calls through the GIL (K1: +655 ms to
    # ready on the phone): it waits for the signal composeCore sends after them.
    body = _RECORDED_WARM + """
hermes_android.start({"role": "chat"}, Native())
hermes_android.submit(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}))
deadline = time.monotonic() + 30
while not any(f.get("id") == 1 for f in frames) and time.monotonic() < deadline:
    time.sleep(0.01)
warm = hermes_android._warm
time.sleep(0.5)
waiting = list(calls)
alive = warm.is_alive()
reply = hermes_android.call("cron_warm_delivery_targets", "")
done.wait(10)
told = list(calls)
hermes_android.stop()
report({"waiting": waiting, "alive": alive, "reply": reply, "told": told, "joined": not warm.is_alive()})
"""
    seen = _probe(tmp_path, body)

    assert seen["waiting"] == [], "the warm ran before the app's startup calls were done"
    assert seen["alive"] is True
    assert seen["reply"] == "{}"
    assert seen["told"] == [1], "the signal did not start the warm"
    assert seen["joined"] is True


def test_a_stop_before_the_signal_warms_nothing_and_returns(tmp_path):
    body = _RECORDED_WARM + """
hermes_android.start({"role": "chat"}, Native())
warm = hermes_android._warm
began = time.monotonic()
hermes_android.stop()
report({"calls": calls, "alive": warm.is_alive(), "elapsed": time.monotonic() - began})
"""
    seen = _probe(tmp_path, body)

    assert seen["calls"] == [], "stop() ran the warm the app never asked for"
    assert seen["alive"] is False
    assert seen["elapsed"] < 5, "stop() waited out its join bound"


def test_the_signal_is_a_no_op_without_a_waiting_warm():
    # The :gateway role starts no warm, and a signal after stop() finds none.
    assert hermes_android._warm_go is None
    assert hermes_android.cron_warm_delivery_targets("") == "{}"


def test_the_gateway_process_warms_nothing(tmp_path):
    body = """
hermes_android.prepare = lambda paths: None
hermes_android.start({"role": "gateway"}, Native())
try:
    report([hermes_android._warm is None, "cron.scheduler_delivery" in sys.modules, ADAPTER in sys.modules])
finally:
    hermes_android.stop()
"""
    assert _probe(tmp_path, body) == [True, False, False]


def test_the_released_warm_fills_the_skills_listing_cache(tmp_path):
    # The Skills page's first skills_list read every SKILL.md (cold 764-957 ms on the phone, M11) on hermes-python,
    # the thread that carries the chat's frames (/architect 20261115T185930Z #3): the told warm scans them beside it.
    body = _RECORDED_WARM + """
hermes_android.start({"role": "chat"}, Native())
hermes_android.submit(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "ping"}))
deadline = time.monotonic() + 30
while not any(f.get("id") == 1 for f in frames) and time.monotonic() < deadline:
    time.sleep(0.01)
warm = hermes_android._warm
time.sleep(0.5)
early = "tools.skills_tool" in sys.modules
hermes_android.call("cron_warm_delivery_targets", "")
warm.join(60)
joined = not warm.is_alive()
loaded = "tools.skills_tool" in sys.modules
import tools.skills_tool as st
key = ("with_disabled", False)  # _find_all_skills(skip_disabled=True)'s entry, the one skills_list reads
entry = st._SKILLS_CACHE.get(key)
listed = json.loads(hermes_android.call("skills_list", ""))["skills"]
served = entry is not None and st._SKILLS_CACHE[key][1] == entry[1]  # a re-scan stamps a new time
hermes_android.stop()
report({"early": early, "joined": joined, "loaded": loaded, "filled": entry is not None,
        "count": len(entry[2]) if entry is not None else 0, "listed": len(listed), "served": served})
"""
    seen = _probe(tmp_path, body)

    assert seen["early"] is False, "the skills scan ran before the app's startup calls were done"
    assert seen["joined"] is True
    assert seen["loaded"] is True, "tools.skills_tool is not in sys.modules once the warm ran"
    assert seen["filled"] is True, "the warm left skills_list's cache entry empty"
    assert seen["count"] > 0  # start() seeds the bundled skills into the probe's home
    assert seen["listed"] == seen["count"]
    assert seen["served"] is True, "skills_list re-scanned instead of reading the warm's scan"


def test_a_stop_before_the_signal_scans_no_skills(tmp_path):
    body = _RECORDED_WARM + """
hermes_android.start({"role": "chat"}, Native())
warm = hermes_android._warm
hermes_android.stop()
report(["tools.skills_tool" in sys.modules, warm.is_alive()])
"""
    assert _probe(tmp_path, body) == [False, False], "stop() ran the skills scan the app never asked for"


def test_the_skills_scan_runs_on_the_warms_thread_as_skills_list_asks(tmp_path):
    body = _RECORDED_WARM + """
hermes_android.start({"role": "chat"}, Native())
import tools.skills_tool as st  # this test proves the thread and the argument, not the import
records = []
scan = st._find_all_skills
def recorded(*args, **kwargs):
    records.append([threading.current_thread().name, kwargs])
    return scan(*args, **kwargs)
st._find_all_skills = recorded
reply = hermes_android.call("cron_warm_delivery_targets", "")
hermes_android._warm.join(60)
hermes_android.stop()
report({"reply": reply, "records": records})
"""
    seen = _probe(tmp_path, body)

    assert seen["reply"] == "{}"
    assert seen["records"] == [["hermes-prewarm", {"skip_disabled": True}]], (
        "the skills scan did not run once, on hermes-prewarm, with skills_list's own argument")


def test_a_failed_skills_scan_is_quiet(monkeypatch, caplog):
    def raising(**_kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr("tools.skills_tool._find_all_skills", raising)
    caplog.set_level(logging.DEBUG, logger="hermes_android")

    assert hermes_android._load_skills() is None
    assert "warming the skills listing failed" in caplog.text


def test_a_session_closed_while_its_agent_builds_prints_no_frame(tmp_path):
    # The phone's path end to end: a chat closes a session whose deferred agent build is still running, and the
    # build then fails for lack of a provider. The record is gone, so write_json would fall the error frame through
    # to the core's stdout, which on the phone is logcat python.stdout.
    body = """
import threading, time
hermes_android.prepare = lambda paths: None  # host probe: keep this interpreter's paths
hermes_android.start({"role": "chat"}, Native())
from agent.auxiliary_unavailable import ProviderNotConfiguredError
from tui_gateway import server
from tui_gateway.contracts import registry
registry.STRICT = False  # the phone's mode: the child inherits the suite's marker, and a strict ErrorPayload check
# would raise on the error's provider_not_configured code before write_json routed it
building, closed = threading.Event(), threading.Event()
def fails_once_closed(*_a, **_kw):  # the phone's no-provider build, held until the session is closed
    building.set()
    closed.wait(30)
    raise ProviderNotConfiguredError("Hermes is not connected to any AI provider yet.")
server._make_agent = fails_once_closed
def ask(rid, method, params):
    hermes_android.submit(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}))
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if (reply := next((f for f in frames if f.get("id") == rid), None)) is not None:
            return reply
        time.sleep(0.01)
    raise TimeoutError(method)
sid = ask(1, "session.create", {})["result"]["session_id"]
assert building.wait(30), "the deferred build never started"
build = server._sessions[sid]["_agent_build_thread"]
closed_reply = ask(2, "session.close", {"session_id": sid})
closed.set()
build.join(30)
hermes_android.stop()  # it releases the warm that was never told to go, so it loads nothing
report({"closed": closed_reply.get("result", {}).get("closed"), "joined": not build.is_alive(),
        "host_errors": [f for f in frames if f.get("method") == "event" and f["params"].get("type") == "error"]})
"""
    run = _probe_run(tmp_path, body)
    lines = run.stdout.strip().splitlines()

    assert len(lines) == 1, f"the closed session's error frame was printed to the core's stdout: {lines[:-1]}"
    seen = json.loads(lines[-1])
    assert seen["closed"] is True
    assert seen["joined"] is True
    assert seen["host_errors"] == []


def test_stop_detaches_the_host_from_the_gateway(paths, isolated_home, monkeypatch):
    from tui_gateway import server

    frames: list[str] = []
    monkeypatch.setattr(hermes_android, "prepare", lambda paths: None)

    class Native:
        def secrets(self) -> str:
            return "{}"

        def emit(self, line: str, /) -> bool:
            frames.append(line)
            return True

    hermes_android.start(paths, Native())
    hermes_android.stop()
    frames.clear()
    server._broadcast_global_event("skin.changed", {"probe": True})

    assert frames == []


def test_runtime_facts_report_what_the_app_shows_about_its_interpreter(paths):
    hermes_android.prepare(paths)

    facts = json.loads(hermes_android.runtime_facts(""))

    import sqlite3

    from hermes_cli import __release_date__
    assert facts == {"python": sys.version.split()[0], "sqlite": sqlite3.sqlite_version, "fts5": True, "executable": "",
                     "core_release": __release_date__}


def test_the_core_release_is_the_cores_own_date_where_its_version_reads_unknown(paths, monkeypatch):
    # The phone's conditions: the payload carries no install stamp and no .git, so get_version_info()
    # answers "unknown" there; the core's release date is the version the app can name (F13, DC-M19).
    hermes_android.prepare(paths)
    from hermes_cli import __release_date__, version_info

    monkeypatch.setattr(version_info, "_stamp_version_info", lambda: None)
    monkeypatch.setattr(version_info, "_resolve_repo_dir", lambda: None)
    version_info._reset_version_info_cache()
    try:
        assert version_info.get_version_info().base_version == "unknown"

        facts = json.loads(hermes_android.call("runtime_facts", ""))  # through call: the contract check applies

        assert facts["core_release"] == __release_date__
        assert re.fullmatch(r"\d{4}\.\d{1,2}\.\d{1,2}", facts["core_release"])
        assert "unknown" not in facts.values()
    finally:
        version_info._reset_version_info_cache()
