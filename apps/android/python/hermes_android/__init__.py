"""What the Android app's embedded interpreter runs before the core.

The native bridge starts CPython with the stdlib and this directory on ``sys.path`` and calls
:func:`start`. Frames to the app leave through ``native.emit``; the app's requests come back through
:func:`submit`, one at a time, from the bridge thread.
"""

from __future__ import annotations

import os
import site
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

if TYPE_CHECKING:  # the core is importable only after prepare()
    from tui_gateway.embedded import EmbeddedTransport


class Native(Protocol):
    """The bridge's upcalls into the app (the native ``_hermes_native`` module)."""

    def emit(self, line: str, /) -> bool: ...

    def secrets(self) -> str: ...


_transport: EmbeddedTransport | None = None


def prepare(paths: dict[str, str]) -> None:
    """Point the core at the app's directories and the extracted runtime. Runs before any core import."""
    os.environ["HOME"] = paths["home"]
    os.environ["HERMES_HOME"] = paths["hermes_home"]
    os.environ["TMPDIR"] = paths["tmp"]
    # The wheels' OpenSSL looks for its legacy provider under the Termux prefix, unreadable from an app:
    # say so up front, or every `import cryptography` warns on stderr (logcat). Nothing else changes.
    os.environ.setdefault("CRYPTOGRAPHY_OPENSSL_NO_LEGACY", "1")
    # The process image is app_process64, not Python: subprocess spawners must see no interpreter.
    # (_base_executable is private and undeclared; the core reads it to find the base interpreter.)
    sys.executable = ""
    sys._base_executable = ""  # ty: ignore[unresolved-attribute]
    runtime = Path(paths["runtime"])
    sys.path.append(str(runtime / "app"))
    site.addsitedir(str(runtime / "site-packages"))


def start(paths: dict[str, str], native: Native) -> None:
    global _transport
    prepare(paths)
    # Registered before the core's first env load (tui_gateway.server's import), which applies it.
    from hermes_android import secrets

    secrets.enable(native)
    _seed_bundled_skills()
    from tui_gateway.embedded import EmbeddedTransport

    _transport = EmbeddedTransport.connect(native.emit)


def _seed_bundled_skills() -> None:
    """Copy the bundled skills into the home, as the CLI does at its startup (``hermes_cli.main``), which
    the app's host never runs: at once into a home that has none, so the first session sees them,
    else in the background to pick up an updated payload's changes."""
    from agent.memory_provider import spawn_context_thread
    from hermes_constants import get_hermes_home
    from tools.skills_sync import sync_skills

    skills = get_hermes_home() / "skills"
    if skills.is_dir() and next(skills.rglob("SKILL.md"), None) is not None:
        spawn_context_thread(sync_skills, name="bundled-skills-sync", kwargs={"quiet": True}).start()
        return
    sync_skills(quiet=True)
    from hermes_cli import banner

    banner._available_skills_cache = None  # computed before the seed: empty


def submit(frame: str) -> None:
    if _transport is not None:
        _transport.submit(frame)


def stop() -> None:
    global _transport
    if _transport is not None:
        _transport.close()
        _transport = None


def runtime_facts(_arg: str) -> str:
    """The interpreter facts the app shows, as JSON (FTS5 backs session search; python.org's SQLite lacks it)."""
    import json
    import sqlite3

    try:
        sqlite3.connect(":memory:").execute("CREATE VIRTUAL TABLE probe USING fts5(text)")
        fts5 = True
    except sqlite3.OperationalError:
        fts5 = False
    return json.dumps({"python": sys.version.split()[0], "sqlite": sqlite3.sqlite_version, "fts5": fts5,
                       "executable": sys.executable})


def secrets_changed(_arg: str) -> str:
    """Re-pull the keystore's secrets after the app changed one; variables it no longer holds are revoked.
    Agents already built keep the credentials they started with; new sessions see the change."""
    from hermes_cli.env_loader import load_hermes_dotenv, reset_secret_source_cache

    home = os.environ["HERMES_HOME"]
    reset_secret_source_cache(home)
    load_hermes_dotenv(hermes_home=home)
    return ""


def save_custom_endpoint(arg: str) -> str:
    """The app's call into :func:`hermes_android.endpoints.save_custom_endpoint`."""
    from hermes_android import endpoints

    return endpoints.save_custom_endpoint(arg)
