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
    assert json.loads(telegram.status("")) | {"sdk": None} == \
        {"token": False, "allowed_user": None, "sdk": None, "enabled": True, "live": None}

    telegram.configure(json.dumps({"allowed_user": "42"}))
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)

    assert json.loads(telegram.status("")) | {"sdk": None} == \
        {"token": True, "allowed_user": "42", "sdk": None, "enabled": True, "live": None}


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


def _checked() -> dict:
    import hermes_android

    return json.loads(hermes_android.call("telegram_check", "{}"))  # through the contract's checked entry


def test_check_names_the_bot_the_token_belongs_to(home, bot_api, monkeypatch):
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)

    assert _checked() == {"check": "ok", "bot": "@phone_hermes_bot", "message": None}


def test_a_refused_token_is_reported_as_refused_without_the_token(home, bot_api, monkeypatch):
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", "999:wrong-token")

    answer = _checked()

    assert answer["check"] == "refused" and answer["bot"] is None
    assert "wrong-token" not in json.dumps(answer)


def test_telegram_out_of_reach_is_not_a_refusal(home, monkeypatch):
    # A token pasted without a connection may be good: the app keeps it and says Telegram could not be asked.
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)
    monkeypatch.setattr(telegram, "API", "http://127.0.0.1:9")  # nothing listens there

    answer = _checked()

    assert answer["check"] == "unreachable" and answer["bot"] is None and TOKEN not in json.dumps(answer)


def test_a_telegram_server_error_is_not_a_refusal(home, monkeypatch):
    class Busy(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(502)
            self.end_headers()

        def log_message(self, format: str, *args: object) -> None:
            pass

    server = HTTPServer(("127.0.0.1", 0), Busy)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)
    monkeypatch.setattr(telegram, "API", f"http://127.0.0.1:{server.server_port}")
    try:
        assert _checked()["check"] == "unreachable"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(5)


def test_check_without_a_token_says_so(home, monkeypatch):
    monkeypatch.delenv("TELEGRAM_BOT_TOKEN", raising=False)

    assert "error" in json.loads(telegram.check(""))


def test_allowing_a_user_keeps_the_sections_other_keys_on_a_plain_mapping():
    telegram._allow(cfg := {"telegram": {"require_mention": True}}, "42")

    assert cfg == {"telegram": {"require_mention": True, "allow_from": ["42"]}}


# The switch and the live entry (D39, F8 / F3's Python half): the switch is config.yaml's
# platforms.telegram.enabled, written under the app's config lock; the entry is the core's own in
# gateway_state.json, passed on as written. Every call goes through the contract's checked entry.

def _ask(function: str, request: dict | None = None) -> dict:
    import hermes_android

    return json.loads(hermes_android.call(function, json.dumps(request or {})))


def _switch(on: bool) -> dict:
    return _ask("telegram_enable", {"enabled": on})


def test_switching_the_bot_off_beats_a_held_token_and_on_brings_it_back(home, monkeypatch):
    monkeypatch.delenv("TELEGRAM_ALLOWED_USERS", raising=False)
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)
    from gateway.config import Platform, load_gateway_config

    assert _switch(False) == {"enabled": False}

    config = load_gateway_config()
    assert config.platforms[Platform.TELEGRAM].enabled is False
    assert Platform.TELEGRAM not in config.get_connected_platforms()

    assert _switch(True) == {"enabled": True}

    config = load_gateway_config()
    assert config.platforms[Platform.TELEGRAM].enabled is True
    assert Platform.TELEGRAM in config.get_connected_platforms()


def test_the_switch_keeps_the_token_the_allowed_user_and_the_sections_other_keys_and_writes_no_dotenv(
        home, monkeypatch):
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)
    (home / "config.yaml").write_text(
        "model:\n  default: m1\ntelegram:\n  allow_from: ['42']\nplatforms:\n  telegram:\n"
        "    home_channel: {platform: telegram, chat_id: '5', name: Home}\n  discord:\n    enabled: false\n",
        encoding="utf-8")

    _switch(False)

    from hermes_cli.config import read_user_config_raw
    raw = read_user_config_raw()
    assert raw["model"]["default"] == "m1"
    assert raw["telegram"]["allow_from"] == ["42"]
    assert raw["platforms"]["telegram"]["home_channel"]["chat_id"] == "5"
    assert raw["platforms"]["discord"] == {"enabled": False}
    assert raw["platforms"]["telegram"]["enabled"] is False
    assert not (home / ".env").exists()
    assert os.environ["TELEGRAM_BOT_TOKEN"] == TOKEN
    answer = _ask("telegram_status")
    assert answer["token"] is True and answer["enabled"] is False


