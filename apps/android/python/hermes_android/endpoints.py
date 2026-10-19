"""Custom OpenAI-compatible endpoints the app adds, as ``providers.<id>`` entries in config.yaml.

The entry is the one the Desktop's custom-endpoint settings write, ``key_env`` name included, so the
core resolves it like any named provider. The key itself never reaches config.yaml or ``.env``: the
app keeps it in the Android keystore under ``key_env``, and the ``android_keystore`` secret source
exports it.
"""

from __future__ import annotations

import json
import re
import urllib.parse


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
