"""A throwaway SSH server for scripts/ssh_smoke.sh, on this host's 127.0.0.1 (the phone reaches it
through `adb reverse`). Ephemeral host key; commands run as local ``bash -c`` in ``--home``, which
stands in for the remote home. Any public key may log in, and the one that did is written to
``--record``: the smoke compares it with the key the app shows, so it is the phone's own identity
that authenticated. Prints ``fingerprint <SHA256:…>`` once listening, then serves until killed.

Run with an interpreter that has asyncssh (the test environment: source ./activate --test-extras all,asyncssh).
"""

from __future__ import annotations

import argparse
import asyncio
import os
from pathlib import Path

import asyncssh

_COMMAND_TIMEOUT = 600  # longer than any terminal call of a turn; a hung command must not hang the smoke


class _AnyKey(asyncssh.SSHServer):
    def __init__(self, record: Path):
        self._record = record

    def begin_auth(self, username: str) -> bool:
        return True

    def public_key_auth_supported(self) -> bool:
        return True

    def validate_public_key(self, username: str, key: asyncssh.SSHKey) -> bool:
        self._record.write_text(key.export_public_key().decode().split()[1] + "\n", encoding="utf-8")
        return True


async def _serve(port: int, home: Path, record: Path) -> None:
    async def run(process):
        # The model chooses these commands: they get a minimal environment, never this machine's own (its tokens).
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(home), "LANG": "C.UTF-8", **dict(process.env)}
        stdin = await process.stdin.read()
        local = await asyncio.create_subprocess_exec(
            "bash", "-c", process.command or "true", cwd=home, env=env,
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            out, err = await asyncio.wait_for(local.communicate(stdin), timeout=_COMMAND_TIMEOUT)
            status = local.returncode
        except TimeoutError:
            local.kill()
            await asyncio.wait_for(local.wait(), timeout=10)  # SIGKILL: it exits at once
            out, err, status = b"", f"ssh_smoke_server: killed after {_COMMAND_TIMEOUT} s\n".encode(), 124
        process.stdout.write(out)
        process.stderr.write(err)
        process.exit(status)

    host_key = asyncssh.generate_private_key("ssh-ed25519")
    await asyncssh.create_server(lambda: _AnyKey(record), "127.0.0.1", port, server_host_keys=[host_key],
                                 process_factory=run, sftp_factory=True, encoding=None)
    print(f"fingerprint {host_key.get_fingerprint('sha256')}", flush=True)
    await asyncio.Event().wait()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", type=int, default=2222)
    parser.add_argument("--home", type=Path, required=True)
    parser.add_argument("--record", type=Path, required=True)
    args = parser.parse_args()
    asyncio.run(_serve(args.port, args.home, args.record))


if __name__ == "__main__":
    main()
