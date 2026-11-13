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
# Functions of the package that are no call: the Python half's own lines (call() refuses them like any undeclared name).
NOT_CALLS = LIFECYCLE | {"event_line"}


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
    assert defined - NOT_CALLS == set(CONTRACT["calls"])


def test_only_the_network_bound_calls_are_declared_blocking():
    # The app runs these apart from the core's frames (BridgeThreads): a slow probe never queues Stop or an answer.
    # The SSH look/login and the Telegram check wait up to 15-20 s on a host or on Telegram.
    assert {name for name, c in CONTRACT["calls"].items() if c.get("blocking")} == {
        "probe_endpoint", "discover_endpoints", "ssh_host_key", "ssh_check_login", "telegram_check"}
    assert all(isinstance(c["blocking"], bool) for c in CONTRACT["calls"].values() if "blocking" in c)


def _known(kind: str) -> bool:
    if kind.startswith("[") and kind.endswith("]"):
        return _known(kind[1:-1])
    return kind.removesuffix("?") in {"string", "integer", "boolean"} | set(CONTRACT["enums"]) | set(CONTRACT.get("records", {}))


def test_the_contract_uses_only_known_types():
    for name, call in CONTRACT["calls"].items():
        for field, kind in {**call["params"], **call["reply"]}.items():
            assert _known(kind), (name, field, kind)
    for record, fields in CONTRACT.get("records", {}).items():
        for field, kind in fields.items():
            assert _known(kind), (record, field, kind)


def test_a_list_holds_every_item_to_its_type(monkeypatch):
    monkeypatch.setitem(hermes_android._CONTRACT["records"], "Item", {"id": "string", "n": "integer"})
    assert hermes_android._conforms("[string]", ["a", "b"])
    assert hermes_android._conforms("[string]", [])
    assert not hermes_android._conforms("[string]", ["a", 1])
    assert not hermes_android._conforms("[string]", "a")
    assert hermes_android._conforms("[Item]", [{"id": "x", "n": 1}])
    assert not hermes_android._conforms("[Item]", [{"id": "x"}])  # a missing field
    assert not hermes_android._conforms("[Item]", [{"id": "x", "n": 1, "extra": 2}])  # an undeclared field


def test_a_nullable_type_is_its_type_or_null(monkeypatch):
    monkeypatch.setitem(hermes_android._CONTRACT["enums"], "S", ["a"])
    monkeypatch.setitem(hermes_android._CONTRACT["records"], "Item", {"id": "string"})
    assert hermes_android._conforms("S?", None)
    assert hermes_android._conforms("S?", "a")
    assert not hermes_android._conforms("S?", "b")
    assert hermes_android._conforms("Item?", None)
    assert hermes_android._conforms("Item?", {"id": "x"})
    assert not hermes_android._conforms("Item?", {})
    assert hermes_android._conforms("string?", None)
    assert hermes_android._conforms("string?", "x")
    assert not hermes_android._conforms("string?", 1)


def test_a_record_is_an_object_with_exactly_its_fields(monkeypatch):
    monkeypatch.setitem(hermes_android._CONTRACT["records"], "Item", {"id": "string", "tags": "[string]"})
    assert hermes_android._conforms("Item", {"id": "x", "tags": ["a"]})
    assert not hermes_android._conforms("Item", {"id": "x", "tags": "a"})
    assert not hermes_android._conforms("Item", ["x"])


def test_a_call_the_contract_does_not_name_is_refused(monkeypatch):
    started = _record(monkeypatch, "start", {})

    for name in ("start", "stop", "prepare", "submit", "no_such_call"):
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
    _record(monkeypatch, "telegram_status", {"token": False, "allowed_user": None, "sdk": True, "enabled": True, "live": None})

    assert json.loads(hermes_android.call("telegram_status", "{}"))["allowed_user"] is None


def test_real_calls_answer_their_declared_replies():
    # An empty argument is an empty request, as the app sends for calls without params.
    facts = json.loads(hermes_android.call("runtime_facts", ""))
    assert set(facts) == set(CONTRACT["calls"]["runtime_facts"]["reply"])

    assert json.loads(hermes_android.call("gateway_state", "{}")) == {"gateway": "stopped"}
    assert set(json.loads(hermes_android.call("telegram_status", "{}"))) == {"token", "allowed_user", "sdk", "enabled", "live"}


def test_an_event_line_is_held_to_its_declared_fields():
    assert json.loads(hermes_android.event_line("gateway_state", {"gateway": "failed", "reason": "boom"})) == \
        {"gateway": "failed", "reason": "boom"}
    for bad in ({"gateway": "exploded", "reason": None}, {"gateway": "running"}, {"gateway": "running", "reason": None, "x": 1}):
        with pytest.raises(TypeError):
            hermes_android.event_line("gateway_state", bad)
    with pytest.raises(LookupError):
        hermes_android.event_line("no_such_event", {})


def test_the_secret_names_are_the_ones_the_core_reads():
    from gateway.config import PLATFORM_TOKEN_ENV_NAMES, Platform
    from tools.environments import local_env_policy

    assert CONTRACT["secrets"]["telegram_bot_token"] == PLATFORM_TOKEN_ENV_NAMES[Platform.TELEGRAM]
    # The SSH identity is read by the asyncssh transport, and the core keeps it from every child process.
    assert CONTRACT["secrets"]["ssh_identity"] in local_env_policy._ALWAYS_STRIP_KEYS


def test_the_custom_endpoint_slot_rule_is_the_cores():
    # The app tells a custom endpoint's key slot by its fixed prefix and suffix, never by a copy of the id rule.
    from hermes_cli.config import custom_endpoint_key_env

    for identity in ["dgx", "2070", "host ollama", "", "a.b:11434"]:
        slot = custom_endpoint_key_env(identity)
        assert slot.startswith(CONTRACT["secrets"]["custom_endpoint_slot_prefix"]), slot
        assert slot.endswith(CONTRACT["secrets"]["custom_endpoint_slot_suffix"]), slot
