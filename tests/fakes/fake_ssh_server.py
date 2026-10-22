"""A real asyncssh server on 127.0.0.1 for SSH tests that need no sshd.

Each command runs as a local ``bash -c`` with the server's ``remote_home`` as HOME and working
directory, so "remote" effects land in a temp directory the test can inspect; SFTP serves the local
filesystem. Only ``client_key``'s public key may log in. Stdin is buffered to EOF before the command
starts: a client that never closes stdin hangs here, as a command reading stdin would on a real host.

Usage::

    server = FakeSSHServer(tmp_path, client_key)
    try:
        ...  # connect to 127.0.0.1:server.port as any user with client_key
    finally:
        server.close()
"""

from __future__ import annotations

import asyncio
import os
import threading
from pathlib import Path

import asyncssh


class FakeSSHServer:
    def __init__(self, remote_home: Path, client_key: asyncssh.SSHKey, host_key: asyncssh.SSHKey | None = None):
        self.remote_home = remote_home
        self.host_key = host_key or asyncssh.generate_private_key("ssh-ed25519")
        self._client_key = client_key
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, daemon=True)
        self._thread.start()
        self._server = asyncio.run_coroutine_threadsafe(self._start(), self._loop).result(10)
        self.port = self._server.sockets[0].getsockname()[1]

    async def _start(self):
        return await asyncssh.create_server(
            asyncssh.SSHServer, "127.0.0.1", 0, server_host_keys=[self.host_key],
            authorized_client_keys=asyncssh.import_authorized_keys(self._client_key.export_public_key().decode()),
            process_factory=self._run, sftp_factory=True, encoding=None)  # bytes: tar streams are binary

    async def _run(self, process):
        env = {**os.environ, "HOME": str(self.remote_home), **dict(process.env)}
        stdin = await process.stdin.read()
        local = await asyncio.create_subprocess_exec(
            "bash", "-c", process.command or "true", cwd=self.remote_home, env=env,
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        out, err = await local.communicate(stdin)
        process.stdout.write(out)
        process.stderr.write(err)
        process.exit(local.returncode)

    def close(self):
        async def stop():  # on the server's own loop, letting asyncssh finish its per-process cleanup
            self._server.close()
            await self._server.wait_closed()

        asyncio.run_coroutine_threadsafe(stop(), self._loop).result(10)
        self._loop.call_soon_threadsafe(self._loop.stop)
        self._thread.join(5)
        self._loop.close()
