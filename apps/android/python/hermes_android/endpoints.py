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
    """``{name, base_url, model, has_key}`` -> ``{id, key_env}`` (``key_env`` is ``""`` for a keyless endpoint),
    or ``{error}`` in words the app shows when the request is incomplete."""
    from hermes_cli.config import custom_endpoint_key_env, load_config, save_config

    request = json.loads(arg)
    name = str(request.get("name") or "").strip()
    base_url = str(request.get("base_url") or "").strip().rstrip("/")
    model = str(request.get("model") or "").strip()
    parsed = urllib.parse.urlparse(base_url)
    if not name or not model or parsed.scheme not in ("http", "https") or not parsed.netloc:
        return json.dumps({"error": "An endpoint needs a name, a model and an http(s) base URL."})

    # The Desktop's id rule (hermes_cli/web_routers/config_env.py::_custom_endpoint_id).
    endpoint_id = re.sub(r"[^A-Za-z0-9_-]+", "-", name).strip("-_").lower() or "custom"
    key_env = custom_endpoint_key_env(endpoint_id) if request.get("has_key") else ""

    cfg = load_config()
    current = cfg.get("providers")
    providers = current if isinstance(current, dict) else {}
    # Merged, not replaced: an entry may carry hand-written keys the app has no field for.
    entry = dict(providers.get(endpoint_id) or {})
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


def _status(base_url: str, api_key: str | None, timeout: float) -> int | None:
    """The HTTP status of ``<base>/models`` (None when nothing answered): why a probe found no models."""
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    try:
        with urllib.request.urlopen(urllib.request.Request(base_url.rstrip("/") + "/models", headers=headers),
                                    timeout=timeout) as resp:
            return resp.status
    except urllib.error.HTTPError as answered:
        return answered.code
    except (urllib.error.URLError, OSError):
        return None


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
