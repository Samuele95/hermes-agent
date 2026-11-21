"""The ssh backend over asyncssh, for hosts with no OpenSSH client (the Android app, Termux without
openssh, Windows without ssh.exe).

SSHEnvironment keeps every semantic of the backend (snapshot, cwd markers, file sync, passthrough)
and reaches the remote side through a few primitives; this subclass replaces only those, over one
asyncssh connection kept open the way OpenSSH's ControlMaster is. Host keys follow OpenSSH's
``StrictHostKeyChecking=accept-new`` in the same ``~/.ssh/known_hosts``: a host seen for the first
time is pinned, a pinned host presenting another key is refused. The private key comes from
``key_path``, else from ``TERMINAL_SSH_PRIVATE_KEY`` (the key's text, e.g. from a secret source,
never written to disk), else from the default ``~/.ssh/id_*`` files as with OpenSSH.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import io
import os
import shlex
import subprocess
import tarfile
import threading
from pathlib import Path

import asyncssh

from tools.environments import ssh
from tools.environments.base import EnvironmentConnectionError
from tools.environments.base_output import ProcessHandle, _ThreadedProcessHandle
from tools.environments.file_sync import quoted_mkdir_command, unique_parent_dirs
from tools.environments.remote_common import bash_argv, prepend_unset, resolve_passthrough_env

PRIVATE_KEY_ENV = "TERMINAL_SSH_PRIVATE_KEY"
_CONNECT_FAILED = 255  # what OpenSSH's ssh exits with when it cannot reach or log in to the host


def known_hosts_path() -> Path:
    """The OpenSSH client's file, shared by both transports."""
    return Path.home() / ".ssh" / "known_hosts"


def pinned_host_keys(host: str, port: int, addr: str = "") -> list[asyncssh.SSHKey]:
    """The keys ``~/.ssh/known_hosts`` trusts for *host*:*port* (empty when the host is new)."""
    path = known_hosts_path()
    return list(asyncssh.read_known_hosts(str(path)).match(host, addr or host, port)[0]) if path.is_file() else []


def pin_host_key(host: str, port: int, key: asyncssh.SSHKey) -> None:
    """Append *key* for *host*:*port* in OpenSSH's own entry format."""
    path = known_hosts_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    name = host if port == 22 else f"[{host}]:{port}"
    with path.open("a", encoding="utf-8") as pinned:
        pinned.write(f"{name} {key.export_public_key('openssh').decode().strip()}\n")


class _PinNewHosts(asyncssh.SSHClient):
    """Called for a host key no known_hosts entry trusts: pin it when the host has no entry yet."""

    def validate_host_public_key(self, host: str, addr: str, port: int, key: asyncssh.SSHKey) -> bool:
        if pinned_host_keys(host, port, addr):
            return False  # pinned to another key: refused, as OpenSSH does
        pin_host_key(host, port, key)
        return True