def test_the_switch_waits_for_the_apps_config_lock(home):
    from hermes_android import _config
    from hermes_cli.config import read_user_config_raw

    a_in, release = threading.Event(), threading.Event()

    def fn_a(cfg):
        a_in.set()
        release.wait(timeout=10)
        cfg.setdefault("providers", {})["a"] = {"name": "a", "base_url": "http://10.0.0.9/v1", "model": "m"}

    a = threading.Thread(target=_config.mutate, args=(fn_a,))
    a.start()
    assert a_in.wait(timeout=10)
    b = threading.Thread(target=_switch, args=(False,))
    b.start()
    try:
        b.join(timeout=1.0)
        platforms = (read_user_config_raw() or {}).get("platforms") or {}
        assert b.is_alive() and "enabled" not in (platforms.get("telegram") or {}), \
            "the switch wrote while another app write held the span"
    finally:
        release.set()
        a.join(timeout=10)
        b.join(timeout=10)

    raw = read_user_config_raw()
    assert "a" in raw["providers"]
    assert raw["platforms"]["telegram"]["enabled"] is False


def test_switching_to_the_value_already_set_writes_nothing(home):
    _switch(False)
    before = (home / "config.yaml").stat()

    assert _switch(False) == {"enabled": False}

    after = (home / "config.yaml").stat()
    assert (after.st_ino, after.st_mtime_ns) == (before.st_ino, before.st_mtime_ns)


@pytest.mark.parametrize(("block", "expected"), [
    ("", True),
    ("platforms:\n  telegram:\n    enabled: false\n", False),
    ("platforms:\n  telegram:\n    enabled: true\n", True),
    ("platforms:\n  telegram:\n    enabled: 'no'\n", False),
    ("platforms:\n  telegram:\n    enabled: 'on'\n", True),
    ("platforms:\n  telegram:\n    enabled:\n", False),
], ids=["none", "false", "true", "no", "on", "null"])
def test_enabled_is_the_cores_own_reading_of_the_switch(home, monkeypatch, block, expected):
    monkeypatch.delenv("TELEGRAM_ALLOWED_USERS", raising=False)
    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)
    (home / "config.yaml").write_text("model:\n  default: m1\n" + block, encoding="utf-8")

    from gateway.config import Platform, load_gateway_config
    core = load_gateway_config().platforms.get(Platform.TELEGRAM)

    enabled = _ask("telegram_status")["enabled"]
    assert enabled is bool(core and core.enabled)
    assert enabled is expected


def test_with_no_gateway_state_the_bot_has_no_live_entry(home):
    assert _ask("telegram_status")["live"] is None


def test_the_live_entry_is_the_cores_own_with_the_pid_that_wrote_it(home):
    from gateway.status import read_runtime_status, write_runtime_status

    write_runtime_status(gateway_state="running", platform="telegram", platform_state="fatal",
                         error_code="telegram_polling_conflict",
                         error_message="Conflict: terminated by other getUpdates request")

    assert _ask("telegram_status")["live"] == {
        "state": "fatal", "error_message": "Conflict: terminated by other getUpdates request",
        "writer_pid": os.getpid(), "updated_at": read_runtime_status()["platforms"]["telegram"]["updated_at"]}

    write_runtime_status(platform="telegram", platform_state="connected", error_code=None, error_message=None)

    live = _ask("telegram_status")["live"]
    assert live["state"] == "connected" and live["error_message"] is None


def test_an_entry_from_a_gone_writer_is_passed_on_as_written(home):
    (home / "gateway_state.json").write_text(json.dumps({
        "pid": 999999, "gateway_state": "running", "updated_at": "2025-01-01T00:00:00+00:00",
        "platforms": {"telegram": {"state": "connected", "writer_pid": 999999,
                                   "updated_at": "2025-01-01T00:00:00+00:00"}}}), encoding="utf-8")

    # Whether it describes the running gateway is telegramLive's call, against the app's own record.
    assert _ask("telegram_status")["live"] == {
        "state": "connected", "error_message": None, "writer_pid": 999999, "updated_at": "2025-01-01T00:00:00+00:00"}


_NULLS = {"state": None, "error_message": None, "writer_pid": None, "updated_at": None}


@pytest.mark.parametrize(("text", "expected"), [
    ('{"platforms": {"telegram": {"state": 7, "writer_pid": "12", "error_message": ["x"], "updated_at": 5}}}',
     _NULLS),
    ('{"platforms": {"telegram": {"state": "connected", "writer_pid": true, "updated_at": "2026-11-13T00:00:00+00:00"}}}',
     {"state": "connected", "error_message": None, "writer_pid": None, "updated_at": "2026-11-13T00:00:00+00:00"}),
    ('{"platforms": {"telegram": "x"}}', None),
    ('{"platforms": []}', None),
    ('{"platforms": {"discord": {"state": "connected"}}}', None),
    ("{", None),
], ids=["wrong-types", "bool-pid", "entry-not-object", "platforms-not-object", "no-telegram", "not-json"])
def test_a_malformed_entry_reads_as_nulls_never_as_a_broken_reply(home, text, expected):
    (home / "gateway_state.json").write_text(text, encoding="utf-8")

    assert _ask("telegram_status")["live"] == expected


def test_the_live_error_leaves_redacted(home):
    from gateway.status import write_runtime_status

    write_runtime_status(platform="telegram", platform_state="fatal", error_message=(
        "Telegram startup failed: https://api.telegram.org/bot123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw/getMe failed"))

    message = _ask("telegram_status")["live"]["error_message"]
    assert "AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw" not in message
    assert "Telegram startup failed" in message
