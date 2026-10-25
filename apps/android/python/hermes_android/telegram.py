"""The phone's own Telegram bot for the always-on gateway (D11, D24).

The token is a secret the app keeps in the keystore as ``TELEGRAM_BOT_TOKEN``; this process reads it from
the environment, where the ``android_keystore`` secret source puts it (D8). Who may talk to the bot is one
numeric Telegram user id, written to config.yaml as ``telegram.allow_from``, which the Telegram adapter
turns into its allowlist: messages from anyone else are ignored. Each function takes and returns JSON;
an ``{"error"}`` answer is for the user.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request

API = "https://api.telegram.org"


def configure(arg: str) -> str:
    """Allows ``{"allowed_user"}`` (a numeric Telegram user id) to talk to the bot."""
    user = str(json.loads(arg).get("allowed_user", "")).strip()
    if not user.isdigit() or int(user) <= 0:
        return json.dumps({"error": "Enter your numeric Telegram user id (@userinfobot shows it)."})
    from hermes_cli.config import load_config, save_config

    config = load_config()
    section = config.get("telegram")
    config["telegram"] = {**(section if isinstance(section, dict) else {}), "allow_from": [user]}
    save_config(config)
    return json.dumps({"allowed_user": user})


def status(_arg: str) -> str:
    """Whether a token is held, who may talk to the bot, and whether this build carries the Telegram SDK
    (``sdk``: the app cannot install it later, so a payload without it has no Telegram)."""
    import pm.extras
    from agent.secret_scope import get_secret_str
    from hermes_cli.config import load_config

    section = load_config().get("telegram")
    allowed = section.get("allow_from") if isinstance(section, dict) else None
    user = str(allowed[0]) if isinstance(allowed, list) and allowed else None
    return json.dumps({"token": get_secret_str("TELEGRAM_BOT_TOKEN").strip() != "", "allowed_user": user,
                       "sdk": pm.extras.available("telegram")})


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
