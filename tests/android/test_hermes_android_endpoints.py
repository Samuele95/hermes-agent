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
    request = {"name": "Host Ollama", "base_url": "http://127.0.0.1:11434/v1/", "model": "gemma4:12b-it-q8_0",
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
