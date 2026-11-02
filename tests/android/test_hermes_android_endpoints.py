"""The app adds an OpenAI-compatible endpoint as a ``providers.<id>`` entry; its key stays in the keystore.

The entry has the shape the Desktop's custom-endpoint settings write, including the ``key_env`` name
(``HERMES_CUSTOM_<ID>_API_KEY``), but nothing is written to ``.env``: the app stores the key under that
variable in the Android keystore, and the core reads it through the ``android_keystore`` secret source.
"""

from __future__ import annotations

import importlib
import json

import pytest

from hermes_cli.config import load_config
from hermes_cli.runtime_provider import resolve_runtime_provider
from tests.android.fake_endpoint import FakeEndpoint, closed_port

endpoints = importlib.import_module("hermes_android.endpoints")

KEY_ENV = "HERMES_CUSTOM_HOST_OLLAMA_API_KEY"


@pytest.fixture
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    monkeypatch.delenv(KEY_ENV, raising=False)
    return tmp_path


def save(**fields) -> dict:
    request = {"id": None, "name": "Host Ollama", "base_url": "http://127.0.0.1:11434/v1/", "model": "gemma4:12b-it-q8_0",
               "has_key": True, **fields}
    return json.loads(endpoints.save_custom_endpoint(json.dumps(request)))


def test_a_saved_endpoint_resolves_to_its_url_with_the_key_from_the_environment(home, monkeypatch):
    saved = save()

    assert saved == {"id": "host-ollama", "key_env": KEY_ENV}
    # Stored as the Desktop stores it: paths such as /models are joined onto it.
    assert load_config()["providers"]["host-ollama"]["base_url"] == "http://127.0.0.1:11434/v1"
    monkeypatch.setenv(KEY_ENV, "sk-from-the-keystore")  # what the android_keystore source does
    runtime = resolve_runtime_provider(requested="host-ollama")
    assert (runtime["base_url"], runtime["api_key"]) == ("http://127.0.0.1:11434/v1", "sk-from-the-keystore")
    assert not (home / ".env").exists()
    assert "sk-from-the-keystore" not in (home / "config.yaml").read_text(encoding="utf-8")


def test_an_endpoint_without_a_key_names_no_variable_and_sends_the_placeholder(home):
    saved = save(has_key=False)

    assert saved["key_env"] == ""
    assert "key_env" not in load_config()["providers"]["host-ollama"]
    assert resolve_runtime_provider(requested="host-ollama")["api_key"] == "no-key-required"


def test_saving_again_keeps_what_the_app_does_not_edit(home):
    save()
    from hermes_cli.config import save_config
    cfg = load_config()
    cfg["providers"]["host-ollama"]["extra_headers"] = {"X-Route": "lab"}
    save_config(cfg)

    save(model="gemma4:27b")

    entry = load_config()["providers"]["host-ollama"]
    assert entry["model"] == "gemma4:27b" and entry["extra_headers"] == {"X-Route": "lab"}
    assert set(entry["models"]) == {"gemma4:12b-it-q8_0", "gemma4:27b"}


@pytest.mark.parametrize("fields", [{"base_url": "127.0.0.1:11434"}, {"name": " "}, {"model": ""}])
def test_an_incomplete_endpoint_is_refused_in_words_before_anything_is_written(home, fields):
    assert save(**fields) == {"error": "An endpoint needs a name, a model and an http(s) base URL."}

    assert "providers" not in load_config() or not load_config()["providers"]


def listed() -> list[dict]:
    return json.loads(endpoints.list_saved())["endpoints"]


def test_saved_endpoints_are_listed_without_keys(home):
    save()
    save(name="Other", base_url="http://10.0.0.2:1234/v1", model="qwen", has_key=False)

    assert listed() == [
        {"id": "host-ollama", "name": "Host Ollama", "base_url": "http://127.0.0.1:11434/v1",
         "model": "gemma4:12b-it-q8_0", "has_key": True},
        {"id": "other", "name": "Other", "base_url": "http://10.0.0.2:1234/v1", "model": "qwen", "has_key": False}]


def test_an_edit_keeps_the_id_even_when_the_name_changes(home):
    save()

    edited = save(id="host-ollama", name="DGX", base_url="http://spark-98d1:11434/v1")

    assert edited["id"] == "host-ollama" and edited["key_env"] == KEY_ENV
    assert [(e["id"], e["name"], e["base_url"]) for e in listed()] == [("host-ollama", "DGX", "http://spark-98d1:11434/v1")]


def test_an_id_no_longer_saved_is_a_new_endpoint_named_by_the_id_rule(home):
    # e.g. deleted on another screen while this form was open: the stale id is not written as given.
    saved = save(id="Gone Away")

    assert saved == {"id": "host-ollama", "key_env": KEY_ENV}
    assert [e["id"] for e in listed()] == ["host-ollama"]


def test_a_deleted_endpoint_is_gone_and_names_its_key_and_default(home):
    from hermes_cli.config import save_config
    save()
    cfg = load_config()
    cfg["model"] = {"default": "gemma4:12b-it-q8_0", "provider": "host-ollama"}
    save_config(cfg)

    gone = json.loads(endpoints.delete(json.dumps({"id": "host-ollama"})))

    assert gone == {"key_env": KEY_ENV, "was_default": True}
    assert listed() == []
    assert load_config()["model"].get("provider") in (None, "")


def test_deleting_an_unknown_endpoint_is_an_error_in_words(home):
    assert "error" in json.loads(endpoints.delete(json.dumps({"id": "nope"})))


def test_the_list_and_delete_bridge_calls_answer_their_declared_replies(home):
    import hermes_android
    save(has_key=False)

    rows = json.loads(hermes_android.call("list_custom_endpoints", ""))["endpoints"]
    gone = json.loads(hermes_android.call("delete_custom_endpoint", json.dumps({"id": "host-ollama"})))

    assert [r["id"] for r in rows] == ["host-ollama"]
    assert gone == {"key_env": None, "was_default": False}


@pytest.fixture
def servers():
    made = []

    def make(**kw):
        made.append(FakeEndpoint(**kw))
        return made[-1]

    yield make
    for s in made:
        s.close()


def probe(address, **kw):
    return endpoints.probe(address, timeout=2.0, **kw)


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

    reply = endpoints.probe(f"http://127.0.0.1:{server.port}/v1", api_key="sk-test-123", timeout=2.0)

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

    from hermes_android.endpoints import _status

    refusing = servers(status=403)
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always", ResourceWarning)
        assert _status(f"http://127.0.0.1:{refusing.port}", None, 2.0) == 403
        gc.collect()

    assert [w for w in caught if issubclass(w.category, ResourceWarning)] == []
