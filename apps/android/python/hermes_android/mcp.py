"""The MCP calls the core protocol has no safe method for (rows capabilities-15..19; batch-5 F1, F6, F9, F12, F15;
D26, Dv32).

``catalog`` reproduces ``GET /api/mcp/catalog`` (``hermes_cli/web_routers/mcp.py``) in-process: the curated
manifests bundled beside the core, read by ``list_catalog()`` with no network and no MCP SDK, each with the env
values it needs (names and prompts, never a value) and the tool defaults ``mcp.catalog`` drops. The configured
servers are read once per call, not twice per entry as the route does (D9). ``set_enabled`` reproduces
``PUT /api/mcp/servers/{name}/enabled``: one ``enabled`` key, refused in the route's words for a plugin's server,
an unknown one or a malformed entry (F9). ``key_ref`` points one header of a remote server at a keystore variable
by writing only its ``${VAR}`` template (F1): the core's ``mcp.servers.set_api_key`` and ``mcp.servers.add`` with a
``bearer_token`` save the value to ``.env`` (``save_env_value``), which the app never does (D8, D50), so neither is
called. The variable must be the name the core derives for that server and header (D3): the process env the core's
``${VAR}`` expansion reads also holds every provider key and the SSH and Telegram secrets, and a header must never
send one of them. Since this is the only app writer of ``headers``, a backup keeps holding templates only (F15).

The pip job installs one Python package for a stdio server on the user's tap (F18, D55 row 8): only a PyPI name with
an optional ``==`` pin reaches ``pip``, which runs as a child of the app's launcher (``$HOME/bin/python3``) with
``--isolated`` (no user config or ``PIP_*`` variable can swap the index), wheels only, into a staging directory
that replaces ``HERMES_HOME/mcp-packages/<server>`` whole once pip succeeds. The child gets HOME, PATH, TMPDIR and
LANG and nothing else: no keystore variable reaches it. One job runs at a time; the calls only start, look and
kill, so neither the frames' thread nor the network thread waits on pip. Desktop has no counterpart (it runs
``uvx``).

Every config write is one ``_config.mutate`` span (E5): the core's ``save_config`` locks only its own write. The
core is importable only after ``prepare()``, so its imports sit inside the functions.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import threading
from dataclasses import dataclass
from pathlib import Path

BAD_REQUIREMENT = "Only a package name from PyPI, optionally with ==version, can be installed."
BAD_SERVER = "A server name is letters, digits, '.', '_' or '-' (at most 64) and does not start with '.'."
NO_LAUNCHER = "Python packages cannot be installed here: this install has no Python launcher."
BUSY = "Another package is still installing. Wait for it or cancel it."
UNKNOWN_JOB = "That install is not known to the app any more."

BAD_HEADER = "A header name is letters, digits and - only."
WRONG_NAME = "The {header} header of {name} reads its key from {env_var}; nothing was changed."
NO_URL = "Server '{name}' has no URL, so it sends no headers."
# The route's words (hermes_cli/web_routers/mcp.py, PUT /api/mcp/servers/{name}/enabled).
NOT_FOUND = "Server '{name}' not found"
PLUGIN = "Server '{name}' is provided by plugin '{plugin}' and cannot be modified"
MALFORMED = "Malformed server config"

HEADER = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")  # an HTTP token (RFC 9110 field-name)
# A PEP 508 name with an optional exact pin: no whitespace, extras, markers, url, path, option or wildcard.
REQUIREMENT = re.compile(r"([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9._-]*[A-Za-z0-9])(==[0-9][0-9A-Za-z.!+_-]*)?")
# Desktop's server-name rule, and never '.'-first: the name is a directory under mcp-packages/ (D6).
SERVER = re.compile(r"[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}")
TAIL = 2000  # characters of pip's output a poll answers
CHILD_ENV = ("HOME", "PATH", "TMPDIR", "LANG")


def catalog(_arg: str) -> str:
    """``{"entries"}``: one record per valid bundled manifest, in the core's order."""
    from hermes_cli.mcp_catalog import installed_servers, list_catalog, server_enabled

    servers = installed_servers()
    entries = []
    for entry in list_catalog():
        configured = servers.get(entry.name)
        entries.append({
            "name": entry.name,
            "description": entry.description or "",
            "url": entry.transport.url,
            "transport": entry.transport.type,
            "auth": entry.auth.type,
            "env": [{"name": v.name, "prompt": v.prompt, "required": v.required, "secret": v.secret}
                    for v in entry.auth.env],
            "include": _names(entry.tools.default_enabled),
            "exclude": _names(entry.tools.default_excluded),
            "installed": entry.name in servers,
            "enabled": isinstance(configured, dict) and server_enabled(configured),
            "connector_slug": entry.connector_slug,
        })
    return json.dumps({"entries": entries})


