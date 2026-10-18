"""The Android app's Kotlin protocol types are rendered from the published OpenRPC contract.

The app drives the core over tui_gateway JSON-RPC; its client covers the v1 surface the app uses
(``gen_kotlin_client.SURFACE``), generated from ``apps/shared/src/gateway-contract.openrpc.json``
the way the desktop's TypeScript is, so a contract change that the app would decode wrongly fails
here instead of on a phone. Regenerate with ``python -m scripts.android.gen_kotlin_client``.
"""

from __future__ import annotations

import json

import pytest

from scripts.android import gen_kotlin_client as gen


def test_the_committed_kotlin_is_current():
    contract = json.loads(gen.CONTRACT.read_text(encoding="utf-8-sig"))

    assert gen.OUT.read_text(encoding="utf-8-sig") == gen.render(contract), (
        "stale: run python -m scripts.android.gen_kotlin_client")


def _contract(schemas: dict, *, params: str = "Params", result: str = "Result") -> dict:
    ref = lambda name: {"$ref": f"#/components/schemas/{name}"}
    return {"methods": [{"name": "thing.do", "params": [{"name": "params", "schema": ref(params)}],
                         "result": {"name": "result", "schema": ref(result)}}],
            "x-notifications": [], "x-server-requests": [], "components": {"schemas": schemas}}


def _render(schemas: dict) -> str:
    return gen.render(_contract(schemas), surface=gen.Surface(methods=("thing.do",), events=(), requests=()))


EMPTY = {"type": "object", "properties": {}}
NAMED = {"type": "object", "required": ["name"], "properties": {"name": {"type": "string"}}}


def test_a_schema_without_fields_is_a_data_object():
    # Kotlin has no zero-property data class; the object still serializes as {}.
    out = _render({"Params": EMPTY, "Result": NAMED})

    assert "@Serializable\ndata object Params\n" in out and "data class Result(" in out


def test_required_fields_have_no_default_and_optional_ones_default_to_the_contract():
    out = _render({"Params": {"type": "object", "required": ["session_id"], "properties": {
        "session_id": {"type": "string"},
        "cols": {"anyOf": [{"type": "integer"}, {"type": "null"}], "default": None},
        "close_on_disconnect": {"type": "boolean", "default": False}}}, "Result": EMPTY})

    assert '@SerialName("session_id") val sessionId: String,' in out
    assert '@SerialName("cols") val cols: Long? = null,' in out
    assert '@SerialName("close_on_disconnect") val closeOnDisconnect: Boolean = false,' in out


def test_references_lists_and_maps_keep_their_element_types():
    out = _render({"Params": {"type": "object", "properties": {
        "info": {"$ref": "#/components/schemas/Info"},
        "rows": {"type": "array", "items": {"$ref": "#/components/schemas/Info"}, "default": []},
        "tools": {"type": "object", "additionalProperties": {"type": "integer"}, "default": {}}},
        "required": ["info"]}, "Info": NAMED, "Result": EMPTY})

    assert "val info: Info," in out and "val rows: List<Info> = emptyList()," in out
    assert "val tools: Map<String, Long> = emptyMap()," in out
    assert "data class Info(" in out


def test_untyped_and_union_fields_stay_raw_json():
    out = _render({"Params": {"type": "object", "properties": {
        "text": {"default": ""},
        "value": {"anyOf": [{"type": "string"}, {"type": "boolean"}, {"type": "null"}], "default": None},
        "args": {"type": "object", "additionalProperties": True}}, "required": ["args"]}, "Result": EMPTY})

    assert 'val text: JsonElement = JsonPrimitive(""),' in out
    assert "val value: JsonElement? = null," in out and "val args: JsonObject," in out


def test_named_enums_become_enum_classes_with_their_wire_values():
    out = _render({"Params": {"type": "object", "required": ["scope"], "properties": {
        "scope": {"$ref": "#/components/schemas/Scope"}}},
        "Scope": {"type": "string", "enum": ["per-session", "global"]}, "Result": EMPTY})

    assert "enum class Scope {" in out
    assert '@SerialName("per-session") PER_SESSION,' in out and '@SerialName("global") GLOBAL,' in out


def test_methods_are_typed_descriptors():
    out = _render({"Params": EMPTY, "Result": EMPTY})

    assert 'val THING_DO = Method("thing.do", Params.serializer(), Result.serializer())' in out


def test_a_construct_the_renderer_does_not_model_fails_generation():
    with pytest.raises(ValueError, match="oneOf"):
        _render({"Params": {"type": "object", "properties": {"x": {"oneOf": [{"type": "string"}]}}}, "Result": EMPTY})


def test_an_event_without_payload_fields_carries_a_raw_object():
    contract = _contract({"Params": EMPTY, "Result": EMPTY})
    contract["x-notifications"] = [{"name": "message.start", "params": [
        {"name": "payload", "schema": {"type": "object", "additionalProperties": False}}]}]

    out = gen.render(contract, surface=gen.Surface(methods=(), events=("message.start",), requests=()))

    assert 'val MESSAGE_START = Event("message.start", JsonObject.serializer())' in out
