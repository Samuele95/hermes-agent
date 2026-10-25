"""The app's side of SSH: an identity that never touches disk, and a host trusted only once the user has
seen its fingerprint (D22). The private key goes to the Android keystore (TERMINAL_SSH_PRIVATE_KEY,
exported by the keystore secret source); the host and user go to config.yaml, never ``.env``.

Driven against a real asyncssh server in this process (``tests/fakes/fake_ssh_server.py``).
"""

from __future__ import annotations

import importlib
import json

import pytest

asyncssh = pytest.importorskip("asyncssh")  # the optional dependency; the module under test must import

from tests.fakes.fake_ssh_server import FakeSSHServer
from tools.environments import asyncssh_env

hermes_android = importlib.import_module("hermes_android")


@pytest.fixture
def home(tmp_path, monkeypatch):
    home = tmp_path / "files"
    (home / ".hermes").mkdir(parents=True)
    (home / ".hermes" / "config.yaml").write_text("model:\n  default: some/model  # the user's\n", encoding="utf-8")
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("HERMES_HOME", str(home / ".hermes"))
    monkeypatch.delenv("TERMINAL_SSH_PRIVATE_KEY", raising=False)
    return home


@pytest.fixture
def client_key():
    return asyncssh.generate_private_key("ssh-ed25519")


@pytest.fixture
def server(tmp_path, client_key):
    remote = tmp_path / "remote"
    remote.mkdir()
    running = FakeSSHServer(remote, client_key)
    yield running
    running.close()


def call(function: str, **request) -> dict:
    return json.loads(getattr(hermes_android, function)(json.dumps(request)))


def test_a_new_identity_is_an_ed25519_pair_and_nothing_is_written(home):
    before = sorted(home.rglob("*"))

    pair = call("ssh_new_key")

    private = asyncssh.import_private_key(pair["private"])
    assert pair["public"].startswith("ssh-ed25519 ")
    assert private.export_public_key().decode().split()[1] == pair["public"].split()[1]
    assert sorted(home.rglob("*")) == before


def test_looking_at_a_new_host_shows_its_fingerprint_and_pins_nothing(home, server):
    seen = call("ssh_host_key", host="127.0.0.1", port=server.port)

    assert seen["fingerprint"] == server.host_key.get_fingerprint("sha256")
    assert seen["pinned"] == "no"
    assert not asyncssh_env.known_hosts_path().exists()


def test_a_trusted_host_is_pinned_and_the_terminal_backend_connects_with_the_app_key(home, server, client_key, monkeypatch):
    seen = call("ssh_host_key", host="127.0.0.1", port=server.port)

    assert call("ssh_trust_host", host="127.0.0.1", port=server.port, key=seen["key"]) == {"pinned": "yes"}

    assert call("ssh_host_key", host="127.0.0.1", port=server.port)["pinned"] == "yes"
    monkeypatch.setenv("TERMINAL_SSH_PRIVATE_KEY", client_key.export_private_key().decode())
    env = asyncssh_env.AsyncsshSSHEnvironment(host="127.0.0.1", user="delta", port=server.port, sync_files=False)
    try:
        assert env.execute("echo reached")["output"].strip() == "reached"
    finally:
        env.cleanup()


def test_a_host_whose_key_changed_is_reported_and_never_re_pinned(home, server):
    asyncssh_env.pin_host_key("127.0.0.1", server.port, asyncssh.generate_private_key("ssh-ed25519"))
    pinned_before = asyncssh_env.known_hosts_path().read_text(encoding="utf-8")

    seen = call("ssh_host_key", host="127.0.0.1", port=server.port)
    refused = call("ssh_trust_host", host="127.0.0.1", port=server.port, key=seen["key"])

    assert seen["pinned"] == "changed"
    assert "error" in refused
    assert asyncssh_env.known_hosts_path().read_text(encoding="utf-8") == pinned_before


def test_an_unreachable_host_is_reported_in_words(home):
    seen = call("ssh_host_key", host="127.0.0.1", port=9)  # discard: nothing listens

    assert "error" in seen and "127.0.0.1" in seen["error"]