def _names(tools: list[str] | None) -> list[str] | None:
    return None if tools is None else [str(tool) for tool in tools]


def set_enabled(arg: str) -> str:
    """``{"enabled"}`` as the server is left, or ``{"error"}`` in the route's words; a switch already in place
    writes nothing."""
    from hermes_android import _config
    from tools.mcp_tool_common import mcp_server_enabled

    request = json.loads(arg)
    name, on = request["name"], request["enabled"]
    if refused := _plugin_refusal(name):
        return json.dumps({"error": refused})

    def switch(cfg: dict) -> dict:
        entry, refused = _entry(cfg, name)
        if refused:
            return {"error": refused}
        if mcp_server_enabled(entry) != on:
            entry["enabled"] = bool(on)
        return {"enabled": mcp_server_enabled(entry)}

    return json.dumps(_config.mutate(switch))


def key_ref(arg: str) -> str:
    """``{"headers"}``: the server's header names once ``header`` reads ``env_var``; ``{"error"}`` before any write
    when the header, the name or the server does not fit."""
    from hermes_android import _config

    request = json.loads(arg)
    name, header, env_var = request["name"], request["header"], request["env_var"]
    if not HEADER.fullmatch(header):
        return json.dumps({"error": BAD_HEADER})
    authorization = header.lower() == "authorization"
    derived = _derived_key(name, header, authorization)
    if env_var != derived:
        return json.dumps({"error": WRONG_NAME.format(header=header, name=name, env_var=derived)})
    if refused := _plugin_refusal(name):
        return json.dumps({"error": refused})

    def point(cfg: dict) -> dict:
        entry, refused = _entry(cfg, name)
        if refused:
            return {"error": refused}
        if not entry.get("url"):
            return {"error": NO_URL.format(name=name)}
        old = entry.get("headers")
        headers = {k: v for k, v in (old if isinstance(old, dict) else {}).items() if str(k).lower() != header.lower()}
        headers[header] = f"Bearer ${{{env_var}}}" if authorization else f"${{{env_var}}}"
        entry["headers"] = headers
        return {"headers": sorted(str(k) for k in headers)}

    return json.dumps(_config.mutate(point))


def _derived_key(name: str, header: str, authorization: bool) -> str:
    """The keystore variable a header of ``name`` reads: the core's ``MCP_<NAME>_API_KEY`` for Authorization, else
    ``MCP_<NAME>_<HEADER>`` normalised by the same rule."""
    from hermes_cli.mcp_config import _env_key_for_server

    key = _env_key_for_server(name)
    if authorization:
        return key
    return key.removesuffix("API_KEY") + re.sub(r"[^A-Za-z0-9_]", "_", header.upper()).strip("_")


def _plugin_refusal(name: str) -> str | None:
    """The route's refusal when a plugin provides ``name``. ``server_configs_with_sources`` discovers the plugins
    (I/O), so it runs before the config span, as in the route."""
    from hermes_cli.mcp_config import _get_mcp_servers
    from tui_gateway.mcp_rpc_helpers import server_configs_with_sources

    _servers, plugins = server_configs_with_sources(_get_mcp_servers())
    plugin = plugins.get(name)
    return PLUGIN.format(name=name, plugin=plugin) if plugin else None


def _entry(cfg: dict, name: str) -> tuple[dict, str | None]:
    servers = cfg.get("mcp_servers")
    if not isinstance(servers, dict) or name not in servers:
        return {}, NOT_FOUND.format(name=name)
    if not isinstance(servers[name], dict):
        return {}, MALFORMED
    return servers[name], None


