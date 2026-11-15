"""The settings ``config.set`` refuses, read and written by the app's own bridge pair (batch-3 F3, F4, bet 7; D26).

``config.set`` answers 4002 "unknown config key" for ``approvals.timeout``, ``command_allowlist`` and ``memory.*``
(``tui_gateway/methods_config_set.py:504-519``), so ``settings_read`` / ``settings_write`` carry exactly those keys,
each a declared field: a closed key set, never a free key/value write from the UI (DC-M15). The personality and
``approvals.mode`` stay on ``config.set``, the core's own owners (``persist_personality``); this module only lists the
personalities the core offers, since no getter returns the built-ins. Every write is one ``_config.mutate`` under the
app's config lock (E5), never ``.env``.

The always-allowed commands live twice: in config.yaml and in each process's governing set. A removal edits the file
under the lock and then, after the lock, calls the core's only public reconciling function,
``tools.approval.save_permanent_allowlist(set())``, which makes this process stop honouring the removed patterns
(DC-M16); it does its own load and save, so it never runs inside the mutate. The ``:gateway`` process keeps its own set
and follows at its next start (Dv31).

The core is importable only after ``prepare()``, so the core imports sit inside the functions.
"""

from __future__ import annotations

import json

from hermes_android import _config

TIMEOUT_S = 300  # tools/approval_context.py's fallback
LIMITS = {"memory_char_limit": 2200, "user_char_limit": 1375}  # agent/agent_init.py's defaults
SWITCHES = ("memory_enabled", "user_profile_enabled")
_MISSING = object()


def read(_arg: str) -> str:
    """What the core will act on, from one config load (D5)."""
    from hermes_cli.config import load_config
    from hermes_cli.personality import available_personalities, describe_personality
    from tools.memory_tool import get_builtin_memory_store_flags

    cfg = load_config()
    memory_on, profile_on = get_builtin_memory_store_flags(cfg)
    memory = _section(cfg, "memory")
    return json.dumps({
        "approvals_timeout_s": _integer(_section(cfg, "approvals").get("timeout"), TIMEOUT_S),
        "command_allowlist": _patterns(cfg) or [],
        "memory_enabled": bool(memory_on),
        "user_profile_enabled": bool(profile_on),
        **{key: _integer(memory.get(key), default) for key, default in LIMITS.items()},
        "personalities": [{"name": name, "description": describe_personality(definition)}
                          for name, definition in available_personalities(cfg).items()],
    })


def write(arg: str) -> str:
    """Changes only the edit's non-null fields and takes ``allowlist_remove`` out of the allowlist; ``applied`` says
    whether config.yaml changed (D2). A refused value writes nothing, the reconcile included."""
    edit = json.loads(arg)
    timeout = edit["approvals_timeout_s"]
    if timeout is not None and timeout < 1:
        return json.dumps({"error": "The approval timeout must be at least 1 second."})
    if any(edit[key] is not None and edit[key] < 0 for key in LIMITS):
        return json.dumps({"error": "A memory size limit cannot be negative."})
    changed = _config.mutate(lambda cfg: _apply(cfg, edit))
    if edit["allowlist_remove"]:  # after the lock, even when the file no longer held them (D3, D4)
        from tools.approval import save_permanent_allowlist

        save_permanent_allowlist(set())
    return json.dumps({"applied": changed})


def _apply(cfg: dict, edit: dict) -> bool:
    """The edit itself, on a loaded config (no I/O): each section's other keys are kept. Whether anything changed."""
    changed = False
    if edit["approvals_timeout_s"] is not None:
        changed |= _set(cfg, "approvals", "timeout", edit["approvals_timeout_s"])
    for key in (*SWITCHES, *LIMITS):
        if edit[key] is not None:
            changed |= _set(cfg, "memory", key, edit[key])
    removed = set(edit["allowlist_remove"])
    patterns = _patterns(cfg)
    if removed and patterns is not None:  # a malformed list is left as it is (D6)
        kept = [pattern for pattern in patterns if pattern not in removed]
        if kept != patterns:
            cfg["command_allowlist"] = kept
            changed = True
    return changed


def _set(cfg: dict, name: str, key: str, value: object) -> bool:
    section = _section(cfg, name)
    if section.get(key, _MISSING) == value:
        return False
    cfg[name] = {**section, key: value}
    return True


def _section(cfg: dict, name: str) -> dict:
    section = cfg.get(name)
    return section if isinstance(section, dict) else {}


def _integer(value: object, default: int) -> int:
    return value if isinstance(value, int) and not isinstance(value, bool) else default


def _patterns(cfg: dict) -> list[str] | None:
    """``command_allowlist`` when it is a list of strings, else None (the core then reads it as empty)."""
    patterns = cfg.get("command_allowlist")
    if isinstance(patterns, list) and all(isinstance(pattern, str) for pattern in patterns):
        return list(patterns)
    return None
