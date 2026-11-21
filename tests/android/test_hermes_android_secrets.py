"""Provider keys reach the core from the Android keystore, never from ``.env``.

The app stores each key under the environment variable its provider reads; the ``android_keystore``
secret source hands the whole set to the core's secret-source orchestrator, which applies it at the
core's env load. After the app changes a key, :func:`hermes_android.secrets_changed` re-pulls the
set and revokes what the keystore no longer holds.
"""

from __future__ import annotations

import importlib
import os

import pytest

from agent.secret_sources import registry
from agent.secret_sources.base import ErrorKind
from hermes_cli import env_loader

hermes_android = importlib.import_module("hermes_android")
secrets = importlib.import_module("hermes_android.secrets")

KEY = "DEEPSEEK_API_KEY"


class Keystore:
    """The app's side of the ``secrets`` upcall: a JSON object of variable name to value."""

    def __init__(self, held: dict[str, str]):
        self.held = held

    def secrets(self) -> str:
        import json
        return json.dumps(self.held)


@pytest.fixture
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    monkeypatch.delenv(KEY, raising=False)
    (tmp_path / "config.yaml").write_text("model:\n  default: some/model  # the user's\n", encoding="utf-8")
    yield tmp_path
    registry._reset_registry_for_tests()
    env_loader.reset_secret_source_cache()
    os.environ.pop(KEY, None)


def test_the_source_hands_over_what_the_keystore_holds(home):
    result = secrets.KeystoreSecretSource(Keystore({KEY: "sk-1"})).fetch({}, home)

    assert result.ok and result.secrets == {KEY: "sk-1"}


def test_a_name_that_is_not_an_env_var_is_skipped_with_a_warning(home):
    result = secrets.KeystoreSecretSource(Keystore({KEY: "sk-1", "not a name": "x"})).fetch({}, home)

    assert result.secrets == {KEY: "sk-1"} and result.warnings


def test_a_failing_keystore_is_reported_not_raised(home):
    class Broken:
        def secrets(self) -> str:
            raise RuntimeError("keystore locked")

    result = secrets.KeystoreSecretSource(Broken()).fetch({}, home)

    assert not result.ok and result.error_kind is ErrorKind.INTERNAL and "keystore locked" in result.error


def test_enabling_registers_the_source_and_turns_it_on_in_the_users_config(home, capsys):
    secrets.enable(Keystore({}))

    # In the app, stdout is a logcat stream: the CLI setter's confirmation line must not reach it.
    assert capsys.readouterr().out == ""

    assert registry.get_source("android_keystore") is not None
    config = (home / "config.yaml").read_text(encoding="utf-8")
    assert "android_keystore:\n    enabled: true" in config and "# the user's" in config


def test_the_core_reads_the_key_and_a_change_reaches_it_without_restarting(home):
    keystore = Keystore({KEY: "sk-1"})
    secrets.enable(keystore)

    env_loader.load_hermes_dotenv(hermes_home=home)
    assert os.environ.get(KEY) == "sk-1"

    keystore.held = {KEY: "sk-2"}
    hermes_android.secrets_changed("")
    assert os.environ.get(KEY) == "sk-2"

    keystore.held = {}
    hermes_android.secrets_changed("")
    assert KEY not in os.environ
