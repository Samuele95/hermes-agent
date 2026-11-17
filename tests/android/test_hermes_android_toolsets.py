"""The toolsets whose keys are missing, through the app's own bridge call (batch-4 F2, DC-P25; row capabilities-10,
merged protocol-35).

``toolsets.list`` (the core's method) has no configured flag, so ``toolsets_keys`` answers the names
``GET /api/tools/toolsets`` reports unconfigured: ``_toolset_has_keys`` over every configurable toolset, with the
subscription features read once. It checks presence only: no value is ever read back or answered.
"""

from __future__ import annotations

import importlib
import json
import socket

import pytest

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest

CANARY = "sk-or-canary-toolsets-0123456789"


def _keys() -> dict:
    return json.loads(hermes_android.call("toolsets_keys", ""))


def _oracle() -> list[str]:
    from hermes_cli.config import load_config
    from hermes_cli.tools_config import _get_effective_configurable_toolsets, _toolset_has_keys

    config = load_config()
    return [name for name, _, _ in _get_effective_configurable_toolsets() if not _toolset_has_keys(name, config)]


@pytest.fixture
def offline(monkeypatch):
    def refuse(self, *args, **kwargs):
        raise OSError("the network is blocked in this test")

    monkeypatch.setattr(socket.socket, "connect", refuse)
    monkeypatch.setattr(socket.socket, "connect_ex", refuse)


def test_the_names_are_the_cores_unconfigured_toolsets_in_its_order(offline):
    reply = _keys()

    assert set(reply) == {"needs_keys", "switchable"}
    assert reply["needs_keys"] == _oracle()
    assert reply["needs_keys"], "the hermetic keyless home has a toolset that needs a key"


def test_a_key_set_takes_its_toolset_out_and_its_value_is_never_answered(monkeypatch, offline):
    assert "vision" in _keys()["needs_keys"]

    monkeypatch.setenv("OPENROUTER_API_KEY", CANARY)  # TOOLSET_ENV_REQUIREMENTS["vision"]

    answer = hermes_android.call("toolsets_keys", "")
    assert "vision" not in json.loads(answer)["needs_keys"]
    assert json.loads(answer)["needs_keys"] == _oracle()
    assert CANARY not in answer


def _rpc(name: str, params: dict) -> dict:
    """The core's method [name] called as its gateway does (``tui_gateway.server._methods``), answering its result."""
    from tui_gateway import server

    response = server._methods[name](1, params)
    assert "error" not in response, response
    return response["result"]


def test_switchable_is_the_cli_checklist_with_its_gui_labels_and_the_core_switches_each(offline):
    from hermes_cli.tools_config import (
        _CONFIG_ONLY_TOOLSETS, _get_effective_configurable_toolsets, _toolset_allowed_for_platform, gui_toolset_label)

    switchable = _keys()["switchable"]

    checklist = [(n, label) for n, label, _ in _get_effective_configurable_toolsets()
                 if _toolset_allowed_for_platform(n, "cli") and n not in _CONFIG_ONLY_TOOLSETS]
    assert [row["name"] for row in switchable] == [n for n, _ in checklist]
    assert [row["label"] for row in switchable] == [gui_toolset_label(label) for _, label in checklist]
    assert {"name": "web", "label": "Web Search & Scraping"} in switchable
    assert all(set(row) == {"name", "label"} for row in switchable)
    names = [row["name"] for row in switchable]
    assert not {"stt", "discord", "discord_admin"} & set(names)
    assert set(names) <= {row["name"] for row in _rpc("toolsets.list", {})["toolsets"]}
    assert _rpc("tools.configure", {"action": "enable", "names": names})["unknown"] == []
    assert _rpc("tools.configure", {"action": "disable", "names": ["hermes-telegram"]})["unknown"] == ["hermes-telegram"]


def test_a_sessionless_switch_leaves_a_live_chat_alone_and_new_chats_take_it(monkeypatch, offline):
    from tui_gateway import server

    resets = []
    monkeypatch.setattr(server, "_reset_session_agent", lambda sid, session: resets.append(sid) or {})
    history = [{"role": "user", "content": "hi"}]
    monkeypatch.setitem(server._sessions, "chat", {"history": history})

    answer = _rpc("tools.configure", {"action": "disable", "names": ["browser"]})

    assert answer["reset"] is False and answer["info"] is None
    assert "browser" not in answer["enabled_toolsets"]
    assert resets == []
    assert server._sessions["chat"]["history"] == [{"role": "user", "content": "hi"}]
    rows = {row["name"]: row["enabled"] for row in _rpc("toolsets.list", {})["toolsets"]}
    assert rows["browser"] is False

    # With the chat's id the core resets that chat's agent (and empties its history): why the app never sends it (DC-P24).
    answer = _rpc("tools.configure", {"action": "enable", "names": ["browser"], "session_id": "chat"})

    assert answer["reset"] is True
    assert resets == ["chat"]
