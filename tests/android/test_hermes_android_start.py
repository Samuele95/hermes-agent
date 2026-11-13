"""What the app's embedded interpreter runs before the core.

The interpreter lives inside the app process: ``sys.executable`` would otherwise name a binary that
is not Python (``app_process64``), and every subprocess spawner in the core would exec it. The
runtime is a directory the app extracted, so the core and the wheels are put on ``sys.path`` here.
"""

from __future__ import annotations

import importlib
import json
import os
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
    return {"runtime": str(runtime), "home": str(tmp_path / "files"), "hermes_home": str(tmp_path / "files/.hermes"),
            "tmp": str(tmp_path / "cache")}


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


def _probe(tmp_path, body: str):
    """Runs ``body`` after :data:`_PROBE_START` in a fresh interpreter (pytest's own already holds the core's
    modules) against a home under ``tmp_path``; ``body`` reports one JSON line."""
    import subprocess

    from tests.android.conftest import ANDROID_PYTHON

    run = subprocess.run([sys.executable, "-I", "-c", _PROBE_START + body, str(REPOSITORY), str(ANDROID_PYTHON)],
                         capture_output=True, text=True, encoding="utf-8", timeout=300, check=False,
                         env={**os.environ, "HERMES_HOME": str(tmp_path / ".hermes"), "HOME": str(tmp_path)})
    assert run.returncode == 0, run.stderr[-3000:]
    return json.loads(run.stdout.strip().splitlines()[-1])


def test_prepare_imports_no_core_module(tmp_path):
    # prepare() runs before the keystore source is registered and before the core's first env load, and in the
    # :gateway process too: no core import (nor the delivery-targets warm) belongs in it. The repository root is on
    # the probe's path, so a prepare() that imported the core would succeed and show here.
    runtime = tmp_path / "runtime"
    (runtime / "site-packages").mkdir(parents=True)
    (runtime / "app").mkdir()
    paths = {"runtime": str(runtime), "home": str(tmp_path / "files"), "hermes_home": str(tmp_path / "files/.hermes"),
             "tmp": str(tmp_path / "cache")}
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
    assert facts == {"python": sys.version.split()[0], "sqlite": sqlite3.sqlite_version, "fts5": True, "executable": ""}
