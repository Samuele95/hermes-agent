"""The app's config.yaml writes hold one lock from the load to the save.

Each of the app's writers (endpoint save/delete, the SSH terminal, the Telegram allowlist) loads the whole
file, edits it and saves it. ``hermes_android._config.mutate`` keeps that span exclusive across the app's
threads and across its chat and ``:gateway`` processes, by the flock the core's plugin-setting writer takes on
the sibling ``.config.yaml.lock``; it never computes on a config.yaml it cannot read, and writes nothing when
the edit changed nothing.
"""

from __future__ import annotations

import importlib
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

import pytest

from hermes_cli.config import load_config, read_user_config_raw
from tests.android.conftest import ANDROID_PYTHON
from tests.waiting import wait_until

_config = importlib.import_module("hermes_android._config")

REPO = Path(__file__).resolve().parents[2]


@pytest.fixture
def home(tmp_path, monkeypatch):
    path = tmp_path / "home"
    path.mkdir()
    monkeypatch.setenv("HERMES_HOME", str(path))
    return path


def add(name: str):
    def fn(cfg: dict) -> None:
        cfg.setdefault("providers", {})[name] = {"name": name, "base_url": "http://10.0.0.9/v1", "model": "m"}
    return fn


def test_two_threads_writing_at_once_keep_both_edits(home):
    a_in, b_in, release = threading.Event(), threading.Event(), threading.Event()

    def fn_a(cfg):
        a_in.set()
        release.wait(timeout=10)
        add("a")(cfg)

    def fn_b(cfg):
        b_in.set()
        add("b")(cfg)

    a = threading.Thread(target=_config.mutate, args=(fn_a,))
    a.start()
    assert a_in.wait(timeout=10)
    b = threading.Thread(target=_config.mutate, args=(fn_b,))
    b.start()
    try:
        assert not b_in.wait(timeout=1.0), "a second write entered while the first held the span"
    finally:
        release.set()
        a.join(timeout=10)
        b.join(timeout=10)

    assert {"a", "b"} <= set(load_config()["providers"])


CHILD = r"""
import sys, time
from pathlib import Path
sys.path.insert(0, sys.argv[1])
name, tmp = sys.argv[2], Path(sys.argv[3])
import hermes_cli.config
from hermes_android import _config

def fn(cfg):
    (tmp / f"{name}.in").touch()
    for _ in range(400):
        if (tmp / "release").exists():
            break
        time.sleep(0.025)
    cfg.setdefault("providers", {})[name] = {"name": name, "base_url": "http://10.0.0.9/v1", "model": "m"}

print("calling", flush=True)
_config.mutate(fn)
"""


@pytest.mark.platforms("posix")
def test_two_processes_writing_at_once_keep_both_edits(home, tmp_path):
    def child(name: str) -> subprocess.Popen:
        return subprocess.Popen([sys.executable, "-c", CHILD, str(ANDROID_PYTHON), name, str(tmp_path)], cwd=REPO,
                                env={**os.environ, "HERMES_HOME": str(home)}, stdout=subprocess.PIPE, text=True,
                                encoding="utf-8")

    a = child("a")
    b = None
    try:
        wait_until(lambda: (tmp_path / "a.in").exists(), timeout=60, what="the first child inside its write")
        b = child("b")
        assert b.stdout.readline().strip() == "calling"
        entered = False
        for _ in range(40):
            if (tmp_path / "b.in").exists():
                entered = True
                break
            time.sleep(0.025)
        assert not entered, "the second process entered while the first held the span"
    finally:
        (tmp_path / "release").touch()
        a.communicate(timeout=60)
        if b is not None:
            b.communicate(timeout=60)

    assert a.returncode == 0 and b.returncode == 0
    assert {"a", "b"} <= set(load_config()["providers"])


@pytest.mark.platforms("posix")
def test_a_write_waits_for_the_lock_the_cores_plugin_settings_take(home):
    import fcntl

    done = threading.Event()

    def write():
        _config.mutate(add("x"))
        done.set()

    # The file hermes_cli.plugins_state._locked_plugin_state(get_config_path()) locks.
    with open(home / ".config.yaml.lock", "a+b") as held:
        fcntl.flock(held.fileno(), fcntl.LOCK_EX)
        writer = threading.Thread(target=write)
        writer.start()
        try:
            assert not done.wait(timeout=1.0), "the write did not wait for the plugin-settings lock"
            assert "x" not in (read_user_config_raw().get("providers") or {})
        finally:
            fcntl.flock(held.fileno(), fcntl.LOCK_UN)
        assert done.wait(timeout=10)
        writer.join(timeout=10)

    assert "x" in load_config()["providers"]


def test_an_unreadable_config_is_refused_before_the_edit_runs(home):
    path = home / "config.yaml"
    path.write_text("model:\n  default: some/model\n", encoding="utf-8")
    load_config()  # the core now has a last good copy to fall back on
    path.write_text("model: [\n", encoding="utf-8")
    calls = []

    with pytest.raises(Exception):
        _config.mutate(lambda cfg: calls.append(cfg))

    assert calls == []


def test_an_edit_that_changes_nothing_writes_nothing(home):
    path = home / "config.yaml"
    path.write_text("providers:\n  x:\n    name: x\n    base_url: http://10.0.0.9/v1\n    model: m\n", encoding="utf-8")
    ino, mtime = path.stat().st_ino, path.stat().st_mtime_ns

    assert _config.mutate(lambda cfg: "same") == "same"

    assert (path.stat().st_ino, path.stat().st_mtime_ns) == (ino, mtime)
