"""Custom OpenAI-compatible endpoints the app adds, as ``providers.<id>`` entries in config.yaml.

The entry is the one the Desktop's custom-endpoint settings write, ``key_env`` name included, so the
core resolves it like any named provider. The key itself never reaches config.yaml or ``.env``: the
app keeps it in the Android keystore under ``key_env``, and the ``android_keystore`` secret source
exports it.
"""

from __future__ import annotations

import json
import re
import socket
import urllib.error
import urllib.parse
import urllib.request


def save_custom_endpoint(arg: str) -> str:
    """``{id, name, base_url, model, has_key}`` -> ``{id, key_env}`` (``key_env`` is ``""`` for a keyless endpoint),
    or ``{error}`` in words the app shows when the request is incomplete. ``id`` names the saved endpoint an
    edit replaces (null for a new one)."""
    from hermes_cli.config import custom_endpoint_key_env, load_config, save_config

    request = json.loads(arg)
    name = str(request.get("name") or "").strip()
    base_url = str(request.get("base_url") or "").strip().rstrip("/")
    model = str(request.get("model") or "").strip()
    parsed = urllib.parse.urlparse(base_url)
    if not name or not model or parsed.scheme not in ("http", "https") or not parsed.netloc:
        return json.dumps({"error": "An endpoint needs a name, a model and an http(s) base URL."})

    cfg = load_config()
    current = cfg.get("providers")
    providers = current if isinstance(current, dict) else {}
    given_id = str(request.get("id") or "").strip()
    # An edit keeps its id (and so its key's variable name), whatever the new name; a new endpoint takes
    # the Desktop's id rule (hermes_cli/web_routers/config_env.py::_custom_endpoint_id).
    endpoint_id = given_id if given_id in providers else (
        re.sub(r"[^A-Za-z0-9_-]+", "-", name).strip("-_").lower() or "custom")
    existing = providers.get(endpoint_id) if isinstance(providers.get(endpoint_id), dict) else {}
    if request.get("has_key"):
        key_env = custom_endpoint_key_env(endpoint_id)  # the new key goes to this endpoint's own variable
    elif endpoint_id == given_id:
        # An edit with no new key keeps the variable its key already lives in, whichever that is.
        key_env = str(existing.get("key_env") or "")
    else:
        key_env = ""

    # Merged, not replaced: an entry may carry hand-written keys the app has no field for.
    entry = dict(existing)
    models = dict(entry.get("models") or {})
    models.setdefault(model, {})
    entry.update({"name": name, "base_url": base_url, "model": model, "models": models})
    if key_env:
        entry["key_env"] = key_env
    else:
        entry.pop("key_env", None)
    providers[endpoint_id] = entry
    cfg["providers"] = providers
    save_config(cfg)
    return json.dumps({"id": endpoint_id, "key_env": key_env})


def list_saved() -> str:
    """``{endpoints: [{id, name, base_url, model, has_key}]}``: the ``providers.*`` entries this app's form writes."""
    from hermes_cli.config import load_config

    providers = load_config().get("providers")
    rows = []
    for endpoint_id, entry in (providers.items() if isinstance(providers, dict) else []):
        if isinstance(entry, dict) and entry.get("base_url"):
            rows.append({"id": str(endpoint_id), "name": str(entry.get("name") or endpoint_id),
                         "base_url": str(entry["base_url"]), "model": str(entry.get("model") or ""),
                         "has_key": bool(entry.get("key_env"))})
    return json.dumps({"endpoints": rows})


def _names_endpoint(provider: object, endpoint_id: str, entry: dict) -> bool:
    """True when ``model.provider`` names this endpoint: its key, or ``custom:<key>`` / ``custom:<lowercased
    name>`` as the core's model switch spells it. Mirrors the Desktop's
    ``hermes_cli/web_routers/config_env.py::_model_names_provider`` (that module pulls in the web server)."""
    names = {endpoint_id.strip().lower()}
    if str(entry.get("name") or "").strip():
        names.add(str(entry["name"]).strip().lower())
    return str(provider or "").strip().lower().removeprefix("custom:") in names


