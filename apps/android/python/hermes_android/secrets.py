"""The ``android_keystore`` secret source: provider keys held in the Android keystore, by variable name.

The app stores each key under the environment variable its provider reads, so the keystore is the
authority for which variables exist: the source is ``bulk`` and needs no per-key mapping in
config.yaml. Values never touch ``.env``; they live in the process environment only.
"""

from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
from typing import Protocol

from agent.secret_sources.base import ErrorKind, FetchResult, SecretSource, is_valid_env_name

NAME = "android_keystore"


class Keystore(Protocol):
    """The bridge's upcall: the keystore's secrets as a JSON object of variable name to value."""

    def secrets(self) -> str: ...


class KeystoreSecretSource(SecretSource):
    name = NAME
    label = "Android Keystore"
    shape = "bulk"
    # The keystore is the app's only authority for these variables (no .env, no shell): a rotated key
    # must replace the value applied before, as the vault sources do.
    override_existing_default = True

    def __init__(self, keystore: Keystore) -> None:
        self._keystore = keystore

    def fetch(self, cfg: dict, home_path: Path) -> FetchResult:
        result = FetchResult()
        try:
            held = json.loads(self._keystore.secrets())
        except (RuntimeError, ValueError) as exc:  # the upcall's failure, or malformed JSON (a position, no content)
            return result.fail(f"the Android keystore could not be read: {exc}", ErrorKind.INTERNAL)
        for name, value in (held.items() if isinstance(held, dict) else ()):
            if is_valid_env_name(name) and isinstance(value, str):
                result.secrets[name] = value
            else:
                result.warnings.append(f"skipped {name!r}: not an environment variable name")
        return result


def enable(keystore: Keystore) -> None:
    """Register the source and switch it on in the profile's config.yaml: the core applies only
    sources a ``secrets.<name>.enabled: true`` section names."""
    from agent.secret_sources.registry import register_source
    from hermes_cli.config import load_config_readonly, set_config_value

    register_source(KeystoreSecretSource(keystore), replace=True)
    section = (load_config_readonly().get("secrets") or {}).get(NAME) or {}
    if section.get("enabled") is not True:
        # The setter confirms on stdout for the CLI; in the app stdout is logcat.
        with contextlib.redirect_stdout(io.StringIO()):
            set_config_value(f"secrets.{NAME}.enabled", "true")