class AsyncsshSSHEnvironment(ssh.SSHEnvironment):
    """``ssh`` backend whose transport is asyncssh instead of the OpenSSH client."""

    def __init__(self, *args, **kwargs):
        self._loop = asyncio.new_event_loop()
        self._loop_thread = threading.Thread(target=self._loop.run_forever, name="hermes-asyncssh", daemon=True)
        self._loop_thread.start()
        self._conn: asyncssh.SSHClientConnection | None = None
        self._conn_lock: asyncio.Lock | None = None
        self._closed = False
        try:
            super().__init__(*args, **kwargs)
        except BaseException:
            self._shutdown()
            raise

    # --- the event loop and the one connection ---------------------------------------------------

    def _call(self, coro, timeout: float | None, what: str):
        if self._closed:  # the loop is gone: fail now rather than wait out the timeout
            coro.close()
            raise EnvironmentConnectionError(f"SSH connection to {self.host} is closed: {what}")
        future = asyncio.run_coroutine_threadsafe(coro, self._loop)
        try:
            return future.result(timeout)
        except concurrent.futures.TimeoutError:
            future.cancel()
            raise subprocess.TimeoutExpired(what, timeout or 0) from None

    async def _connection(self, keys) -> asyncssh.SSHClientConnection:
        """The one connection, opened again only once it is closed (lost, or ended by the host): a refused
        channel leaves it in use. Only the loop's thread writes it."""
        self._conn_lock = self._conn_lock or asyncio.Lock()
        async with self._conn_lock:
            if self._conn is None or self._conn.is_closed():
                self._conn = await asyncssh.connect(
                    self.host, self.port, username=self.user, client_keys=keys,
                    known_hosts=(), client_factory=_PinNewHosts, connect_timeout=10)
            return self._conn

    def _client_keys(self):
        """The client keys, read on the caller's thread: ``key_path``, else ``TERMINAL_SSH_PRIVATE_KEY``, else
        ``()`` = the default ``~/.ssh/id_*`` files. A key that cannot be read is a connection failure naming
        where the key came from, never its text."""
        source = self.key_path or PRIVATE_KEY_ENV
        try:
            if self.key_path:
                return [asyncssh.read_private_key(self.key_path)]
            held = os.environ.get(PRIVATE_KEY_ENV, "").strip()
            return [asyncssh.import_private_key(held)] if held else ()
        except (OSError, ValueError) as exc:  # asyncssh.KeyImportError is a ValueError
            raise EnvironmentConnectionError(
                f"SSH private key from {source} could not be read ({type(exc).__name__})",
                retry_hint=f"Check the key in {source}: an OpenSSH or PEM private key without a passphrase.") from None

    async def _run(self, keys, command: str, *, stdin=None, env=None, merge: bool = False, binary: bool = False):
        conn = await self._connection(keys)
        # asyncssh sends EOF only after non-empty input; with none, stdin would stay open and a command
        # reading it would block. OpenSSH's client gives such a command DEVNULL, and so does this.
        feed = {"input": stdin} if stdin else {"stdin": asyncssh.DEVNULL}
        return await conn.run(command, env=env or {}, check=False, **feed,
                              stderr=asyncssh.STDOUT if merge else asyncssh.PIPE, encoding=None if binary else "utf-8")

    def _shutdown(self) -> None:
        """Close the connection on the loop's own thread (asyncssh is not thread-safe), then stop it."""
        self._closed = True

        async def close():
            if self._conn is not None:
                self._conn.close()
                await self._conn.wait_closed()

        try:
            asyncio.run_coroutine_threadsafe(close(), self._loop).result(5)
        except (concurrent.futures.TimeoutError, OSError, asyncssh.Error):
            pass  # an unclean close leaves nothing behind but the socket, which the loop's end releases
        finally:
            self._loop.call_soon_threadsafe(self._loop.stop)
            self._loop_thread.join(5)
            if not self._loop.is_running():
                self._loop.close()

    # --- SSHEnvironment's transport primitives ---------------------------------------------------

    def _ensure_client(self) -> None:
        """asyncssh is imported by this module; there is no client binary to look for."""

    def _establish_connection(self):
        try:
            self._call(self._connection(self._client_keys()), 15, "connect")
        except subprocess.TimeoutExpired:
            raise EnvironmentConnectionError(
                f"SSH connection to {self.user}@{self.host} timed out",
                retry_hint=f"Check that {self.host} is up and reachable on port {self.port}, then retry.") from None
        except (OSError, asyncssh.Error) as exc:
            raise EnvironmentConnectionError(
                f"SSH connection failed: {exc}",
                retry_hint=(f"Check that {self.host} is up, sshd is running on port {self.port}, {self.user} can "
                            "log in with the configured key, and the host key matches ~/.ssh/known_hosts, then retry."
                            )) from exc

    def _run_ssh(self, remote_cmd: str, timeout: float) -> subprocess.CompletedProcess:
        try:
            result = self._call(self._run(self._client_keys(), remote_cmd), timeout, remote_cmd)
        except (OSError, asyncssh.Error, EnvironmentConnectionError) as exc:
            return subprocess.CompletedProcess(remote_cmd, _CONNECT_FAILED, "", str(exc))
        return subprocess.CompletedProcess(remote_cmd, result.returncode, result.stdout or "", result.stderr or "")

    def _run_bash(self, cmd_string: str, *, login: bool = False, timeout: int = 120,
                  stdin_data: str | None = None) -> ProcessHandle:
        # Passthrough values ride env requests (the remote sshd must AcceptEnv them, as with SendEnv),
        # never the remote command text.
        values, unset_names = resolve_passthrough_env(hermes_env_loader=ssh._load_hermes_env_vars)
        command = " ".join(bash_argv(shlex.quote(prepend_unset(cmd_string, unset_names)), login))
        try:
            keys = self._client_keys()
        except EnvironmentConnectionError as unreadable:
            failure = f"SSH connection failed: {unreadable}\n"  # the except name is gone once the block ends
            return _ThreadedProcessHandle(lambda: (failure, _CONNECT_FAILED))
        future = asyncio.run_coroutine_threadsafe(self._run(keys, command, stdin=stdin_data, env=values, merge=True),
                                                  self._loop)

        def exec_fn() -> tuple[str, int]:
            try:
                result = future.result()
            except concurrent.futures.CancelledError:
                return "", 130
            except (OSError, asyncssh.Error) as exc:
                return f"SSH connection failed: {exc}\n", _CONNECT_FAILED
            return result.stdout or "", result.returncode

        def cancel() -> None:
            future.cancel()

        return _ThreadedProcessHandle(exec_fn, cancel_fn=cancel)

    def _scp_upload(self, host_path: str, remote_path: str) -> None:
        self._run_ssh(f"mkdir -p {shlex.quote(str(Path(remote_path).parent))}", timeout=10)

        keys = self._client_keys()

        async def put():
            conn = await self._connection(keys)
            async with conn.start_sftp_client() as sftp:
                await sftp.put(host_path, remote_path)

        try:
            self._call(put(), 30, f"sftp put {remote_path}")
        except (OSError, asyncssh.Error, subprocess.TimeoutExpired) as exc:
            raise ssh._sync_error(f"sftp failed: {exc}", f"File sync to {self.user}@{self.host}") from exc

    def _ssh_bulk_upload(self, files: list[tuple[str, str]]) -> None:
        """Many files in one stream: a tar built in memory (links followed, like ``tar -h``) into remote
        ``tar x``, after one batched ``mkdir -p``."""
        if not files:
            return
        base = f"{self._remote_home}/.hermes"
        parents = unique_parent_dirs(files)
        if parents:
            self._run_ssh_checked(quoted_mkdir_command(parents), 30, "remote mkdir failed",
                                  f"Remote directory setup on {self.host}")
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w", dereference=True) as tar:
            for host_path, remote_path in files:
                tar.add(host_path, arcname=f"./{ssh._relative_to_sync_base(remote_path, base)}", recursive=False)
        command = f"tar xf - --no-overwrite-dir -C {shlex.quote(base)}"
        try:
            result = self._call(self._run(self._client_keys(), command, stdin=archive.getvalue(), binary=True), 120,
                                command)
        except (OSError, asyncssh.Error) as exc:
            raise ssh._sync_error(f"tar over SSH failed: {exc}", f"File sync over SSH to {self.host}") from exc
        except subprocess.TimeoutExpired:
            raise EnvironmentConnectionError(
                "SSH bulk upload timed out",
                retry_hint=f"Bulk file sync to {self.host} timed out — check the connection and retry.") from None
        if result.returncode != 0:
            raise ssh._sync_error(f"tar extract over SSH failed (rc={result.returncode}): "
                                  f"{(result.stderr or b'').decode(errors='replace').strip()}",
                                  f"File sync over SSH to {self.host}", what="the connection")

    def _ssh_bulk_download(self, dest: Path) -> None:
        rel_base = f"{self._remote_home}/.hermes".lstrip("/")
        command = f"tar cf - --exclude='*.sock' -C / {shlex.quote(rel_base)}"
        try:
            result = self._call(self._run(self._client_keys(), command, binary=True), 120, command)
        except (OSError, asyncssh.Error) as exc:
            raise ssh._sync_error(f"SSH bulk download failed: {exc}", f"File sync from {self.host}") from exc
        except subprocess.TimeoutExpired:
            raise EnvironmentConnectionError(
                "SSH bulk download timed out",
                retry_hint=f"Bulk file sync from {self.host} timed out — check the connection and retry.") from None
        stderr = (result.stderr or b"").decode(errors="replace").strip()
        if result.returncode != 0 and not ssh._only_sockets_skipped(result.returncode, stderr):
            raise ssh._sync_error(f"SSH bulk download failed: {stderr}", f"File sync from {self.host}")
        dest.write_bytes(result.stdout or b"")

    def cleanup(self):
        if self._closed:  # cleanup() runs again from __del__
            return
        try:
            super().cleanup()  # syncs back; there are no ControlMaster sockets to close
        finally:
            self._shutdown()