def delete(arg: str) -> str:
    """``{id}`` -> ``{key_env, was_default}``: the entry is gone. ``key_env`` is the keystore variable the app
    deletes: this endpoint's own (``HERMES_CUSTOM_<ID>_API_KEY``), or null when the entry kept its key
    elsewhere (a hand-written entry may point at another provider's variable, which is not this endpoint's
    to delete), as the Desktop's delete removes only ``custom_endpoint_key_env(id)``. A default model that
    used the endpoint loses its provider, base URL and key fields, as the Desktop's
    ``_detach_main_model_from_provider`` does, so the core no longer routes to the deleted URL."""
    from hermes_cli.config import custom_endpoint_key_env, load_config, save_config

    endpoint_id = str(json.loads(arg).get("id") or "")
    cfg = load_config()
    providers = cfg.get("providers") if isinstance(cfg.get("providers"), dict) else {}
    entry = providers.pop(endpoint_id, None)
    if not isinstance(entry, dict):
        return json.dumps({"error": "That endpoint is no longer saved."})
    model = cfg.get("model") if isinstance(cfg.get("model"), dict) else {}
    was_default = _names_endpoint(model.get("provider"), endpoint_id, entry)
    if was_default:
        for field in ("provider", "base_url", "api_key", "key_env"):
            model.pop(field, None)
        cfg["model"] = model
    cfg["providers"] = providers
    save_config(cfg)
    own = custom_endpoint_key_env(endpoint_id)
    return json.dumps({"key_env": own if entry.get("key_env") == own else None, "was_default": was_default})


KNOWN_PORTS = ((11434, "ollama"), (1234, "lmstudio"), (8080, "llamacpp"), (8000, "vllm"))
# The most telling outcome wins when several candidates fail: a server that answered beats one that did not.
_RANK = {"unreachable": 0, "no_models": 1, "refused": 2, "needs_key": 3}


def _kind(port: int | None) -> str:
    return dict(KNOWN_PORTS).get(port or 0, "server")


def _candidates(address: str, ports) -> list[tuple[str, str]]:
    text = address.strip().rstrip("/")
    if "://" in text:
        return [(text, _kind(urllib.parse.urlparse(text).port))]
    host, sep, port = text.rpartition(":")
    if sep and port.isdigit() and host:
        return [(f"http://{host}:{port}/v1", _kind(int(port)))]
    return [(f"http://{text}:{p}/v1", kind) for p, kind in ports]


def _open(base_url: str, timeout: float) -> bool:
    parsed = urllib.parse.urlparse(base_url)
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        with socket.create_connection((parsed.hostname, port), timeout=min(timeout, 0.5)):
            return True
    except OSError:
        return False


def _toggle_v1(base_url: str) -> str:
    """The same base with ``/v1`` added or removed (``probe_api_models``'s own heuristic): the base a
    server answering only the other form would need."""
    trimmed = base_url.rstrip("/")
    return trimmed[:-3].rstrip("/") if trimmed.endswith("/v1") else trimmed + "/v1"


def _status(base_url: str, api_key: str | None, timeout: float) -> int | None:
    """The HTTP status of ``<base>/models``, or of its ``/v1``-toggled variant when the first is a plain
    404 (None when neither answered): why a probe found no models, even when the server serves only the
    other form of the base URL (a bare base given without ``/v1``, or the reverse)."""
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    status: int | None = None
    for candidate in dict.fromkeys((base_url, _toggle_v1(base_url))):
        try:
            with urllib.request.urlopen(urllib.request.Request(candidate.rstrip("/") + "/models", headers=headers),
                                        timeout=timeout) as resp:
                return resp.status
        except urllib.error.HTTPError as answered:
            answered.close()  # an HTTPError holds the open response
            status = answered.code
            if answered.code != 404:
                return status
        except (urllib.error.URLError, OSError):
            continue
    return status


def probe(address: str, api_key: str | None = None, ports=KNOWN_PORTS, timeout: float = 5.0) -> dict:
    """Finds the OpenAI-compatible server behind ``address`` (a URL, ``host:port`` or a bare host tried on
    ``ports``) and its models, through the core's own probe. ``api_key`` is used for this probe only."""
    from hermes_cli.models import probe_api_models

    outcome = "unreachable"
    for base, kind in _candidates(address, ports):
        if not _open(base, timeout):
            continue
        result = probe_api_models(api_key or None, base, timeout=timeout)
        models = [m for m in result.get("models") or [] if m]
        if models:
            return {"outcome": "found", "base_url": result.get("resolved_base_url") or base, "kind": kind,
                    "models": models}
        status = _status(result.get("resolved_base_url") or base, api_key, timeout)
        tried = {401: "needs_key", 403: "refused", 200: "no_models"}.get(status or 0, "unreachable")
        outcome = max(outcome, tried, key=_RANK.__getitem__)
    return {"outcome": outcome, "base_url": None, "kind": None, "models": []}
