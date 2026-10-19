"""Nous Portal device-code sign-ins for gateway clients (``nous.login.start`` / ``poll`` / ``cancel``).

The plain device-code flow the dashboard runs for "connect a Nous account"
(``hermes_cli/web_routers/oauth.py::_start_nous_device_code`` + ``web_server_oauth._nous_plain_poller``),
for a client with no dashboard REST routes: ``start`` asks the portal for a code and returns it at
once, a worker thread waits for the user's approval and stores the account where every Nous sign-in
puts it (``persist_nous_credentials``: auth.json), never ``.env``. A free-tier identity is left to the
sign-in that carries its connectors over (``/login``).

Each sign-in belongs to the profile home it started in: poll and cancel from another profile see
nothing, and the worker runs under the starting request's profile scope (``spawn_context_thread``).
"""

from __future__ import annotations

import enum
import logging
import secrets
import threading
import time
from dataclasses import dataclass
from typing import Any

logger = logging.getLogger(__name__)


class Status(str, enum.Enum):
    """A sign-in's state as ``nous.login.poll`` reports it; every state but PENDING is final."""

    PENDING = "pending"
    APPROVED = "approved"
    EXPIRED = "expired"
    CANCELLED = "cancelled"
    ERROR = "error"


@dataclass(frozen=True)
class _Grant:
    """What the portal handed out for one sign-in, and where it was asked for."""

    portal_base_url: str
    client_id: str
    scope: str
    device_code: str
    interval: int
    expires_at: float


@dataclass
class _Login:
    """One sign-in. Its status changes only through :func:`_settle`, under ``_lock``."""

    login_id: str
    hermes_home: str
    created_at: float
    grant: _Grant
    status: Status = Status.PENDING
    error_message: str | None = None
    model: str | None = None


_logins: dict[str, _Login] = {}
_lock = threading.Lock()
_TTL_SECONDS = 900  # a settled or abandoned sign-in stays pollable this long


class GuestIdentity(Exception):
    """A free-tier identity is signed in here: its own sign-in keeps its connectors."""


def start(hermes_home: str) -> dict[str, Any]:
    """Ask the portal for a device code → ``{login_id, user_code, verification_url, expires_in,
    poll_interval}``; the sign-in then completes in the background. Called inside the request's
    profile scope. Network errors raise."""
    import httpx

    from agent.memory_provider import spawn_context_thread
    from hermes_cli import anon_auth
    from hermes_cli.auth import PROVIDER_REGISTRY, _nous_portal_env_override, _request_device_code

    pconfig = PROVIDER_REGISTRY["nous"]
    if anon_auth.guest_allowed() and anon_auth.is_guest_state(anon_auth.current_nous_state()):
        raise GuestIdentity("A Nous free-tier identity is signed in here; use /login to keep its connectors.")
    # The operator's dev/staging override, read through the profile's secret scope.
    portal_base_url = (_nous_portal_env_override() or pconfig.portal_base_url).rstrip("/")
    with httpx.Client(timeout=httpx.Timeout(15.0), headers={"Accept": "application/json"}) as client:
        device = _request_device_code(client=client, portal_base_url=portal_base_url,
                                      client_id=pconfig.client_id, scope=pconfig.scope)
    grant = _Grant(portal_base_url=portal_base_url, client_id=pconfig.client_id, scope=pconfig.scope,
                   device_code=str(device["device_code"]), interval=int(device["interval"]),
                   expires_at=time.time() + int(device["expires_in"]))
    login = _Login(login_id=secrets.token_urlsafe(16), hermes_home=hermes_home, created_at=time.time(), grant=grant)
    cutoff = time.time() - _TTL_SECONDS
    with _lock:
        for stale in [k for k, v in _logins.items() if v.created_at < cutoff]:
            _logins.pop(stale)
        _logins[login.login_id] = login
    spawn_context_thread(_finish, name="nous-login", args=(login,)).start()
    return {"login_id": login.login_id, "user_code": str(device["user_code"]),
            "verification_url": str(device["verification_uri_complete"]),
            "expires_in": int(device["expires_in"]), "poll_interval": int(device["interval"])}


def _settle(login: _Login, status: Status, error_message: str | None = None) -> bool:
    """Move a pending sign-in to ``status``; a final state is never left. Called under ``_lock``."""
    if login.status is not Status.PENDING:
        return False
    login.status, login.error_message = status, error_message
    return True


def _finish(login: _Login) -> None:
    """Wait for the approval, then store the account unless the sign-in was cancelled meanwhile."""
    import httpx

    from hermes_cli import anon_auth
    from hermes_cli.auth import (
        _poll_for_token, nous_auth_state_from_token, persist_nous_credentials, refresh_nous_oauth_from_state)
    from hermes_cli.auth_error_copy import DeviceCodeExpired

    grant = login.grant
    try:
        with httpx.Client(timeout=httpx.Timeout(15.0), headers={"Accept": "application/json"}) as client:
            token_data = _poll_for_token(
                client=client, portal_base_url=grant.portal_base_url, client_id=grant.client_id,
                device_code=grant.device_code, expires_in=max(60, int(grant.expires_at - time.time())),
                poll_interval=grant.interval)
        state = nous_auth_state_from_token(
            token_data, portal_base_url=grant.portal_base_url, client_id=grant.client_id, scope=grant.scope)
        if login.status is not Status.PENDING:
            return
        full_state = refresh_nous_oauth_from_state(state, timeout_seconds=15.0, force_refresh=False)
        # The last cancellation check, the save and the verdict are one step: a cancel either
        # lands before it (nothing stored) or finds the sign-in already approved.
        with _lock:
            if login.status is not Status.PENDING:
                return
            persist_nous_credentials(full_state)
            _settle(login, Status.APPROVED)
    except DeviceCodeExpired as exc:
        _failed(login, Status.EXPIRED, exc)
        return
    except Exception as exc:  # the worker's boundary: it has no caller, the poll reports the failure
        logger.warning("nous.login: sign-in failed", exc_info=True)
        _failed(login, Status.ERROR, exc)
        return
    try:  # the account is stored: choosing its model is a best effort that cannot undo the approval
        model = anon_auth.settle_after_upgrade(full_state).get("model") or None
    except Exception:
        logger.warning("nous.login: signed in, but choosing the account's model failed", exc_info=True)
        return
    with _lock:
        login.model = model


def _failed(login: _Login, status: Status, exc: Exception) -> None:
    with _lock:
        _settle(login, status, str(exc))  # a cancelled sign-in stays cancelled, whatever the portal said after


def _owned(login_id: str, hermes_home: str) -> _Login | None:
    from hermes_constants import hermes_home_key

    with _lock:
        login = _logins.get(login_id)
    if login is None or hermes_home_key(login.hermes_home) != hermes_home_key(hermes_home):
        return None
    return login


def poll(login_id: str, hermes_home: str) -> dict[str, Any]:
    """``{status: pending | approved | expired | cancelled | error, error_message?, model?}``."""
    login = _owned(login_id, hermes_home)
    if login is None:
        return {"status": "error", "error_message": "Nous sign-in not found or expired"}
    with _lock:
        answer: dict[str, Any] = {"status": login.status.value}
        if login.error_message:
            answer["error_message"] = login.error_message
        if login.model:
            answer["model"] = login.model
    return answer


def cancel(login_id: str, hermes_home: str) -> dict[str, Any]:
    """Abandon a pending sign-in: whatever the portal answers later is not stored."""
    login = _owned(login_id, hermes_home)
    if login is None:
        return {"cancelled": False}
    with _lock:
        return {"cancelled": _settle(login, Status.CANCELLED)}
