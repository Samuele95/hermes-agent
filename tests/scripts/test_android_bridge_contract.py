"""The Android app's bridge calls into its Python half are typed from one declaration (D26).

``hermes_android/bridge_contract.json`` is what ``hermes_android.call`` enforces at run time; the Kotlin
side is rendered from the same file, so a call, a request field or a reply field cannot drift between the
two halves without this test or the generator failing. Regenerate with
``python -m scripts.android.gen_bridge_contract``.
"""

from __future__ import annotations

import json

import pytest

from scripts.android import gen_bridge_contract as gen


def test_the_committed_kotlin_is_current():
    contract = json.loads(gen.CONTRACT.read_text(encoding="utf-8-sig"))

    assert gen.OUT.read_text(encoding="utf-8-sig") == gen.render(contract), (
        "stale: run python -m scripts.android.gen_bridge_contract")


def _render(calls: dict, enums: dict | None = None) -> str:
    return gen.render({"version": 1, "enums": enums or {}, "calls": calls})


def test_a_call_is_a_typed_descriptor_over_its_request_and_reply():
    text = _render({"ssh_host_key": {"summary": "A host's key.", "params": {"host": "string", "port": "integer"},
                                     "reply": {"key_text": "string", "known": "boolean"}}})

    assert "data class SshHostKeyParams(\n    val host: String,\n    val port: Int,\n)" in text
    assert '@SerialName("key_text") val keyText: String,' in text
    assert "    val known: Boolean," in text
    assert '    /** A host\'s key. */\n    val SSH_HOST_KEY = BridgeCall("ssh_host_key", SshHostKeyParams.serializer(), ' \
           "SshHostKeyReply.serializer())" in text


def test_a_call_without_params_or_reply_fields_uses_the_shared_empty_objects():
    text = _render({"secrets_changed": {"params": {}, "reply": {}}})

    assert 'BridgeCall("secrets_changed", NoParams.serializer(), NoReply.serializer())' in text
    assert "SecretsChangedParams" not in text and "SecretsChangedReply" not in text


def test_a_nullable_string_must_be_present_but_may_be_null():
    text = _render({"status": {"params": {}, "reply": {"allowed_user": "string?"}}})

    assert '@SerialName("allowed_user") val allowedUser: String?,' in text  # no default: the field is always sent


def test_enums_keep_their_wire_values():
    text = _render({"look": {"params": {}, "reply": {"pinned": "Pinned"}}}, enums={"Pinned": ["no", "yes"]})

    assert 'enum class Pinned {\n    @SerialName("no") NO,\n    @SerialName("yes") YES,\n}' in text
    assert "    val pinned: Pinned," in text


def test_a_type_the_contract_does_not_define_fails_generation():
    with pytest.raises(ValueError, match="unknown type"):
        _render({"look": {"params": {"when": "date"}, "reply": {}}})
