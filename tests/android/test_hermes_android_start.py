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
