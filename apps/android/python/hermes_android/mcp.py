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

Every config write is one ``_config.mutate`` span (E5): the core's ``save_config`` locks only its own write. The
core is importable only after ``prepare()``, so its imports sit inside the functions.
"""

from __future__ import annotations

import json
import re

BAD_HEADER = "A header name is letters, digits and - only."
WRONG_NAME = "The {header} header of {name} reads its key from {env_var}; nothing was changed."
NO_URL = "Server '{name}' has no URL, so it sends no headers."
# The route's words (hermes_cli/web_routers/mcp.py, PUT /api/mcp/servers/{name}/enabled).
NOT_FOUND = "Server '{name}' not found"
PLUGIN = "Server '{name}' is provided by plugin '{plugin}' and cannot be modified"
MALFORMED = "Malformed server config"

HEADER = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}")  # an HTTP token (RFC 9110 field-name)


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
