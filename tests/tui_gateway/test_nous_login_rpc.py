"""``nous.login.start`` / ``nous.login.poll`` / ``nous.login.cancel``: the Nous Portal device-code
sign-in for gateway clients that have no dashboard REST routes (an embedding host such as the
Android app), driven through the registered RPC handlers against the fake portal. The account lands
where every Nous sign-in puts it (``auth.json``), never in ``.env``."""

from __future__ import annotations

import threading
import time

import pytest

import tui_gateway.server as srv
from hermes_cli import anon_auth
from hermes_cli.auth import _load_auth_store
from hermes_constants import get_hermes_home
from tests.hermes_cli.anon_portal import PORTAL, install_portal


def _call(method: str, params: dict | None = None) -> dict:
    return srv._methods[method](1, params or {})


def _result(method: str, params: dict | None = None) -> dict:
    response = _call(method, params)
    assert "result" in response, response
    return response["result"]


def _settled(login_id: str, timeout: float = 15.0) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = _result("nous.login.poll", {"login_id": login_id})
        if status["status"] != "pending":
            return status
        time.sleep(0.05)
    raise AssertionError("the sign-in is still pending")


@pytest.fixture
def portal(tmp_path, monkeypatch):
    return install_portal(monkeypatch, tmp_path)


def test_a_device_code_sign_in_shows_its_code_and_stores_the_account_once_approved(portal):
    portal.device_approved = False

    started = _result("nous.login.start")
    assert started["user_code"] == "NOUS-1234"
    assert started["verification_url"] == f"{PORTAL}/device?user_code=NOUS-1234"
    assert _result("nous.login.poll", {"login_id": started["login_id"]})["status"] == "pending"

    portal.device_approved = True
    assert _settled(started["login_id"])["status"] == "approved"

    store = _load_auth_store()
    assert store["active_provider"] == "nous" and store["providers"]["nous"]["refresh_token"] == "refresh-1"
    assert not (get_hermes_home() / ".env").exists()


def test_a_cancelled_sign_in_stores_nothing_even_if_approved_later(portal):
    portal.device_approved = False
    login_id = _result("nous.login.start")["login_id"]

    assert _result("nous.login.cancel", {"login_id": login_id}) == {"cancelled": True}
    portal.device_approved = True

    assert _settled(login_id)["status"] == "cancelled"
    assert "nous" not in (_load_auth_store().get("providers") or {})


def test_an_unknown_sign_in_is_an_error_not_a_hang(portal):
    assert _result("nous.login.poll", {"login_id": "nope"})["status"] == "error"
    assert _result("nous.login.cancel", {"login_id": "nope"}) == {"cancelled": False}


def test_a_free_tier_identity_is_left_to_the_sign_in_that_keeps_its_connectors(portal, monkeypatch):
    monkeypatch.setattr(anon_auth, "guest_allowed", lambda: True)
    monkeypatch.setattr(anon_auth, "current_nous_state", lambda: {"auth_method": "anonymous"})
    monkeypatch.setattr(anon_auth, "is_guest_state", lambda state: bool(state))

    response = _call("nous.login.start")

    assert "error" in response and "/login" in response["error"]["message"]
    assert ("POST", "/api/oauth/device/code") not in portal.calls


def test_a_sign_in_for_another_profile_lands_in_that_profiles_store_and_only_it_sees_it(portal, monkeypatch):
    profile_home = get_hermes_home() / "profiles" / "coder"
    profile_home.mkdir(parents=True)
    (profile_home / "config.yaml").write_text("{}\n")  # identity marker: a bare dir is not a profile
    portal.device_approved = False

    login_id = _result("nous.login.start", {"profile": "coder"})["login_id"]
    assert _result("nous.login.poll", {"login_id": login_id})["status"] == "error"  # the launch profile's view
    portal.device_approved = True
    deadline = time.monotonic() + 15
    while _result("nous.login.poll", {"login_id": login_id, "profile": "coder"})["status"] == "pending":
        assert time.monotonic() < deadline
        time.sleep(0.05)

    import json
    assert json.loads((profile_home / "auth.json").read_text())["providers"]["nous"]["refresh_token"] == "refresh-1"
    assert "nous" not in (_load_auth_store().get("providers") or {})


def test_a_cancel_that_lands_while_the_account_is_validated_still_stores_nothing(portal, monkeypatch):
    from hermes_cli import auth as auth_mod
    real_refresh = auth_mod.refresh_nous_oauth_from_state
    login_id = None

    def refresh_while_the_user_cancels(state, **kwargs):
        assert _result("nous.login.cancel", {"login_id": login_id}) == {"cancelled": True}
        return real_refresh(state, **kwargs)
    monkeypatch.setattr(auth_mod, "refresh_nous_oauth_from_state", refresh_while_the_user_cancels)
    portal.device_approved = False
    login_id = _result("nous.login.start")["login_id"]
    portal.device_approved = True

    deadline = time.monotonic() + 15
    while _result("nous.login.poll", {"login_id": login_id})["status"] == "pending":
        assert time.monotonic() < deadline
        time.sleep(0.05)
    for worker in [t for t in threading.enumerate() if t.name == "nous-login"]:
        worker.join(15)  # the cancel settled the status; the worker decides about the save after it

    assert _result("nous.login.poll", {"login_id": login_id})["status"] == "cancelled"
    assert "nous" not in (_load_auth_store().get("providers") or {})


def _worker_done() -> None:
    for worker in [t for t in threading.enumerate() if t.name == "nous-login"]:
        worker.join(15)


def test_an_approved_sign_in_stays_approved_when_settling_its_model_fails(portal, monkeypatch):
    def settle_fails(state):
        raise RuntimeError("models endpoint down")
    monkeypatch.setattr(anon_auth, "settle_after_upgrade", settle_fails)
    login_id = _result("nous.login.start")["login_id"]

    _settled(login_id)
    _worker_done()

    assert _result("nous.login.poll", {"login_id": login_id}) == {"status": "approved"}  # the account is stored
    assert _load_auth_store()["providers"]["nous"]["refresh_token"] == "refresh-1"


def test_the_model_chosen_after_the_sign_in_is_reported(portal, monkeypatch):
    monkeypatch.setattr(anon_auth, "settle_after_upgrade", lambda state: {"model": "hermes-4-405b"})
    login_id = _result("nous.login.start")["login_id"]

    _settled(login_id)
    _worker_done()

    assert _result("nous.login.poll", {"login_id": login_id}) == {"status": "approved", "model": "hermes-4-405b"}


@pytest.mark.parametrize("raised, status", [("expired", "expired"), ("portal", "error")])
def test_a_code_that_expires_or_a_portal_failure_is_reported_and_stores_nothing(portal, monkeypatch, raised, status):
    from hermes_cli import auth as auth_mod
    from hermes_cli.auth_error_copy import DeviceCodeExpired

    def poll_fails(**_):
        raise DeviceCodeExpired("the code expired") if raised == "expired" else RuntimeError("portal answered 502")
    monkeypatch.setattr(auth_mod, "_poll_for_token", poll_fails)

    answer = _settled(_result("nous.login.start")["login_id"])

    assert answer["status"] == status and answer["error_message"]
    assert "nous" not in (_load_auth_store().get("providers") or {})


def test_a_settled_sign_in_cannot_be_cancelled_or_changed(portal):
    login_id = _result("nous.login.start")["login_id"]
    assert _settled(login_id)["status"] == "approved"
    _worker_done()

    assert _result("nous.login.cancel", {"login_id": login_id}) == {"cancelled": False}
    assert _result("nous.login.poll", {"login_id": login_id})["status"] == "approved"
