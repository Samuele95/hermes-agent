"""A deferred agent build that fails tells only a session that is still there.

``write_json`` routes an event frame to its session's transport; a session id it no longer holds falls through to
the module's stdio transport, which outside the stdio TUI is a log sink (the Android app's logcat, Desktop's
desktop.log). A build that fails after its session was closed or replaced has no one to tell.
"""

import io
import json
import threading

import pytest

from tui_gateway import server
from tui_gateway.embedded import EmbeddedTransport


class Host:
    def __init__(self):
        self.frames: list[dict] = []

    def emit(self, line: str) -> bool:
        self.frames.append(json.loads(line))
        return True

    def errors(self) -> list[dict]:
        return [f["params"] for f in self.frames if f.get("method") == "event" and f["params"]["type"] == "error"]


@pytest.fixture
def stdout(monkeypatch):
    buf = io.StringIO()
    monkeypatch.setattr(server, "_real_stdout", buf)
    return buf


@pytest.fixture
def held_build(monkeypatch):
    """A registered session whose deferred build fails for lack of a provider once released, as on a phone with
    no provider set up. Returns ``(session, host, release)``."""
    building, go = threading.Event(), threading.Event()

    def _no_provider(*_a, **_kw):
        building.set()
        go.wait(10)
        # A plain error: the provider_not_configured ``code`` is outside ErrorPayload's contract, which the test
        # suite enforces (the phone only logs it), so ProviderNotConfiguredError would fail the emit itself.
        raise RuntimeError("Hermes is not connected to any AI provider yet.")

    monkeypatch.setattr(server, "_make_agent", _no_provider)
    monkeypatch.setattr(server, "_deferred_build_agent_kwargs", lambda *a, **kw: {})
    monkeypatch.setattr(server, "_await_resume_history", lambda *a, **kw: True)
    monkeypatch.setattr(server, "_set_session_context", lambda *a, **kw: None)
    monkeypatch.setattr(server, "_clear_session_context", lambda *a, **kw: None)
    monkeypatch.setattr(server, "_session_cwd", lambda *a, **kw: None)
    monkeypatch.setattr(server, "_bind_build_profile_scopes", lambda *a, **kw: None)
    monkeypatch.setattr(server, "_finish_agent_build", lambda *a, **kw: None)
    host = Host()
    session = {"session_key": "key", "agent_ready": threading.Event(), "transport": EmbeddedTransport(host.emit)}
    monkeypatch.setitem(server._sessions, "sid", session)
    server._start_agent_build("sid", session)
    assert building.wait(10), "the build never reached _make_agent"

    def release():
        go.set()
        session["_agent_build_thread"].join(10)
        assert session["agent_ready"].is_set()

    return session, host, release


def test_a_build_failing_after_its_session_closed_writes_nothing_to_stdout(held_build, stdout):
    session, host, release = held_build
    server._sessions.pop("sid")  # what session.close's _pop_session_by_id does while the build runs

    release()

    assert stdout.getvalue() == "", "the closed session's error frame fell through to stdout"
    assert host.errors() == []
    assert "not connected to any AI provider" in session["agent_error"]  # the cause is kept on the record


def test_a_build_failing_while_its_session_is_open_still_tells_its_client(held_build, stdout):
    # The app settles a left session on this frame before closing it (ChatSession.kt LeftSessions).
    _session, host, release = held_build

    release()

    assert [e["session_id"] for e in host.errors()] == ["sid"]
    assert "not connected to any AI provider" in host.errors()[0]["payload"]["message"]
    assert stdout.getvalue() == ""


def test_a_replaced_session_does_not_receive_the_old_builds_error(held_build, stdout, monkeypatch):
    _session, old_host, release = held_build
    new_host = Host()
    monkeypatch.setitem(server._sessions, "sid", {"session_key": "key", "transport": EmbeddedTransport(new_host.emit)})

    release()

    assert new_host.errors() == [] and old_host.errors() == []
    assert stdout.getvalue() == ""
