"""OpenRouter takes a pasted key like every api_key provider: its picker row names the variable,
``model.save_key`` stores it and ``model.disconnect`` removes it. OpenRouter is not in the auth
registry (it is the aggregator default), so the variable comes from the provider catalog."""

from __future__ import annotations

import pytest

import tui_gateway.server as srv
from hermes_cli.config import load_env


@pytest.fixture(autouse=True)
def no_openrouter_key(monkeypatch):
    monkeypatch.delenv("OPENROUTER_API_KEY", raising=False)


def _result(method: str, params: dict) -> dict:
    response = srv._methods[method](1, params)
    assert "result" in response, response
    return response["result"]


def _openrouter_row() -> dict:
    rows = _result("model.options", {"include_unconfigured": True})["providers"]
    return next(row for row in rows if row["slug"] == "openrouter")


def test_the_openrouter_row_names_the_key_it_takes():
    row = _openrouter_row()

    assert (row["authenticated"], row["auth_type"], row["key_env"]) == (False, "api_key", "OPENROUTER_API_KEY")
    assert row["warning"] == "paste OPENROUTER_API_KEY to activate"


def test_a_pasted_openrouter_key_is_saved_and_a_disconnect_takes_it_back():
    saved = _result("model.save_key", {"slug": "openrouter", "api_key": "sk-or-v1-" + "test"})

    assert saved["provider"]["authenticated"] is True
    assert load_env().get("OPENROUTER_API_KEY") == "sk-or-v1-test"

    assert _result("model.disconnect", {"slug": "openrouter"})["disconnected"] is True
    assert "OPENROUTER_API_KEY" not in load_env()


def test_a_fresh_install_without_a_models_dev_catalog_still_names_the_key(monkeypatch):
    from hermes_cli import providers
    monkeypatch.setattr(providers, "_models_dev_info", lambda *_args, **_kwargs: None)

    assert _openrouter_row()["key_env"] == "OPENROUTER_API_KEY"


def test_a_providers_key_auth_is_a_named_record():
    from hermes_cli.providers import api_key_auth

    auth = api_key_auth("deepseek")
    assert (auth.auth_type, auth.name) == ("api_key", "DeepSeek") and "DEEPSEEK_API_KEY" in auth.env_vars
    assert api_key_auth("no-such-provider") is None
