"""The ssh backend over asyncssh, for hosts without an OpenSSH client (the Android app, Termux without
openssh, Windows without ssh.exe).

Driven against a real asyncssh server in this process (``tests/fakes/fake_ssh_server.py``). The
backend's semantics (snapshot, cwd markers, file sync) come from SSHEnvironment unchanged; what is
pinned here is the transport under them, and OpenSSH's ``accept-new`` host-key rule.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from tools.environments.base import EnvironmentConnectionError

asyncssh = pytest.importorskip("asyncssh")  # the optional dependency; the module under test must import

from tests.fakes.fake_ssh_server import FakeSSHServer as Server
from tools.environments import asyncssh_env


@pytest.fixture
def client_key():
    return asyncssh.generate_private_key("ssh-ed25519")


@pytest.fixture
def local_home(tmp_path, monkeypatch):
    """The client side: HOME (where ~/.ssh/known_hosts lives) and an empty Hermes home."""
    home = tmp_path / "client"
    (home / ".hermes").mkdir(parents=True)
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("HERMES_HOME", str(home / ".hermes"))
    monkeypatch.delenv("TERMINAL_SSH_PRIVATE_KEY", raising=False)
    return home


@pytest.fixture
def server(tmp_path, client_key):
    remote_home = tmp_path / "remote"
    remote_home.mkdir()
    running = Server(remote_home, client_key)
    yield running
    running.close()


def connect(server, key_file: Path | None = None, **kwargs):
    return asyncssh_env.AsyncsshSSHEnvironment(
        host="127.0.0.1", user="hermes", port=server.port, key_path=str(key_file or ""), cwd="~", **kwargs)


@pytest.fixture
def key_file(tmp_path, client_key):
    path = tmp_path / "id_ed25519"
    client_key.write_private_key(str(path))
    path.chmod(0o600)
    return path


def test_a_command_runs_on_the_remote_side_and_reports_its_status(local_home, server, key_file):
    env = connect(server, key_file)
    try:
        ok = env.execute("echo \"$HOME\"")
        failed = env.execute("exit 3")
    finally:
        env.cleanup()

    assert ok["returncode"] == 0 and ok["output"].strip() == str(server.remote_home)
    assert failed["returncode"] == 3


def test_the_working_directory_carries_over_between_commands(local_home, server, key_file):
    (server.remote_home / "work").mkdir()
    env = connect(server, key_file)
    try:
        env.execute("cd work")
        where = env.execute("pwd")
    finally:
        env.cleanup()

    assert where["output"].strip() == str(server.remote_home / "work")


def test_a_new_host_key_is_pinned_and_a_changed_one_refused(local_home, tmp_path, client_key, key_file):
    first = Server(tmp_path, client_key)
    try:
        connect(first, key_file, sync_files=False).cleanup()
        known = (local_home / ".ssh" / "known_hosts").read_text(encoding="utf-8")
        assert f"[127.0.0.1]:{first.port}" in known
        assert first.host_key.export_public_key().decode().split()[1] in known

        impostor = Server(tmp_path, client_key)  # a different host key behind the pinned name
        try:
            known = known.replace(f"[127.0.0.1]:{first.port}", f"[127.0.0.1]:{impostor.port}")
            (local_home / ".ssh" / "known_hosts").write_text(known, encoding="utf-8")
            with pytest.raises(EnvironmentConnectionError):
                connect(impostor, key_file, sync_files=False)
        finally:
            impostor.close()
    finally:
        first.close()


def test_a_key_held_only_in_memory_authenticates(local_home, server, client_key, monkeypatch):
    monkeypatch.setenv("TERMINAL_SSH_PRIVATE_KEY", client_key.export_private_key().decode())

    env = connect(server, sync_files=False)
    try:
        result = env.execute("echo in-memory")
    finally:
        env.cleanup()

    assert result["output"].strip() == "in-memory"


def test_the_hermes_home_is_synced_to_the_remote_side(local_home, server, key_file):
    skill = local_home / ".hermes" / "skills" / "probe" / "SKILL.md"
    skill.parent.mkdir(parents=True)
    skill.write_text("---\nname: probe\n---\n", encoding="utf-8")

    env = connect(server, key_file)
    env.cleanup()

    assert (server.remote_home / ".hermes" / "skills" / "probe" / "SKILL.md").read_text(encoding="utf-8").startswith("---")


def test_a_command_that_reads_stdin_gets_end_of_file_not_a_hang(local_home, server, key_file):
    # OpenSSH's client gives a command DEVNULL; an open stdin would block `cat` until the timeout.
    env = connect(server, key_file, sync_files=False)
    try:
        result = env.execute("cat; echo done", timeout=20)
    finally:
        env.cleanup()

    assert result["output"].strip() == "done"


BAD_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nbm90LWEta2V5LXNlY3JldA==\n-----END OPENSSH PRIVATE KEY-----\n"


def test_a_key_that_cannot_be_read_is_a_connection_error_that_names_its_source(local_home, server, tmp_path, client_key,
                                                                               monkeypatch):
    monkeypatch.setenv("TERMINAL_SSH_PRIVATE_KEY", BAD_KEY)
    with pytest.raises(EnvironmentConnectionError) as in_memory:
        connect(server, sync_files=False)
    assert "TERMINAL_SSH_PRIVATE_KEY" in str(in_memory.value) and "bm90LWEta2V5" not in str(in_memory.value)

    locked = tmp_path / "id_locked"
    client_key.write_private_key(str(locked), "pkcs8-pem", passphrase="secret")
    with pytest.raises(EnvironmentConnectionError) as encrypted:
        connect(server, locked, sync_files=False)
    assert str(locked) in str(encrypted.value)


def test_a_key_that_turns_unreadable_fails_a_command_as_a_connection_failure(local_home, server, client_key, monkeypatch):
    monkeypatch.setenv("TERMINAL_SSH_PRIVATE_KEY", client_key.export_private_key().decode())
    env = connect(server, sync_files=False)
    try:
        monkeypatch.setenv("TERMINAL_SSH_PRIVATE_KEY", BAD_KEY)
        conn = env._conn

        async def drop():  # on the loop's thread: the next command must reconnect, with the key as it is now
            conn.close()
            await conn.wait_closed()

        env._call(drop(), 5, "drop")
        result = env.execute("echo unreachable", timeout=20)
        handle = env._run_bash("echo unreachable")  # the primitive itself, not only what execute() makes of it
        handle.wait(10)
        with handle.stdout as out:
            bash_output = out.read()
    finally:
        env.cleanup()

    assert result["returncode"] == 255
    assert "TERMINAL_SSH_PRIVATE_KEY" in result["output"] and "bm90LWEta2V5" not in result["output"]
    assert handle.returncode == 255 and "TERMINAL_SSH_PRIVATE_KEY" in bash_output


def test_a_refused_channel_keeps_the_connection_and_cleanup_closes_it(local_home, server, key_file, monkeypatch):
    env = connect(server, key_file, sync_files=False)
    first = env._conn
    real_run = asyncssh.SSHClientConnection.run
    refused = []

    async def refuse_once(self, *args, **kwargs):
        if not refused:
            refused.append(True)
            raise asyncssh.ChannelOpenError(asyncssh.OPEN_ADMINISTRATIVELY_PROHIBITED, "no more sessions")
        return await real_run(self, *args, **kwargs)

    monkeypatch.setattr(asyncssh.SSHClientConnection, "run", refuse_once)
    try:
        failed = env.execute("echo one", timeout=20)
        ok = env.execute("echo two", timeout=20)
        still = env._conn
    finally:
        env.cleanup()

    assert failed["returncode"] == 255 and ok["output"].strip() == "two"
    assert still is first  # the live connection was kept, not dropped and replaced
    assert first.is_closed()
