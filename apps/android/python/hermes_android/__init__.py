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

:func:`prepare`, in both roles, keeps ``$HOME/bin/python3`` pointing at the Python launcher the installer extracted
(:mod:`hermes_android.launcher`), the one interpreter a child process of the app can run.
"""

from __future__ import annotations

import json
import logging
import os
import queue
import site
import sys
import threading
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
_warm: threading.Thread | None = None
_warm_go: queue.SimpleQueue[bool] | None = None  # True: the app's startup calls are done; False: stop()


def prepare(paths: dict[str, str]) -> None:
    """Point the core at the app's directories and the extracted runtime. Runs before any core import."""
    os.environ["HOME"] = paths["home"]
    os.environ["HERMES_HOME"] = paths["hermes_home"]
    os.environ["TMPDIR"] = paths["tmp"]
    # The wheels' OpenSSL looks for its legacy provider under the Termux prefix, unreadable from an app:
    # say so up front, or every `import cryptography` warns on stderr (logcat). Nothing else changes.
    os.environ.setdefault("CRYPTOGRAPHY_OPENSSL_NO_LEGACY", "1")
    # The core's package manager would fetch Termux binaries (uvx, npx, node) the app cannot exec: none, ever.
    os.environ["HERMES_DISABLE_LAZY_INSTALLS"] = "1"
    # The process image is app_process64, not Python: subprocess spawners must see no interpreter.
    # (_base_executable is private and undeclared; the core reads it to find the base interpreter.)
    sys.executable = ""
    sys._base_executable = ""  # ty: ignore[unresolved-attribute]
    runtime = Path(paths["runtime"])
    sys.path.append(str(runtime / "app"))
    site.addsitedir(str(runtime / "site-packages"))
    # python.org's Android CPython finds no system CA store: without a bundle every HTTPS request through ssl's
    # default context (urllib in the core) fails verification. certifi's, as httpx uses, unless the host set one.
    import certifi

    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
    from hermes_android import launcher

    launcher.ensure(paths["home"], paths["native_libs"], paths["runtime"])


def start(paths: dict[str, str], native: Native) -> None:
    global _transport, _gateway, _warm, _warm_go
    prepare(paths)
    # Registered before the core's first env load (tui_gateway.server's import), which applies it.
    from hermes_android import secrets

    secrets.enable(native)
    if paths.get("role") == "gateway":
        from hermes_android.gateway_host import GatewayHost

        _gateway = GatewayHost(native.emit)
        return
    from tui_gateway.embedded import EmbeddedTransport, seed_bundled_skills

    seed_bundled_skills()  # the CLI does it at its startup, which the app's host never runs
    _transport = EmbeddedTransport.connect(native.emit)
    _warm_go = queue.SimpleQueue()
    _warm = _warm_delivery_targets(_warm_go)


def _warm_delivery_targets(go: queue.SimpleQueue[bool]) -> threading.Thread:
    """The job form's first ``cron_delivery_targets`` loads every plugin and platform adapter (about 2.8 s on the
    phone) on ``hermes-python``, the thread that carries the chat's frames: load them here, beside it, then scan the
    skills listing, whose first read (up to about 1 s on the phone) also held ``hermes-python``. The thread waits
    until the app says its startup calls are done (:func:`cron_warm_delivery_targets`): started earlier, its loads
    slowed those calls through the GIL (about +650 ms to ready on the phone). This runs after the keystore source and
    the core's env load, so never from :func:`prepare`. A form or Skills page opened meanwhile waits on the core's own
    locks or scans for itself."""
    import cron.scheduler_delivery  # in sys.modules when start() returns
    from agent.memory_provider import spawn_context_thread

    thread = spawn_context_thread(_warm_when_told, args=(go,), name="hermes-prewarm")
    thread.start()
    return thread


def _warm_when_told(go: queue.SimpleQueue[bool]) -> None:
    if go.get():
        _load_delivery_targets()
        _load_skills()


def _load_delivery_targets() -> None:
    from cron.scheduler_delivery import cron_delivery_targets

    try:
        cron_delivery_targets()
    except Exception:  # the first form then pays the cost, as before
        logging.getLogger(__name__).debug("warming the delivery targets failed", exc_info=True)


def _load_skills() -> None:
    try:
        from tools.skills_tool import _find_all_skills

        _find_all_skills(skip_disabled=True)  # skills_list's own call (skills.py), so its cache entry
    except Exception:  # the first Skills page then pays the cost, as before
        logging.getLogger(__name__).debug("warming the skills listing failed", exc_info=True)


def cron_warm_delivery_targets(_arg: str) -> str:
    """The app's startup calls are done: the waiting warm loads the job form's delivery targets, then scans the
    skills listing (once; a later call is left unread)."""
    if _warm_go is not None:
        _warm_go.put(True)
    return "{}"


def submit(frame: str) -> None:
    if _transport is not None:
        _transport.submit(frame)


def stop() -> None:
    global _transport, _gateway, _warm, _warm_go
    if _warm is not None:
        if _warm_go is not None:
            _warm_go.put(False)  # releases a warm the app never told to go
        _warm.join(timeout=60)
        _warm = None
        _warm_go = None
    if _transport is not None:
        _transport.close()
        _transport = None
    if _gateway is not None:
        _gateway.stop()
        _gateway = None


_CONTRACT = json.loads(Path(__file__).with_name("bridge_contract.json").read_text(encoding="utf-8"))


def _conforms(kind: str, value: object) -> bool:
    if kind.startswith("[") and kind.endswith("]"):
        return isinstance(value, list) and all(_conforms(kind[1:-1], item) for item in value)
    if kind.endswith("?"):
        return value is None or _conforms(kind[:-1], value)
    if kind in _CONTRACT.get("records", {}):
        return _mismatch(_CONTRACT["records"][kind], value) is None
    if kind in _CONTRACT["enums"]:
        return value in _CONTRACT["enums"][kind]
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


