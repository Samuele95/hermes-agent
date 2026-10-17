"""On an Android host the ssh backend is offered only when the OpenSSH client resolves.

SSHEnvironment shells out to ``ssh`` and ``scp``. An app embedding the core has neither; Termux (also
``sys.platform == "android"``) can install openssh and keeps the backend. Desktop hosts are untouched:
the gate keys on the host family, passed here as data rather than faking the host.
"""

import tools.terminal_tool_backends as backends


def _ssh_binaries(monkeypatch, present: bool):
    from hermes_platform.resolver import Candidate, Resolution

    def locate(name, ctx=None, *, known_dirs=()):
        return Resolution("path_executable" if present else "missing", (Candidate(f"/usr/bin/{name}", "PATH", present),))

    monkeypatch.setattr("hermes_platform.resolver.locate_command", locate)


SSH_CONFIG = {"ssh_host": "host.example", "ssh_user": "delta"}


def test_ssh_backend_on_android_without_the_openssh_client_is_unavailable(monkeypatch):
    _ssh_binaries(monkeypatch, present=False)

    assert backends._ssh_pre(SSH_CONFIG, family="android") is False
    assert "ssh" in (backends.terminal_backend_unavailable_reason() or "")


def test_ssh_backend_on_android_with_the_openssh_client_is_offered(monkeypatch):
    _ssh_binaries(monkeypatch, present=True)

    assert backends._ssh_pre(SSH_CONFIG, family="android") is True


def test_ssh_backend_on_desktop_is_not_gated_on_binaries(monkeypatch):
    _ssh_binaries(monkeypatch, present=False)

    assert backends._ssh_pre(SSH_CONFIG, family="linux") is True
