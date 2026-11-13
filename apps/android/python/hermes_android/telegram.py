"""The phone's own Telegram bot for the always-on gateway (D11, D24).

The token is a secret the app keeps in the keystore as ``TELEGRAM_BOT_TOKEN``; this process reads it from
the environment, where the ``android_keystore`` secret source puts it (D8). Who may talk to the bot is one
numeric Telegram user id, written to config.yaml as ``telegram.allow_from``, which the Telegram adapter
turns into its allowlist: messages from anyone else are ignored. Each function takes and returns JSON;
an ``{"error"}`` answer is for the user.

Switching the bot off or on (D39, blueprint §3.2) is config.yaml's ``platforms.telegram.enabled``, written
under the app's config lock (``_config.mutate``), never the dotenv file: an explicit false beats a held token, so the
token and the allowed user stay, and the gateway applies it at its next start. The bot's live state is the
core's own entry in ``gateway_state.json`` (``gateway.status.read_runtime_status``), passed on as written,
with the pid that wrote it; whether it describes the running gateway is the app's call, by ``writer_pid``
against its own gateway record.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request

from hermes_android import _config

API = "https://api.telegram.org"


def configure(arg: str) -> str:
    """Allows ``{"allowed_user"}`` (a numeric Telegram user id) to talk to the bot."""
    user = str(json.loads(arg).get("allowed_user", "")).strip()
    if not user.isdigit() or int(user) <= 0:
        return json.dumps({"error": "Enter your numeric Telegram user id (@userinfobot shows it)."})
    _config.mutate(lambda cfg: _allow(cfg, user))
    return json.dumps({"allowed_user": user})


def _allow(cfg: dict, user: str) -> None:
    """The edit itself, on a loaded config (no I/O): the section's other keys are kept."""
    section = cfg.get("telegram")
    cfg["telegram"] = {**(section if isinstance(section, dict) else {}), "allow_from": [user]}


def enable(arg: str) -> str:
    """Switches the bot ``{"enabled"}`` on or off in config.yaml, under the app's config lock."""
    on = json.loads(arg)["enabled"]
    return json.dumps({"enabled": _config.mutate(lambda cfg: _switch(cfg, on))})


def _switch(cfg: dict, on: bool) -> bool:
    """The edit itself, on a loaded config (no I/O): every other platform and key of the section are kept."""
    platforms = cfg.get("platforms")
    platforms = platforms if isinstance(platforms, dict) else {}
    section = platforms.get("telegram")
    section = section if isinstance(section, dict) else {}
    cfg["platforms"] = {**platforms, "telegram": {**section, "enabled": on}}
    return on


def status(_arg: str) -> str:
    """Whether a token is held, who may talk to the bot, whether this build carries the Telegram SDK
    (``sdk``: the app cannot install it later, so a payload without it has no Telegram), whether config.yaml
    lets the bot run (``enabled``, the gateway's own reading: absent leaves it to the token), and the core's
    last entry for the bot (``live``)."""
    import pm.extras
    from agent.secret_scope import get_secret_str
    from gateway.status import read_runtime_status
    from hermes_cli.config import load_config
    from utils import is_truthy_value

    config = load_config()
    section = config.get("telegram")
    allowed = section.get("allow_from") if isinstance(section, dict) else None
    user = str(allowed[0]) if isinstance(allowed, list) and allowed else None
    platforms = config.get("platforms")
    switch = platforms.get("telegram") if isinstance(platforms, dict) else None
    switch = switch if isinstance(switch, dict) else {}
    return json.dumps({"token": get_secret_str("TELEGRAM_BOT_TOKEN").strip() != "", "allowed_user": user,
                       "sdk": pm.extras.available("telegram"),
                       "enabled": True if "enabled" not in switch else is_truthy_value(switch["enabled"], default=False),
                       "live": _live(read_runtime_status())})


def _live(runtime: object) -> dict | None:
    """The core's entry for the bot as written, each field only when of its declared type (the file is
    another process's); the error leaves redacted, since an adapter's message may carry the Bot API URL."""
    platforms = runtime.get("platforms") if isinstance(runtime, dict) else None
    entry = platforms.get("telegram") if isinstance(platforms, dict) else None
    if not isinstance(entry, dict):
        return None
    from agent.redact import redact_sensitive_text

    state, error, pid, updated = (entry.get(key) for key in ("state", "error_message", "writer_pid", "updated_at"))
    return {"state": state if isinstance(state, str) else None,
            "error_message": redact_sensitive_text(error, force=True, redact_url_credentials=True)
            if isinstance(error, str) else None,
            "writer_pid": pid if isinstance(pid, int) and not isinstance(pid, bool) else None,
            "updated_at": updated if isinstance(updated, str) else None}


def check(_arg: str) -> str:
    """Asks Telegram whose bot the token is (``getMe``), so the user sees the token works before relying on it."""
    from agent.secret_scope import get_secret_str

    token = get_secret_str("TELEGRAM_BOT_TOKEN").strip()
    if not token:
        return json.dumps({"error": "No bot token yet: paste the one @BotFather gave you."})
    try:
        with urllib.request.urlopen(f"{API}/bot{token}/getMe", timeout=15) as response:
            bot = json.load(response)["result"]
    except urllib.error.HTTPError as answered:  # its message names the status, never the URL (the token is in it)
        if answered.code in (401, 404):  # Telegram's answers for a token it does not know
            return _checked("refused", f"Telegram refused the token ({answered.code}): check it with @BotFather.")
        return _checked("unreachable", f"Telegram answered {answered.code}; the token was kept, check it again later.")
    except (urllib.error.URLError, OSError, ValueError, KeyError):
        return _checked("unreachable", "Telegram could not be reached, so the token was kept unchecked; "
                                       "check the phone's connection and try again.")
    return json.dumps({"check": "ok", "bot": f"@{bot['username']}", "message": None})


def _checked(outcome: str, message: str) -> str:
    return json.dumps({"check": outcome, "bot": None, "message": message})
