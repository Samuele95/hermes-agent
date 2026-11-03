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


def test_adding_again_under_a_saved_name_without_a_key_is_keyless(home):
    # Only an edit (id given) keeps the key variable; the add form's "no key" means no key.
    save()

    assert save(has_key=False) == {"id": "host-ollama", "key_env": ""}
    assert "key_env" not in load_config()["providers"]["host-ollama"]


def test_a_deleted_default_endpoint_no_longer_routes_the_main_model(home, servers):
    # The shape the app's `config.set model "<m> --provider <id> --global"` writes, through the core's own
    # selection path; plus the key pointer the Desktop's activation also mirrors onto `model`.
    from hermes_cli.config import save_config
    from hermes_cli.model_switch import persist_model_selection, switch_model
    server = servers(models=["gemma4"])
    url = f"http://127.0.0.1:{server.port}/v1"
    save(base_url=url, model="gemma4", has_key=False)
    cfg = load_config()
    chosen = switch_model("gemma4", "", "", is_global=True, explicit_provider="host-ollama",
                          user_providers=cfg.get("providers"), custom_providers=cfg.get("custom_providers"))
    assert chosen.success
    persist_model_selection(chosen)
    cfg = load_config()
    cfg["model"]["key_env"] = KEY_ENV
    save_config(cfg)
    assert resolve_runtime_provider()["base_url"] == url  # routed there before the delete

    gone = json.loads(endpoints.delete(json.dumps({"id": "host-ollama"})))

    assert gone["was_default"] is True
    assert listed() == []
    model = load_config()["model"]
    assert model["default"] == "gemma4"
    assert not {"provider", "base_url", "api_key", "key_env"} & set(model)
    from hermes_cli.auth_constants import AuthError
    with pytest.raises(AuthError) as nothing_left:  # no provider left, rather than the deleted URL
        resolve_runtime_provider()
    assert nothing_left.value.code == "no_provider_configured"


@pytest.mark.parametrize("spelled", ["host-ollama", "custom:host-ollama", "custom:host ollama"])
def test_every_spelling_of_the_deleted_endpoint_detaches_the_main_model(home, spelled):
    # The core spells a picked custom provider as its key or as custom:<lowercased name>
    # (hermes_cli/web_routers/config_env.py::_model_names_provider).
    from hermes_cli.config import save_config
    save()
    cfg = load_config()
    cfg["model"] = {"default": "gemma4", "provider": spelled, "base_url": "http://127.0.0.1:11434/v1",
                    "api_key": "sk-inline", "key_env": KEY_ENV}
    save_config(cfg)

    gone = json.loads(endpoints.delete(json.dumps({"id": "host-ollama"})))

    assert gone == {"key_env": KEY_ENV, "was_default": True}
    assert load_config()["model"] == {"default": "gemma4"}


def test_a_main_model_on_another_provider_is_left_alone(home):
    from hermes_cli.config import save_config
    save()
    cfg = load_config()
    cfg["model"] = {"default": "x", "provider": "other", "base_url": "http://10.0.0.9/v1", "key_env": "OTHER_KEY"}
    save_config(cfg)

    gone = json.loads(endpoints.delete(json.dumps({"id": "host-ollama"})))

    assert gone["was_default"] is False
    assert load_config()["model"] == {"default": "x", "provider": "other", "base_url": "http://10.0.0.9/v1",
                                      "key_env": "OTHER_KEY"}


def _hand_written_proxy(home):
    # An entry written by hand (or by the Desktop) whose key lives in another provider's variable.
    from hermes_cli.config import save_config
    cfg = load_config()
    cfg["providers"] = {"proxy": {"name": "Proxy", "base_url": "http://10.0.0.3/v1", "model": "m",
                                  "key_env": "OPENROUTER_API_KEY"}}
    save_config(cfg)


def test_deleting_an_entry_keyed_by_another_variable_names_only_its_own_slot(home):
    _hand_written_proxy(home)

    gone = json.loads(endpoints.delete(json.dumps({"id": "proxy"})))

    # OPENROUTER_API_KEY is not this endpoint's to delete; its own slot is (as Desktop's delete does).
    assert gone == {"key_env": "HERMES_CUSTOM_PROXY_API_KEY", "was_default": False}


def test_deleting_a_keyless_entry_still_names_its_own_slot(home):
    # A key stored earlier may still sit in the keystore after a keyless re-save dropped key_env.
    save(has_key=False)

    gone = json.loads(endpoints.delete(json.dumps({"id": "host-ollama"})))

    assert gone["key_env"] == "HERMES_CUSTOM_HOST_OLLAMA_API_KEY"


def test_an_edit_without_a_new_key_keeps_the_entrys_key_variable(home):
    _hand_written_proxy(home)

    saved = save(id="proxy", name="Proxy", base_url="http://10.0.0.4/v1", model="m", has_key=False)

    assert saved == {"id": "proxy", "key_env": "OPENROUTER_API_KEY"}
    assert load_config()["providers"]["proxy"]["key_env"] == "OPENROUTER_API_KEY"


def test_deleting_an_unknown_endpoint_is_an_error_in_words(home):
    assert "error" in json.loads(endpoints.delete(json.dumps({"id": "nope"})))


def test_the_list_and_delete_bridge_calls_answer_their_declared_replies(home):
    import hermes_android
    save(has_key=False)

    rows = json.loads(hermes_android.call("list_custom_endpoints", ""))["endpoints"]
    gone = json.loads(hermes_android.call("delete_custom_endpoint", json.dumps({"id": "host-ollama"})))

    assert [r["id"] for r in rows] == ["host-ollama"]
    assert gone == {"key_env": "HERMES_CUSTOM_HOST_OLLAMA_API_KEY", "was_default": False}
