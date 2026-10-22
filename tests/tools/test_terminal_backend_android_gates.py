"""On an Android host the ssh backend is offered when it has a transport: the OpenSSH client, or the
asyncssh extra already installed.

An app embedding the core has no ``ssh``/``scp`` binaries but ships asyncssh; Termux (also
``sys.platform == "android"``) can install openssh. Desktop hosts are untouched: the gate keys on the
host family, passed here as data rather than faking the host. A gate never installs anything.
"""

import tools.terminal_tool_backends as backends


def _ssh_binaries(monkeypatch, present: bool):
    monkeypatch.setattr("tools.environments.ssh.shutil.which", lambda name: f"/usr/bin/{name}" if present else None)


def _asyncssh(monkeypatch, installed: bool):
    import pm.extras

    real = pm.extras.available
    monkeypatch.setattr(pm.extras, "available", lambda extra: installed if extra == "asyncssh" else real(extra))


SSH_CONFIG = {"ssh_host": "host.example", "ssh_user": "delta"}


def test_ssh_backend_on_android_without_any_transport_is_unavailable(monkeypatch):
    _ssh_binaries(monkeypatch, present=False)
    _asyncssh(monkeypatch, installed=False)

    assert backends._ssh_pre(SSH_CONFIG, family="android") is False
    assert "asyncssh" in (backends.terminal_backend_unavailable_reason() or "")


def test_ssh_backend_on_android_with_asyncssh_installed_is_offered(monkeypatch):
    _ssh_binaries(monkeypatch, present=False)
    _asyncssh(monkeypatch, installed=True)

    assert backends._ssh_pre(SSH_CONFIG, family="android") is True


def test_ssh_backend_on_android_with_the_openssh_client_is_offered(monkeypatch):
    _ssh_binaries(monkeypatch, present=True)
    _asyncssh(monkeypatch, installed=False)  # the client alone must be enough

    assert backends._ssh_pre(SSH_CONFIG, family="android") is True


def test_ssh_backend_on_desktop_is_not_gated_on_binaries(monkeypatch):
    _ssh_binaries(monkeypatch, present=False)

    assert backends._ssh_pre(SSH_CONFIG, family="linux") is True
