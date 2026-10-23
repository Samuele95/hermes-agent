"""The phone's own Telegram bot for the always-on gateway (D11, D24).

The bot token is a secret: the app keeps it in the keystore as ``TELEGRAM_BOT_TOKEN``, and both processes
read it through the ``android_keystore`` secret source, never from ``.env`` (D8). Who may talk to the bot
is one numeric Telegram user id, a non-secret setting in config.yaml (``telegram.allow_from``) that the
Telegram adapter turns into its allowlist.
"""

from __future__ import annotations

import importlib
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

telegram = importlib.import_module("hermes_android.telegram")  # apps/android/python, put on sys.path by conftest

TOKEN = "123456:AAH-secret-token-for-tests"


@pytest.fixture
def home():
    from hermes_constants import get_hermes_home

    path = get_hermes_home()  # the hermetic conftest's per-test home
    path.mkdir(parents=True, exist_ok=True)
    return path


def test_the_allowed_user_becomes_the_gateways_telegram_allowlist(home, monkeypatch):
    monkeypatch.delenv("TELEGRAM_ALLOWED_USERS", raising=False)
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)

    assert json.loads(telegram.configure(json.dumps({"allowed_user": " 987654321 "}))) == {"allowed_user": "987654321"}

    from gateway.config import Platform, load_gateway_config
    config = load_gateway_config()
    assert config.platforms[Platform.TELEGRAM].enabled
    assert os.environ["TELEGRAM_ALLOWED_USERS"] == "987654321"


@pytest.mark.parametrize("given", ["", "@someone", "12ab", "-100123", "0"])
def test_only_a_numeric_user_id_is_accepted(home, given):
    answer = json.loads(telegram.configure(json.dumps({"allowed_user": given})))

    assert "error" in answer
    assert not (home / "config.yaml").exists()


def test_configuring_keeps_other_settings_and_writes_no_dotenv(home):
    (home / "config.yaml").write_text("model:\n  default: m1\ntelegram:\n  require_mention: true\n", encoding="utf-8")

    telegram.configure(json.dumps({"allowed_user": "42"}))

    from hermes_cli.config import load_config
    config = load_config()
    assert config["model"]["default"] == "m1"
    assert config["telegram"]["require_mention"] is True and config["telegram"]["allow_from"] == ["42"]
    assert not (home / ".env").exists()


def test_status_reports_the_allowed_user_and_whether_a_token_is_held(home, monkeypatch):
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)
    assert json.loads(telegram.status("")) | {"sdk": None} == {"token": False, "allowed_user": None, "sdk": None}

    telegram.configure(json.dumps({"allowed_user": "42"}))
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)

    assert json.loads(telegram.status("")) | {"sdk": None} == {"token": True, "allowed_user": "42", "sdk": None}


@pytest.mark.parametrize("installed", [True, False])
def test_status_says_whether_this_build_carries_the_telegram_sdk(home, monkeypatch, installed):
    # The app cannot install it later: a payload without python-telegram-bot has no Telegram at all.
    import pm.extras

    monkeypatch.setattr(pm.extras, "available", lambda extra: installed and extra == "telegram")

    assert json.loads(telegram.status(""))["sdk"] is installed


class _BotApi(BaseHTTPRequestHandler):
    """Telegram's Bot API, as far as getMe goes."""

    def do_GET(self):
        ok = self.path == f"/bot{TOKEN}/getMe"
        body = {"ok": True, "result": {"id": 1, "is_bot": True, "username": "phone_hermes_bot"}} if ok else \
            {"ok": False, "error_code": 401, "description": "Unauthorized"}
        data = json.dumps(body).encode()
        self.send_response(200 if ok else 401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format: str, *args: object) -> None:
        pass


@pytest.fixture
def bot_api(monkeypatch):
    server = HTTPServer(("127.0.0.1", 0), _BotApi)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setattr(telegram, "API", f"http://127.0.0.1:{server.server_port}")
    yield
    server.shutdown()
    server.server_close()
    thread.join(5)


def test_check_names_the_bot_the_token_belongs_to(home, bot_api, monkeypatch):
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)

    assert json.loads(telegram.check("")) == {"bot": "@phone_hermes_bot"}


def test_a_refused_token_is_reported_without_the_token(home, bot_api, monkeypatch):
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "999:wrong-token")

    answer = telegram.check("")

    assert "error" in json.loads(answer)
    assert "wrong-token" not in answer


def test_check_without_a_token_says_so(home, monkeypatch):
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)

    assert "error" in json.loads(telegram.check(""))
