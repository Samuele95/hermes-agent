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

    assert set(reply) == {"needs_keys"}
    assert reply["needs_keys"] == _oracle()
    assert reply["needs_keys"], "the hermetic keyless home has a toolset that needs a key"


def test_a_key_set_takes_its_toolset_out_and_its_value_is_never_answered(monkeypatch, offline):
    assert "vision" in _keys()["needs_keys"]

    monkeypatch.setenv("OPENROUTER_API_KEY", CANARY)  # TOOLSET_ENV_REQUIREMENTS["vision"]

    answer = hermes_android.call("toolsets_keys", "")
    assert "vision" not in json.loads(answer)["needs_keys"]
    assert json.loads(answer)["needs_keys"] == _oracle()
    assert CANARY not in answer
