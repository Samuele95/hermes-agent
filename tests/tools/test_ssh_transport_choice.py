"""The ssh backend picks its transport: the OpenSSH client when ``ssh`` and ``scp`` resolve, as
always; otherwise asyncssh (installed on first use), so a host without OpenSSH still reaches SSH.
Both environments are stand-ins here: what is pinned is the choice, not a connection."""

import pytest

import tools.terminal_tool_backends as backends

SSH_CONFIG = {"host": "host.example", "user": "delta", "port": 2222, "key": "/keys/id_ed25519"}


@pytest.fixture
def built(monkeypatch):
    made = []

    def stand_in(label):
        return lambda **kwargs: made.append((label, kwargs)) or label

    monkeypatch.setattr(backends, "_SSHEnvironment", stand_in("openssh"))
    pytest.importorskip("asyncssh")  # the optional dependency; the module under test must import
    from tools.environments import asyncssh_env

    monkeypatch.setattr(asyncssh_env, "AsyncsshSSHEnvironment", stand_in("asyncssh"))
    return made


def _openssh(monkeypatch, present: bool):
    monkeypatch.setattr("tools.environments.ssh.shutil.which", lambda name: f"/usr/bin/{name}" if present else None)


def test_the_openssh_client_is_used_whenever_it_is_installed(monkeypatch, built):
    _openssh(monkeypatch, present=True)

    backends._build_ssh_env(cwd="~", timeout=60, ssh_config=SSH_CONFIG)

    assert [label for label, _ in built] == ["openssh"]


def test_without_the_openssh_client_the_asyncssh_transport_takes_the_same_settings(monkeypatch, built):
    _openssh(monkeypatch, present=False)

    backends._build_ssh_env(cwd="~/work", timeout=60, ssh_config=SSH_CONFIG)

    (label, kwargs), = built
    assert label == "asyncssh"
    assert (kwargs["host"], kwargs["user"], kwargs["port"], kwargs["key_path"], kwargs["cwd"]) == (
        "host.example", "delta", 2222, "/keys/id_ed25519", "~/work")