def test_the_terminal_is_pointed_at_the_host_in_config_yaml_only(home, capsys, monkeypatch):
    for name in ("TERMINAL_ENV", "TERMINAL_SSH_HOST", "TERMINAL_SSH_USER", "TERMINAL_SSH_PORT"):
        monkeypatch.delenv(name, raising=False)

    assert call("ssh_configure", host="host.example", user="delta", port=2222) == {"backend": "ssh"}

    assert capsys.readouterr().out == ""  # stdout is a logcat stream in the app
    assert not (home / ".hermes" / ".env").exists()
    assert "# the user's" in (home / ".hermes" / "config.yaml").read_text(encoding="utf-8")
    # The running core sees it at once: terminal tools read these from the environment.
    import os
    assert (os.environ["TERMINAL_ENV"], os.environ["TERMINAL_SSH_HOST"], os.environ["TERMINAL_SSH_USER"],
            os.environ["TERMINAL_SSH_PORT"]) == ("ssh", "host.example", "delta", "2222")


def test_an_incomplete_host_is_refused_without_touching_the_config(home):
    before = (home / ".hermes" / "config.yaml").read_text(encoding="utf-8")

    refused = call("ssh_configure", host="", user="delta", port=22)

    assert "error" in refused
    assert (home / ".hermes" / "config.yaml").read_text(encoding="utf-8") == before


def test_the_public_key_is_read_back_from_the_identity_the_keystore_exports(home, monkeypatch):
    assert "error" in call("ssh_public_key")  # no identity yet
    pair = call("ssh_new_key")
    monkeypatch.setenv("TERMINAL_SSH_PRIVATE_KEY", pair["private"])

    assert call("ssh_public_key") == {"public": pair["public"]}


def test_a_login_check_succeeds_only_on_a_pinned_host_that_accepts_the_app_key(home, server, client_key, monkeypatch):
    target = {"host": "127.0.0.1", "user": "delta", "port": server.port}
    monkeypatch.setenv("TERMINAL_SSH_PRIVATE_KEY", client_key.export_private_key().decode())

    assert "error" in call("ssh_check_login", **target)  # not pinned yet: never trusted on the way
    assert not asyncssh_env.known_hosts_path().exists()
    seen = call("ssh_host_key", host="127.0.0.1", port=server.port)
    call("ssh_trust_host", host="127.0.0.1", port=server.port, key=seen["key"])

    assert call("ssh_check_login", **target) == {"ok": True}

    monkeypatch.setenv("TERMINAL_SSH_PRIVATE_KEY", asyncssh.generate_private_key("ssh-ed25519").export_private_key().decode())
    assert "error" in call("ssh_check_login", **target)  # a key the host does not know


@pytest.mark.parametrize("function, extra", [("ssh_host_key", {}), ("ssh_trust_host", {"key": "ssh-ed25519 AAAA"}),
                                             ("ssh_check_login", {"user": "delta"})])
@pytest.mark.parametrize("host, port", [("", 22), ("host.example", 70000), ("host.example", 0), ("host.example", "ssh")])
def test_every_entry_refuses_a_bad_target_in_words_and_dials_nothing(home, monkeypatch, function, extra, host, port):
    def no_dialling(*args, **kwargs):
        raise AssertionError("a bad target was dialled")

    monkeypatch.setattr(asyncssh, "get_server_host_key", no_dialling)
    monkeypatch.setattr(asyncssh, "connect", no_dialling)

    answer = call(function, host=host, port=port, **extra)

    assert answer == {"error": "An SSH host needs a host name, a user and a port between 1 and 65535."}
    assert not asyncssh_env.known_hosts_path().exists()


def test_the_terminal_says_where_it_runs(home):
    assert call("terminal_status") == {"ssh_host": None}  # a fresh app: on the phone

    call("ssh_configure", host="host.example", user="delta", port=2222)

    assert call("terminal_status") == {"ssh_host": "delta@host.example:2222"}


def test_the_terminal_comes_back_to_the_phone_in_config_yaml_and_the_running_core(home, capsys, monkeypatch):
    monkeypatch.delenv("TERMINAL_ENV", raising=False)
    call("ssh_configure", host="host.example", user="delta", port=2222)

    assert call("terminal_local") == {"backend": "local"}

    assert capsys.readouterr().out == ""
    assert not (home / ".hermes" / ".env").exists()
    config = (home / ".hermes" / "config.yaml").read_text(encoding="utf-8")
    assert "backend: local" in config and "# the user's" in config
    import os
    assert os.environ["TERMINAL_ENV"] == "local"  # the next terminal call runs on the phone
    assert call("terminal_status") == {"ssh_host": None}
