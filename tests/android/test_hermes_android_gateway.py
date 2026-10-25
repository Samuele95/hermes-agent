"""The always-on messaging gateway in the app's own ``:gateway`` process (D12).

That process starts its own interpreter with the role ``gateway``: the same paths and keystore secrets
as the chat's, no chat transport. The gateway runs on a thread of it as the desktop runs it in a
service: a planned stop drains it, ``/restart`` (exit 75, the external-supervisor contract) runs it
again in place, any other end is reported to the app, which owns what happens next.
"""

from __future__ import annotations

import importlib
import json
import os
import threading
import time
from itertools import pairwise
from pathlib import Path

import pytest

from tests.waiting import wait_until

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest
gateway_host = importlib.import_module("hermes_android.gateway_host")


class Events:
    """What the host reports to the app, one JSON object per line."""

    def __init__(self):
        self.seen: list[dict] = []
        self._changed = threading.Condition()

    def emit(self, line: str, /) -> bool:
        with self._changed:
            self.seen.append(json.loads(line))
            self._changed.notify_all()
        return True

    def states(self) -> list[str]:
        return [e["gateway"] for e in self.seen]

    def wait_for(self, state: str, timeout: float = 30.0) -> dict:
        deadline = time.monotonic() + timeout
        with self._changed:
            while True:
                found = next((e for e in self.seen if e["gateway"] == state), None)
                if found is not None:
                    return found
                left = deadline - time.monotonic()
                assert left > 0, f"no {state!r} within {timeout} s: {self.states()}"
                self._changed.wait(left)


@pytest.fixture
def gateway_home(monkeypatch):
    # The hermetic conftest gives every test its own HERMES_HOME.
    monkeypatch.delenv("HERMES_GATEWAY_EXTERNAL_SUPERVISOR", raising=False)
    home = Path(os.environ["HERMES_HOME"])
    home.mkdir(parents=True, exist_ok=True)
    return home


def test_a_real_gateway_runs_until_a_planned_stop_drains_it(gateway_home):
    from gateway.status import read_runtime_status

    events = Events()
    host = gateway_host.GatewayHost(events.emit)

    host.start()
    events.wait_for("running")
    deadline = time.monotonic() + 60
    while (read_runtime_status() or {}).get("gateway_state") != "running":
        assert time.monotonic() < deadline, read_runtime_status()
        time.sleep(0.2)
    host.stop()

    assert events.wait_for("stopped", timeout=60) == {"gateway": "stopped", "reason": None}
    deadline = time.monotonic() + 10  # "stopped" is the thread's last word: it ends right after
    while host.alive():
        assert time.monotonic() < deadline, "the gateway thread outlived its stop"
        time.sleep(0.05)
    assert (read_runtime_status() or {}).get("gateway_state") == "stopped"
    # The app's process is the supervisor: a restart must come back as exit 75, never as a spawned CLI.
    assert os.environ["HERMES_GATEWAY_EXTERNAL_SUPERVISOR"] == "1"


def _scripted(*outcomes, stop: threading.Event | None = None):
    """A stand-in for one gateway run per call: an exit code raises SystemExit, a bool is returned."""
    remaining = list(outcomes)
    calls: list[float] = []

    def run() -> bool:
        calls.append(time.monotonic())
        outcome = remaining.pop(0) if remaining else True
        if outcome == "until-stopped":
            assert stop is not None
            stop.wait(30)
            return True
        if isinstance(outcome, int) and not isinstance(outcome, bool):
            raise SystemExit(outcome)
        return outcome

    return run, calls


def test_a_restart_runs_the_gateway_again_in_place(gateway_home):
    events = Events()
    run, calls = _scripted(75, True)
    host = gateway_host.GatewayHost(events.emit, run_gateway=run, quick_run_s=0)

    host.start()

    events.wait_for("stopped")
    assert events.states() == ["running", "restarting", "running", "stopped"]
    assert len(calls) == 2


@pytest.mark.parametrize("outcome, reported", [(False, {"gateway": "failed", "reason": "the gateway did not start"}),
                                               (78, {"gateway": "failed", "reason": "the gateway exited with code 78"}),
                                               (1, {"gateway": "failed", "reason": "the gateway exited with code 1"})])
def test_any_other_end_is_reported_and_not_retried(gateway_home, outcome, reported):
    events = Events()
    run, calls = _scripted(outcome)
    host = gateway_host.GatewayHost(events.emit, run_gateway=run)

    host.start()

    assert events.wait_for("failed") == reported
    assert events.states() == ["running", "failed"]
    assert len(calls) == 1


def test_a_crashing_gateway_is_reported_with_its_error(gateway_home):
    events = Events()

    def run() -> bool:
        raise RuntimeError("adapter exploded")

    gateway_host.GatewayHost(events.emit, run_gateway=run).start()

    assert events.wait_for("failed") == {"gateway": "failed", "reason": "RuntimeError: adapter exploded"}


def test_a_failure_reason_carrying_a_secret_is_redacted(gateway_home):
    # The reason reaches the app's record, its screen and a notification: the platform's error text may hold a token.
    events = Events()
    token = "123456789:AAH-telegram-bot-token-in-an-error"

    def run() -> bool:
        raise RuntimeError(f"telegram rejected https://api.telegram.org/bot{token}/getMe")

    gateway_host.GatewayHost(events.emit, run_gateway=run).start()

    reason = events.wait_for("failed")["reason"]
    assert reason.startswith("RuntimeError: telegram rejected") and token not in reason


def test_quick_restarts_back_off_and_a_stop_ends_the_wait(gateway_home):
    # Exit 75 at once, again and again (e.g. a lock another holder keeps): no hot loop.
    events = Events()
    run, calls = _scripted(75, 75, 75, 75, 75, 75)
    host = gateway_host.GatewayHost(events.emit, run_gateway=run, quick_run_s=30, first_backoff_s=0.2, max_backoff_s=5)

    host.start()
    wait_until(lambda: len(calls) == 3, timeout=10)  # then it waits 0.8 s before a 4th run
    host.stop()

    events.wait_for("stopped", timeout=0.5)  # the stop ended the wait, it did not sit it out
    first, second = (b - a for a, b in pairwise(calls))
    assert len(calls) == 3
    assert 0.2 <= first < second and second >= 0.4, calls
    assert not host.alive()


def test_start_is_idempotent_while_the_gateway_runs(gateway_home):
    events = Events()
    stop = threading.Event()
    run, calls = _scripted("until-stopped", stop=stop)
    host = gateway_host.GatewayHost(events.emit, run_gateway=run)

    host.start()
    events.wait_for("running")
    host.start()
    stop.set()

    events.wait_for("stopped")
    assert len(calls) == 1


@pytest.fixture
def isolated_registry():
    yield
    from agent.secret_sources import registry
    registry._reset_registry_for_tests()


def test_the_gateway_role_starts_no_chat_transport_and_reads_the_keystore(gateway_home, isolated_registry, monkeypatch):
    monkeypatch.setattr(hermes_android, "prepare", lambda paths: None)  # host test: keep this interpreter's paths
    emitted: list[str] = []

    class Native:
        def secrets(self) -> str:
            return "{}"

        def emit(self, line: str, /) -> bool:
            emitted.append(line)
            return True

    hermes_android.start({"role": "gateway", "hermes_home": str(gateway_home)}, Native())
    try:
        from agent.secret_sources import registry

        assert registry.get_source("android_keystore") is not None
        assert emitted == []  # a chat transport would have announced itself (gateway.ready)
        assert hermes_android.gateway_state("") == json.dumps({"gateway": "stopped"})
    finally:
        hermes_android.stop()