@dataclass
class _Job:
    id: str
    server: str
    proc: subprocess.Popen
    staging: Path
    log: Path
    state: str | None = None  # the final state once the job has ended; None while pip runs


_LOCK = threading.Lock()
_job: _Job | None = None


def pip_start(arg: str) -> str:
    """``{"job"}`` at once once pip is started for ``requirement``; ``{"error"}`` for a refused name or requirement,
    without a launcher, or while another job runs."""
    global _job
    from hermes_android import launcher
    from hermes_constants import get_hermes_home

    request = json.loads(arg)
    server, requirement = request["server"], request["requirement"]
    if not SERVER.fullmatch(server):
        return json.dumps({"error": BAD_SERVER})
    if not REQUIREMENT.fullmatch(requirement):
        return json.dumps({"error": BAD_REQUIREMENT})
    home = os.environ.get("HOME", "")
    if not home or not launcher.available(home):
        return json.dumps({"error": NO_LAUNCHER})
    with _LOCK:
        if _job is not None:
            _reap(_job)
            if _job.state is None:
                return json.dumps({"error": BUSY})
            _job.log.unlink(missing_ok=True)
        packages = get_hermes_home() / "mcp-packages"
        packages.mkdir(parents=True, exist_ok=True)
        for left in packages.glob(".staging-*"):  # a process killed mid-job
            shutil.rmtree(left, ignore_errors=True)
        job = os.urandom(8).hex()
        staging = packages / f".staging-{job}"
        staging.mkdir()
        logs = Path(os.environ.get("TMPDIR") or tempfile.gettempdir()) / "mcp-pip"
        logs.mkdir(parents=True, exist_ok=True)
        log_path = logs / f"{job}.log"
        with open(log_path, "wb") as log:
            proc = subprocess.Popen(
                [str(Path(home) / "bin" / "python3"), "-m", "pip", "install", "--isolated", "--no-input",
                 "--disable-pip-version-check", "--only-binary=:all:", "--target", str(staging), requirement],
                cwd=staging, env={k: os.environ[k] for k in CHILD_ENV if k in os.environ},
                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        _job = _Job(job, server, proc, staging, log_path)
    return json.dumps({"job": job})


def pip_poll(arg: str) -> str:
    """``{"state", "tail"}`` of the job (its package moved into place by the poll that sees pip succeed)."""
    with _LOCK:
        job = _known(json.loads(arg)["job"])
        if job is None:
            return json.dumps({"error": UNKNOWN_JOB})
        _reap(job)
        return json.dumps({"state": job.state or "running", "tail": _tail(job.log)})


def pip_cancel(arg: str) -> str:
    """``{"state"}``: ``cancelled`` once the child is killed and its staging dropped; a finished job's final state."""
    with _LOCK:
        job = _known(json.loads(arg)["job"])
        if job is None:
            return json.dumps({"error": UNKNOWN_JOB})
        _reap(job)
        if job.state is None:
            try:
                os.killpg(job.proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                job.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            shutil.rmtree(job.staging, ignore_errors=True)
            job.state = "cancelled"
        return json.dumps({"state": job.state})


def _known(job_id: str) -> _Job | None:
    return _job if _job is not None and _job.id == job_id else None


def _reap(job: _Job) -> None:
    """Settles an ended job once: on pip's success its staging replaces the server's package dir, else it goes."""
    if job.state is not None:
        return
    code = job.proc.poll()
    if code is None:
        return
    if code == 0:
        target = job.staging.parent / job.server
        try:
            if target.is_symlink() or target.is_file():
                target.unlink()
            elif target.exists():
                shutil.rmtree(target)
            job.staging.rename(target)
            job.state = "done"
            return
        except OSError:
            pass
    shutil.rmtree(job.staging, ignore_errors=True)
    job.state = "failed"


def _tail(log: Path) -> str:
    from agent.redact import redact_sensitive_text

    try:
        with open(log, "rb") as handle:
            handle.seek(max(0, handle.seek(0, os.SEEK_END) - 4 * TAIL))
            text = handle.read().decode("utf-8", errors="replace")[-TAIL:]
    except OSError:
        return ""
    return redact_sensitive_text(text, force=True, redact_url_credentials=True)
