"""The app's 'Find server': the server behind an address the user typed, found through the core's own probe."""

from __future__ import annotations

import importlib
import json

import pytest

from tests.android.fake_endpoint import closed_port

probing = importlib.import_module("hermes_android.probing")


@pytest.fixture
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    return tmp_path


def probe(address, **kw):
    return probing.probe(address, timeout=2.0, **kw)


def test_a_bare_host_is_found_on_the_first_known_port_that_lists_models(home, servers):
    ollama = servers(models=["gemma4:12b-it-q8_0", "gemma3:12b-it-q8_0"])

    found = probe("127.0.0.1", ports=((closed_port(), "ollama"), (ollama.port, "lmstudio")))

    assert found == {"outcome": "found", "base_url": f"http://127.0.0.1:{ollama.port}/v1", "kind": "lmstudio",
                     "models": ["gemma4:12b-it-q8_0", "gemma3:12b-it-q8_0"]}


def test_a_full_url_is_probed_as_given_and_its_v1_is_fixed(home, servers):
    server = servers(models=["m1"])

    found = probe(f"http://127.0.0.1:{server.port}")  # no /v1: the core's probe tries it

    assert found["outcome"] == "found" and found["models"] == ["m1"]
    assert found["base_url"].startswith(f"http://127.0.0.1:{server.port}")


def test_a_host_with_a_port_is_tried_on_that_port_only(home, servers):
    server = servers(models=["m1"])

    found = probe(f"127.0.0.1:{server.port}", ports=((closed_port(), "ollama"),))

    assert found["outcome"] == "found" and found["base_url"] == f"http://127.0.0.1:{server.port}/v1"


def test_a_server_behind_a_key_needs_it_and_answers_with_it(home, servers):
    server = servers(models=["m1"], key="sk-test-123")
    url = f"http://127.0.0.1:{server.port}/v1"

    assert probe(url)["outcome"] == "needs_key"
    assert probe(url, api_key="sk-test-123")["models"] == ["m1"]
    assert "Bearer sk-test-123" in server.seen_auth


def test_a_refusing_server_says_refused_not_needs_key(home, servers):
    server = servers(status=403)

    assert probe(f"http://127.0.0.1:{server.port}/v1")["outcome"] == "refused"


def test_a_full_url_without_v1_against_a_key_protected_server_needs_the_key(home, servers):
    # The server only answers /v1/models (as real Ollama/LM Studio/llama.cpp/vLLM do); a bare /models 404s.
    # The address is given without /v1, so the status check must also look at the /v1-toggled variant.
    server = servers(models=["m1"], key="sk-test-123")

    assert probe(f"http://127.0.0.1:{server.port}")["outcome"] == "needs_key"


def test_a_full_url_without_v1_against_a_refusing_server_is_refused(home, servers):
    server = servers(status=403)

    assert probe(f"http://127.0.0.1:{server.port}")["outcome"] == "refused"


def test_nothing_listening_is_unreachable_and_an_empty_list_is_no_models(home, servers):
    empty = servers(models=[])

    assert probe("127.0.0.1", ports=((closed_port(), "ollama"),)) == {
        "outcome": "unreachable", "base_url": None, "kind": None, "models": []}
    assert probe(f"http://127.0.0.1:{empty.port}/v1")["outcome"] == "no_models"


def test_the_key_never_comes_back_in_the_reply(home, servers):
    server = servers(models=["m1"], key="sk-test-123")

    reply = probing.probe(f"http://127.0.0.1:{server.port}/v1", api_key="sk-test-123", timeout=2.0)

    assert "sk-test-123" not in json.dumps(reply)


def test_the_bridge_call_answers_its_declared_reply(home, servers):
    import hermes_android
    server = servers(models=["m1"])

    reply = json.loads(hermes_android.call("probe_endpoint",
                                           json.dumps({"address": f"http://127.0.0.1:{server.port}/v1", "api_key": None})))

    assert reply["outcome"] == "found"


def test_reading_why_a_probe_failed_closes_every_answer(home, servers):
    import gc
    import warnings

    from hermes_android.probing import status

    refusing = servers(status=403)
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always", ResourceWarning)
        assert status(f"http://127.0.0.1:{refusing.port}", None, 2.0) == 403
        gc.collect()

    assert [w for w in caught if issubclass(w.category, ResourceWarning)] == []
