"""What the Android app's embedded interpreter runs before the core.

The native bridge starts CPython with the stdlib and this directory on ``sys.path`` and calls
:func:`start`. Frames to the app leave through ``native.emit``; the app's requests come back through
:func:`submit`, one at a time, from the bridge thread.

The app's optional ``:gateway`` process (D12) starts its own interpreter with the role ``gateway``: the
same directories and keystore secrets, no chat transport, and a :class:`~hermes_android.gateway_host.GatewayHost`
the app drives with :func:`gateway_start` / :func:`gateway_stop`, whose state changes leave through
``native.emit``.

Every other call from the app goes through :func:`call`, which admits only the calls declared in
``bridge_contract.json`` (D26) and holds their requests and replies to it.
"""

from __future__ import annotations

import json
import os
import site
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

if TYPE_CHECKING:  # the core is importable only after prepare()
    from tui_gateway.embedded import EmbeddedTransport

    from hermes_android.gateway_host import GatewayHost


class Native(Protocol):
    """The bridge's upcalls into the app (the native ``_hermes_native`` module)."""

    def emit(self, line: str, /) -> bool: ...

    def secrets(self) -> str: ...


_transport: EmbeddedTransport | None = None
_gateway: GatewayHost | None = None


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
    global _transport, _gateway
    prepare(paths)
    # Registered before the core's first env load (tui_gateway.server's import), which applies it.
    from hermes_android import secrets

    secrets.enable(native)
    if paths.get("role") == "gateway":
        from hermes_android.gateway_host import GatewayHost

        _gateway = GatewayHost(native.emit)
        return
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
    global _transport, _gateway
    if _transport is not None:
        _transport.close()
        _transport = None
    if _gateway is not None:
        _gateway.stop()
        _gateway = None


_CONTRACT = json.loads(Path(__file__).with_name("bridge_contract.json").read_text(encoding="utf-8"))


def _conforms(kind: str, value: object) -> bool:
    if kind in _CONTRACT["enums"]:
        return value in _CONTRACT["enums"][kind]
    if kind == "string?":
        return value is None or isinstance(value, str)
    if kind == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    return isinstance(value, {"string": str, "boolean": bool}[kind])


def _mismatch(declared: dict[str, str], given: object) -> str | None:
    """Why ``given`` is not an object with exactly the ``declared`` fields, or None when it is."""
    if not isinstance(given, dict):
        return "not a JSON object"
    if set(given) != set(declared):
        return f"fields {sorted(given)}, declared {sorted(declared)}"
    wrong = [name for name, kind in declared.items() if not _conforms(kind, given[name])]
    return f"{wrong} not of the declared type" if wrong else None


def call(function: str, arg: str) -> str:
    """The app's way into this module: ``function`` must be a call ``bridge_contract.json`` declares, ``arg`` its
    request (an empty string is an empty request). The answer is the call's declared reply, or ``{error}``
    in words for the user. Anything else is a bug on one side of the bridge and raises, so it never reaches
    the app as a reply it would misread."""
    declared = _CONTRACT["calls"].get(function)
    if declared is None:
        raise LookupError(f"{function!r} is not a bridge call")
    why = _mismatch(declared["params"], json.loads(arg) if arg else {})
    if why:
        raise ValueError(f"request for {function}: {why}")
    answer = globals()[function](arg)
    reply = json.loads(answer)
    if not (isinstance(reply, dict) and set(reply) == {"error"} and isinstance(reply["error"], str)):
        why = _mismatch(declared["reply"], reply)
        if why:
            raise TypeError(f"reply of {function}: {why}")
    return answer


def gateway_start(_arg: str) -> str:
    """Starts the gateway in this (``:gateway``) process; its state changes arrive through ``native.emit``."""
    if _gateway is not None:
        _gateway.start()
    return gateway_state("")


def gateway_stop(_arg: str) -> str:
    """Asks the gateway to drain and stop; ``stopped`` arrives through ``native.emit`` once it has."""
    if _gateway is not None:
        _gateway.stop()
    return gateway_state("")


def gateway_state(_arg: str) -> str:
    return json.dumps({"gateway": _gateway.state() if _gateway is not None else "stopped"})


def runtime_facts(_arg: str) -> str:
    """The interpreter facts the app shows, as JSON (FTS5 backs session search; python.org's SQLite lacks it)."""
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
    return "{}"


def save_custom_endpoint(arg: str) -> str:
    """The app's call into :func:`hermes_android.endpoints.save_custom_endpoint`."""
    from hermes_android import endpoints

    return endpoints.save_custom_endpoint(arg)


def ssh_new_key(arg: str) -> str:
    from hermes_android import ssh

    return ssh.new_key(arg)


def ssh_host_key(arg: str) -> str:
    from hermes_android import ssh

    return ssh.host_key(arg)


def ssh_trust_host(arg: str) -> str:
    from hermes_android import ssh

    return ssh.trust_host(arg)


def ssh_configure(arg: str) -> str:
    from hermes_android import ssh

    return ssh.configure(arg)


def ssh_public_key(arg: str) -> str:
    from hermes_android import ssh

    return ssh.public_key(arg)


def ssh_check_login(arg: str) -> str:
    from hermes_android import ssh

    return ssh.check_login(arg)


def telegram_configure(arg: str) -> str:
    from hermes_android import telegram

    return telegram.configure(arg)


def telegram_status(arg: str) -> str:
    from hermes_android import telegram

    return telegram.status(arg)


def telegram_check(arg: str) -> str:
    from hermes_android import telegram

    return telegram.check(arg)
