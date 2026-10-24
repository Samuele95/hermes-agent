"""The app's bridge channel into its Python half is declared (D26).

``hermes_android/bridge_contract.json`` names every call the Kotlin app may make, the request object each
takes and the reply each answers; ``hermes_android.call`` is the only way in, and it holds both sides to the
declaration. A call the contract does not name (the lifecycle functions ``start``/``stop`` included) is
refused, and so is a request or a reply that does not match: a drift between the two halves fails where it
happens instead of reaching the user as a misread reply.
"""

from __future__ import annotations

import importlib
import inspect
import json
from pathlib import Path

import pytest

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest

CONTRACT = json.loads((Path(inspect.getfile(hermes_android)).parent / "bridge_contract.json").read_text(encoding="utf-8"))
LIFECYCLE = {"prepare", "start", "submit", "stop", "call"}  # the C glue's own entry points


def _record(monkeypatch, name, reply):
    seen = []

    def fake(arg):
        seen.append(arg)
        return json.dumps(reply)

    monkeypatch.setattr(hermes_android, name, fake)
    return seen


def test_every_declared_call_exists_and_every_bridge_function_is_declared():
    defined = {name for name, fn in inspect.getmembers(hermes_android, inspect.isfunction)
               if fn.__module__ == "hermes_android" and not name.startswith("_")}

    assert set(CONTRACT["calls"]) <= defined
    assert defined - LIFECYCLE == set(CONTRACT["calls"])


def test_the_contract_uses_only_known_types():
    known = {"string", "integer", "boolean", "string?"} | set(CONTRACT["enums"])
    for name, call in CONTRACT["calls"].items():
        for field, kind in {**call["params"], **call["reply"]}.items():
            assert kind in known, (name, field, kind)


def test_a_call_the_contract_does_not_name_is_refused(monkeypatch):
    started = _record(monkeypatch, "start", {})

    for name in ("start", "stop", "prepare", "_seed_bundled_skills", "no_such_call"):
        with pytest.raises(LookupError):
            hermes_android.call(name, "{}")
    assert started == []


@pytest.mark.parametrize("request_", [
    {"host": "example.org"},                              # a param missing
    {"host": "example.org", "port": 22, "user": "me"},   # a param the call does not take
    {"host": "example.org", "port": "22"},                # a number sent as text
    {"host": "example.org", "port": True},                # a boolean is no integer
    {"host": None, "port": 22},                           # null where a string is declared
])
def test_a_request_that_does_not_match_is_refused_before_the_call(monkeypatch, request_):
    seen = _record(monkeypatch, "ssh_host_key", {"key": "k", "fingerprint": "f", "pinned": "no"})

    with pytest.raises(ValueError):
        hermes_android.call("ssh_host_key", json.dumps(request_))
    assert seen == []


def test_a_matching_request_reaches_the_call_unchanged(monkeypatch):
    seen = _record(monkeypatch, "ssh_host_key", {"key": "k", "fingerprint": "f", "pinned": "changed"})
    request_ = json.dumps({"host": "example.org", "port": 2222})

    assert json.loads(hermes_android.call("ssh_host_key", request_)) == {"key": "k", "fingerprint": "f", "pinned": "changed"}
    assert seen == [request_]


@pytest.mark.parametrize("reply", [
    {"key": "k", "fingerprint": "f"},                         # a field missing
    {"key": "k", "fingerprint": "f", "pinned": "maybe"},      # a value the enum does not have
    {"key": "k", "fingerprint": 7, "pinned": "no"},           # a wrong type
    {"key": "k", "fingerprint": "f", "pinned": "no", "x": 1},  # a field the app was not told of
])
def test_a_reply_that_does_not_match_is_refused(monkeypatch, reply):
    _record(monkeypatch, "ssh_host_key", reply)

    with pytest.raises(TypeError):
        hermes_android.call("ssh_host_key", json.dumps({"host": "example.org", "port": 22}))


def test_an_error_reply_passes_for_every_call(monkeypatch):
    _record(monkeypatch, "ssh_host_key", {"error": "Could not reach example.org:22"})

    assert json.loads(hermes_android.call("ssh_host_key", json.dumps({"host": "example.org", "port": 22}))) == \
        {"error": "Could not reach example.org:22"}


def test_a_nullable_field_takes_null(monkeypatch):
    _record(monkeypatch, "telegram_status", {"token": False, "allowed_user": None, "sdk": True})

    assert json.loads(hermes_android.call("telegram_status", "{}"))["allowed_user"] is None


def test_real_calls_answer_their_declared_replies():
    # An empty argument is an empty request, as the app sends for calls without params.
    facts = json.loads(hermes_android.call("runtime_facts", ""))
    assert set(facts) == set(CONTRACT["calls"]["runtime_facts"]["reply"])

    assert json.loads(hermes_android.call("gateway_state", "{}")) == {"gateway": "stopped"}
    assert set(json.loads(hermes_android.call("telegram_status", "{}"))) == {"token", "allowed_user", "sdk"}
