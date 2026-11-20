"""``skills.reload`` must not hold the thread that reads requests.

The rescan behind it (every skill dir + the plugin projection) takes about a
second on slow storage. Inline, it holds the reader: a stdio TUI's stdin loop,
a WS connection's read loop, an embedding host's one thread into the
interpreter. ``session.interrupt``, ``approval.respond`` and every other request
then wait behind it. Like its sibling ``skills.manage`` it belongs to
``_LONG_HANDLERS``: ``dispatch`` hands it to the RPC pool and answers the next
request at once, while the pool worker writes the reload's answer through the
request's own transport.

A pool thread must not change what the reload does: it still rescans under the
requesting session's home and cwd, and a failing rescan still answers its caller
with the method's code. These are invariants, not timings: the next request is
answered while the stubbed rescan is still blocked.
"""

from __future__ import annotations

import threading
import time

from agent import skill_commands
from tui_gateway import server
from tui_gateway.contracts.tools_mcp_plugins import SkillsReloadResult


class _RecordingTransport:
    """Collect every frame written for a request; tests wait on them by request id."""

    def __init__(self):
        self._lock = threading.Lock()
        self._changed = threading.Event()
        self.responses = {}
        self.order = []

    def write(self, response):
        with self._lock:
            self.responses[response.get("id")] = response
            self.order.append(response.get("id"))
            self._changed.set()
        return True

    def close(self):
        pass

    def wait_for(self, request_id, timeout=2.0):
        deadline = time.monotonic() + timeout
        while True:
            with self._lock:
                response = self.responses.get(request_id)
                if response is not None:
                    return response
                self._changed.clear()
            remaining = deadline - time.monotonic()
            assert remaining > 0, f"timed out waiting for response {request_id!r}"
            self._changed.wait(timeout=remaining)


def _send(transport, request_id, method, params=None):
    """Dispatch like a reader loop: an inline answer is written to the transport by the caller."""
    out = server.dispatch({"id": request_id, "method": method, "params": params or {}}, transport)
    if out is not None:
        transport.write(out)
    return out


def test_a_skills_reload_leaves_the_callers_thread_free_for_the_next_request(monkeypatch):
    started, release = threading.Event(), threading.Event()
    seen = {}

    def slow_rescan():
        seen["thread"] = threading.current_thread()
        started.set()
        release.wait(timeout=3)
        return {"added": [{"name": "fresh-skill"}], "removed": [], "unchanged": [], "total": 1, "commands": 1}

    monkeypatch.setattr(skill_commands, "reload_skills", slow_rescan)
    transport = _RecordingTransport()
    out = None
    try:
        out = server.dispatch({"id": "reload", "method": "skills.reload", "params": {}}, transport)
        assert started.wait(timeout=5)
        nxt = server.dispatch({"id": "next", "method": "gateway.capabilities", "params": {}}, transport)
        rescan_still_running = not release.is_set()

        assert out is None, f"skills.reload was answered inline on the caller's thread: {out!r}"
        assert "result" in nxt, nxt
        assert rescan_still_running, "the next request waited for the rescan to finish"
        assert seen["thread"] is not threading.current_thread()
        assert seen["thread"].name.startswith("tui-rpc"), seen["thread"].name
    finally:
        release.set()
        if out is None:
            reloaded = transport.wait_for("reload", timeout=5)
        else:
            reloaded = out

    result = reloaded["result"]
    assert "fresh-skill" in result["output"]
    assert "1 skill(s) available" in result["output"]
    SkillsReloadResult.model_validate(result)


def test_a_pooled_reload_rescans_under_the_requesting_sessions_home_and_cwd(tmp_path, monkeypatch):
    from agent import runtime_cwd
    import hermes_constants

    profile_home, repo = tmp_path / "profile", tmp_path / "repo"
    profile_home.mkdir()
    repo.mkdir()
    seen = {}

    def rescan():
        seen["home"] = hermes_constants.get_hermes_home_override()
        seen["cwd"] = runtime_cwd.scoped_session_cwd()
        return {"added": [], "removed": [], "unchanged": [], "total": 0, "commands": 0}

    monkeypatch.setattr(skill_commands, "reload_skills", rescan)
    server._sessions["reload-scope"] = {"profile_home": str(profile_home), "cwd": str(repo)}
    transport = _RecordingTransport()
    try:
        _send(transport, "reload", "skills.reload", {"session_id": "reload-scope"})
        answer = transport.wait_for("reload", timeout=5)
    finally:
        server._sessions.pop("reload-scope", None)

    assert "result" in answer, answer
    assert seen["home"] == str(profile_home)
    assert seen["cwd"] == str(repo)


def test_a_pooled_reload_that_fails_answers_its_caller_with_the_methods_code(monkeypatch):
    def broken_rescan():
        raise RuntimeError("scan exploded")

    monkeypatch.setattr(skill_commands, "reload_skills", broken_rescan)
    transport = _RecordingTransport()

    _send(transport, "reload", "skills.reload", {})
    answer = transport.wait_for("reload", timeout=5)

    assert answer["error"]["code"] == 5025, answer
    assert "scan exploded" in answer["error"]["message"]