def event_line(event: str, payload: dict) -> str:
    """A line the Python half emits to the app on its own (not as a reply): ``payload`` must have exactly the
    fields ``bridge_contract.json`` declares for ``event``; anything else is a bug and raises."""
    declared = _CONTRACT.get("events", {}).get(event)
    if declared is None:
        raise LookupError(f"{event!r} is not a bridge event")
    why = _mismatch(declared["fields"], payload)
    if why:
        raise TypeError(f"event {event}: {why}")
    return json.dumps(payload)


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
    """The interpreter facts the app shows, as JSON (FTS5 backs session search; python.org's SQLite lacks it).

    The core's version on a phone is its release date: get_version_info() answers "unknown" there, with no
    install stamp and no git (F13, DC-M19).
    """
    import sqlite3

    from hermes_cli import __release_date__  # the core is importable only after prepare()

    try:
        sqlite3.connect(":memory:").execute("CREATE VIRTUAL TABLE probe USING fts5(text)")
        fts5 = True
    except sqlite3.OperationalError:
        fts5 = False
    return json.dumps({"python": sys.version.split()[0], "sqlite": sqlite3.sqlite_version, "fts5": fts5,
                       "executable": sys.executable, "core_release": __release_date__})


def secrets_changed(_arg: str) -> str:
    """Re-pull the keystore's secrets after the app changed one; variables it no longer holds are revoked."""
    from tui_gateway.embedded import reload_secrets

    reload_secrets(os.environ["HERMES_HOME"])
    return "{}"


def save_custom_endpoint(arg: str) -> str:
    """The app's call into :func:`hermes_android.endpoints.save`."""
    from hermes_android import endpoints

    return json.dumps(endpoints.save(**json.loads(arg)))


def list_custom_endpoints(_arg: str) -> str:
    """The app's call into :func:`hermes_android.endpoints.list_saved` and :func:`~hermes_android.endpoints.named_keys`."""
    from hermes_android import endpoints

    return json.dumps({"endpoints": endpoints.list_saved(), "key_envs": endpoints.named_keys()})


def delete_custom_endpoint(arg: str) -> str:
    """The app's call into :func:`hermes_android.endpoints.delete`."""
    from hermes_android import endpoints

    return json.dumps(endpoints.delete(json.loads(arg)["id"]))


def probe_endpoint(arg: str) -> str:
    """The app's call into :func:`hermes_android.probing.probe`."""
    from hermes_android import probing

    request = json.loads(arg)
    return json.dumps(probing.probe(request["address"], request.get("api_key")))


def discover_endpoints(arg: str) -> str:
    """The app's call into :func:`hermes_android.discovery.discover` ("Search this network")."""
    from hermes_android import discovery

    cidr = json.loads(arg)["cidr"]
    try:
        discovery.hosts(cidr)
    except ValueError:
        return json.dumps({"error": "This network's address range could not be read."})
    return json.dumps({"found": discovery.discover(cidr)})


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



def terminal_status(arg: str) -> str:
    from hermes_android import ssh

    return ssh.status(arg)


def terminal_local(arg: str) -> str:
    from hermes_android import ssh

    return ssh.local(arg)

def telegram_configure(arg: str) -> str:
    from hermes_android import telegram

    return telegram.configure(arg)


def telegram_status(arg: str) -> str:
    from hermes_android import telegram

    return telegram.status(arg)


def telegram_check(arg: str) -> str:
    from hermes_android import telegram

    return telegram.check(arg)


def telegram_enable(arg: str) -> str:
    from hermes_android import telegram

    return telegram.enable(arg)


def cron_update(arg: str) -> str:
    from hermes_android import cron

    return cron.update(arg)


def cron_trigger(arg: str) -> str:
    from hermes_android import cron

    return cron.trigger(arg)


def cron_runs(arg: str) -> str:
    from hermes_android import cron

    return cron.runs(arg)


def cron_delivery_targets(arg: str) -> str:
    from hermes_android import cron

    return cron.delivery_targets(arg)


def cron_scheduler(arg: str) -> str:
    from hermes_android import cron

    return cron.scheduler(arg)


def cron_run_owned(arg: str) -> str:
    from hermes_android import cron

    return cron.run_owned(arg)


def settings_read(arg: str) -> str:
    from hermes_android import settings

    return settings.read(arg)


def settings_write(arg: str) -> str:
    from hermes_android import settings

    return settings.write(arg)


def skills_list(arg: str) -> str:
    from hermes_android import skills

    return skills.listing(arg)


def skills_enable(arg: str) -> str:
    from hermes_android import skills

    return skills.enable(arg)


def session_export(arg: str) -> str:
    from hermes_android import export

    return export.export(arg)


def skills_archived(arg: str) -> str:
    from hermes_android import skills

    return skills.archived(arg)


def skills_restore(arg: str) -> str:
    from hermes_android import skills

    return skills.restore(arg)


def hub_official(arg: str) -> str:
    from hermes_android import hub

    return hub.official(arg)


def hub_scan(arg: str) -> str:
    from hermes_android import hub

    return hub.scan(arg)


def toolsets_keys(arg: str) -> str:
    from hermes_android import toolsets

    return toolsets.keys(arg)


def backup_create(arg: str) -> str:
    from hermes_android import backup

    return backup.create(arg)


def backup_inspect(arg: str) -> str:
    from hermes_android import backup

    return backup.inspect(arg)


def backup_restore(arg: str) -> str:
    from hermes_android import backup

    return backup.restore(arg)
