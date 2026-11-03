"""Custom OpenAI-compatible endpoints the app adds, as ``providers.<id>`` entries in config.yaml.

The entry is the one the Desktop's custom-endpoint settings write, ``key_env`` name included, so the
core resolves it like any named provider. The key itself never reaches config.yaml or ``.env``: the
app keeps it in the Android keystore under ``key_env``, and the ``android_keystore`` secret source
exports it.
"""

from __future__ import annotations

import re
import urllib.parse


def save(*, id: str | None, name: str, base_url: str, model: str, has_key: bool) -> dict[str, str]:
    """``{id, key_env}`` (``key_env`` is ``""`` for a keyless endpoint), or ``{error}`` in words the app shows
    when the request is incomplete. ``id`` names the saved endpoint an edit replaces (None for a new one)."""
    from hermes_cli.config import (coerce_provider_id, custom_endpoint_key_env, find_provider_entry, load_config,
                                   save_config)

    name = name.strip()
    base_url = base_url.strip().rstrip("/")
    model = model.strip()
    parsed = urllib.parse.urlparse(base_url)
    if not name or not model or parsed.scheme not in ("http", "https") or not parsed.netloc:
        return {"error": "An endpoint needs a name, a model and an http(s) base URL."}

    cfg = load_config()
    current = cfg.get("providers")
    providers = current if isinstance(current, dict) else {}
    given_id = (id or "").strip()
    # An edit keeps its id (and so its key's variable name), whatever the new name; the entry is found by the
    # core's provider-key rule, so a hand-written `2070:` (an int key) is the entry "2070" edits. A new
    # endpoint takes the Desktop's id rule (hermes_cli/web_routers/config_env.py::_custom_endpoint_id).
    stored, existing = find_provider_entry(providers, given_id)
    editing = existing is not None
    if editing:
        endpoint_id = coerce_provider_id(stored)
    else:
        endpoint_id = re.sub(r"[^A-Za-z0-9_-]+", "-", name).strip("-_").lower() or "custom"
        stored, existing = find_provider_entry(providers, endpoint_id)  # adding under a saved name merges
    existing = existing if isinstance(existing, dict) else {}
    if has_key:
        key_env = custom_endpoint_key_env(endpoint_id)  # the new key goes to this endpoint's own variable
    elif editing:
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
    if stored is not None and stored != endpoint_id:
        providers.pop(stored)  # re-keyed once under its string spelling, as the Desktop's save does: no twin
    providers[endpoint_id] = entry
    cfg["providers"] = providers
    save_config(cfg)
    return {"id": endpoint_id, "key_env": key_env}


def list_saved() -> list[dict[str, object]]:
    """``[{id, name, base_url, model, has_key}]``: the ``providers.*`` entries this app's form writes, each under
    its string identity and only as the entry the core's lookup finds for it (two stored keys with one identity,
    ``2070:`` and ``'2070':``, list once). Narrowed by design (D32): only the ``base_url`` shape this app writes;
    not the Desktop's ``url``/``api`` aliases or legacy ``custom_providers:`` rows."""
    from hermes_cli.config import coerce_provider_id, find_provider_entry, load_config

    providers = load_config().get("providers")
    rows = []
    for stored, entry in (providers.items() if isinstance(providers, dict) else []):
        endpoint_id = coerce_provider_id(stored)
        if (endpoint_id and isinstance(entry, dict) and entry.get("base_url")
                and find_provider_entry(providers, endpoint_id)[1] is entry):
            rows.append({"id": endpoint_id, "name": str(entry.get("name") or endpoint_id),
                         "base_url": str(entry["base_url"]), "model": str(entry.get("model") or ""),
                         "has_key": bool(entry.get("key_env"))})
    return rows


def _names_endpoint(provider: object, endpoint_id: str, entry: dict) -> bool:
    """True when ``model.provider`` names this endpoint: its key, or ``custom:<key>`` / ``custom:<lowercased
    name>`` as the core's model switch spells it. Mirrors the Desktop's
    ``hermes_cli/web_routers/config_env.py::_model_names_provider`` (that module pulls in the web server)."""
    from hermes_cli.config import coerce_provider_id

    names = {coerce_provider_id(endpoint_id).lower()}
    if coerce_provider_id(entry.get("name")):
        names.add(coerce_provider_id(entry.get("name")).lower())
    return coerce_provider_id(provider).lower().removeprefix("custom:") in names


def delete(endpoint_id: str) -> dict[str, object]:
    """``{key_env, was_default}``: the entry is gone. ``key_env`` is the keystore variable the app deletes,
    always this endpoint's own slot (``HERMES_CUSTOM_<ID>_API_KEY``), whatever ``key_env`` the entry held (a
    hand-written entry may point at another provider's variable, which is not this endpoint's to delete), as
    the Desktop's delete removes only ``custom_endpoint_key_env(id)``. A default model that
    used the endpoint loses its provider, base URL and key fields, as the Desktop's
    ``_detach_main_model_from_provider`` does, so the core no longer routes to the deleted URL."""
    from hermes_cli.config import (coerce_provider_id, custom_endpoint_key_env, find_provider_entry, load_config,
                                   save_config)

    cfg = load_config()
    providers = cfg.get("providers") if isinstance(cfg.get("providers"), dict) else {}
    stored, entry = find_provider_entry(providers, endpoint_id)  # the core's provider-key rule, as for an edit
    if entry is None:
        return {"error": "That endpoint is no longer saved."}
    provider_key = coerce_provider_id(stored)
    providers.pop(stored)
    model = cfg.get("model") if isinstance(cfg.get("model"), dict) else {}
    was_default = _names_endpoint(model.get("provider"), provider_key, entry)
    if was_default:
        for field in ("provider", "base_url", "api_key", "key_env"):
            model.pop(field, None)
        cfg["model"] = model
    cfg["providers"] = providers
    save_config(cfg)
    # Only the endpoint's own slot is named for deletion, whatever key_env the entry held (as Desktop's delete):
    # another provider's variable is never this endpoint's, and its own slot may outlive a keyless re-save.
    return {"key_env": custom_endpoint_key_env(provider_key), "was_default": was_default}
