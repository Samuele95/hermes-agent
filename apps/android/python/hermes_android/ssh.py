"""The app's side of the ssh terminal backend (D22): an Ed25519 identity the app keeps in the Android
keystore, and a host pinned only after the user has seen its fingerprint.

The core runs the backend over asyncssh (no OpenSSH client in the app) with the private key from
``TERMINAL_SSH_PRIVATE_KEY``, which the keystore secret source exports, and the host's key from the
same ``~/.ssh/known_hosts`` its own accept-new rule would write. Host, user and port go to
config.yaml's ``terminal`` section through the core's config writer; nothing here touches ``.env``
or writes key material to disk.
"""

from __future__ import annotations

import asyncio
import json
import os

import asyncssh

from tools.environments.asyncssh_env import PRIVATE_KEY_ENV, known_hosts_path, pin_host_key, pinned_host_keys

_LOOK_TIMEOUT = 15
_LOGIN_TIMEOUT = 20


_BAD_TARGET = "An SSH host needs a host name, a user and a port between 1 and 65535."


class _BadTarget(ValueError):
    """A host or port no entry acts on; its message is the user's."""


def _target(request: dict) -> tuple[str, int]:
    """The host and port every entry acts on, checked here once: nothing is dialled or pinned for a bad one."""
    host = str(request.get("host") or "").strip()
    given = request.get("port")
    try:
        port = 22 if given is None or given == "" else int(given)  # 0 is a port given, not a port missing
    except (TypeError, ValueError):
        raise _BadTarget(_BAD_TARGET) from None
    if not host or not 0 < port < 65536:
        raise _BadTarget(_BAD_TARGET)
    return host, port


def _refused(bad: _BadTarget) -> str:
    return json.dumps({"error": str(bad)})


def new_key(_arg: str) -> str:
    """``{private, public}``: a fresh Ed25519 pair. The app stores ``private`` in the keystore at once and
    shows ``public`` for the host's ``authorized_keys``."""
    key = asyncssh.generate_private_key("ssh-ed25519", comment="hermes-android")
    return json.dumps({"private": key.export_private_key().decode(),
                       "public": key.export_public_key().decode().strip()})


def public_key(_arg: str) -> str:
    """``{public}`` of the identity the keystore exports, or ``{error}`` when there is none yet."""
    held = os.environ.get(PRIVATE_KEY_ENV, "").strip()
    if not held:
        return json.dumps({"error": "No SSH identity yet."})
    try:
        key = asyncssh.import_private_key(held)
    except (asyncssh.KeyImportError, ValueError):
        return json.dumps({"error": "The stored SSH identity cannot be read."})
    return json.dumps({"public": key.export_public_key().decode().strip()})


def host_key(arg: str) -> str:
    """``{host, port}`` -> ``{key, fingerprint, pinned}`` with ``pinned`` one of ``no`` / ``yes`` /
    ``changed``, or ``{error}``. Looking logs in to nothing and pins nothing."""
    try:
        host, port = _target(json.loads(arg))
    except _BadTarget as bad:
        return _refused(bad)
    try:
        key = asyncio.run(asyncio.wait_for(asyncssh.get_server_host_key(host, port), _LOOK_TIMEOUT))
    except (OSError, asyncssh.Error, TimeoutError) as exc:
        return json.dumps({"error": f"Could not reach {host}:{port}: {exc or 'timed out'}"})
    if key is None:
        return json.dumps({"error": f"{host}:{port} offered no host key."})
    pinned = pinned_host_keys(host, port)
    return json.dumps({"key": key.export_public_key("openssh").decode().strip(),
                       "fingerprint": key.get_fingerprint("sha256"),
                       "pinned": "yes" if key in pinned else "changed" if pinned else "no"})


def trust_host(arg: str) -> str:
    """``{host, port, key}`` -> ``{pinned: "yes"}`` once the user confirmed the fingerprint, or ``{error}``
    when the host is already pinned to another key (that is for the user to resolve, never the app)."""
    request = json.loads(arg)
    try:
        host, port = _target(request)
    except _BadTarget as bad:
        return _refused(bad)
    key = asyncssh.import_public_key(str(request.get("key") or ""))
    pinned = pinned_host_keys(host, port)
    if pinned and key not in pinned:
        return json.dumps({"error": f"{host}:{port} is pinned to a different host key. If the host was really "
                                    "reinstalled, remove its line from known_hosts first."})
    if not pinned:
        pin_host_key(host, port, key)
    return json.dumps({"pinned": "yes"})


def configure(arg: str) -> str:
    """``{host, user, port}`` -> ``{backend: "ssh"}``: the terminal runs on that host from now on."""
    from hermes_cli.config import apply_terminal_config_to_env, load_config, save_config

    request = json.loads(arg)
    try:
        host, port = _target(request)
    except _BadTarget as bad:
        return _refused(bad)
    user = str(request.get("user") or "").strip()
    if not user:
        return json.dumps({"error": _BAD_TARGET})
    # config.yaml only: the CLI's `config set` also mirrors terminal.* into .env, which the app never writes.
    config = load_config()
    config.setdefault("terminal", {}).update({"backend": "ssh", "ssh_host": host, "ssh_user": user, "ssh_port": port})
    save_config(config)
    # The core's own bridge puts terminal.* into this process's environment, which terminal tools read.
    apply_terminal_config_to_env()
    return json.dumps({"backend": "ssh"})


def check_login(arg: str) -> str:
    """``{host, user, port}`` -> ``{ok: true}`` once the pinned host accepts the app's key, else ``{error}``.
    Strict: a host not pinned yet, or presenting another key, fails rather than being trusted on the way."""
    request = json.loads(arg)
    try:
        host, port = _target(request)
    except _BadTarget as bad:
        return _refused(bad)
    user = str(request.get("user") or "").strip()
    held = os.environ.get(PRIVATE_KEY_ENV, "").strip()
    if not held:
        return json.dumps({"error": "No SSH identity yet."})
    if not pinned_host_keys(host, port):
        return json.dumps({"error": f"{host}:{port} is not trusted yet: confirm its fingerprint first."})

    async def attempt():
        async with asyncssh.connect(host, port, username=user, client_keys=[asyncssh.import_private_key(held)],
                                    known_hosts=str(known_hosts_path()), connect_timeout=10) as conn:
            await conn.run("true", check=True, stdin=asyncssh.DEVNULL)

    try:
        asyncio.run(asyncio.wait_for(attempt(), _LOGIN_TIMEOUT))
    except asyncssh.PermissionDenied:
        return json.dumps({"error": f"{host} refused the app's key for {user}: add the public key to that "
                                    "user's ~/.ssh/authorized_keys."})
    except (OSError, asyncssh.Error, TimeoutError) as exc:
        return json.dumps({"error": f"Could not log in to {user}@{host}:{port}: {exc or 'timed out'}"})
    return json.dumps({"ok": True})
