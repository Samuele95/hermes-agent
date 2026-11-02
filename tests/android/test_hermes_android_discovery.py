"""The app's 'Search this network': the phone's Wi-Fi /24, the known ports, each open port probed for models."""

from __future__ import annotations

import importlib
import json

import pytest

from tests.android.fake_endpoint import FakeEndpoint, closed_port

discovery = importlib.import_module("hermes_android.discovery")


@pytest.fixture
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    return tmp_path


def test_a_wide_prefix_is_clamped_to_the_phones_slash_24():
    assert len(discovery.hosts("192.168.1.37/16")) == 254
    assert discovery.hosts("192.168.1.37/16")[0] == "192.168.1.1"
    assert discovery.hosts("10.0.0.5/30") == ["10.0.0.5", "10.0.0.6"]
    assert discovery.hosts("127.0.0.1/32") == ["127.0.0.1"]


def test_only_open_ports_with_models_are_found(home):
    good, refusing = FakeEndpoint(models=["m1", "m2"]), FakeEndpoint(status=403)
    try:
        found = discovery.discover("127.0.0.1/32", ports=((good.port, "ollama"), (refusing.port, "vllm"),
                                                          (closed_port(), "lmstudio")))
    finally:
        good.close()
        refusing.close()

    assert found == [{"base_url": f"http://127.0.0.1:{good.port}/v1", "kind": "ollama", "models": ["m1", "m2"]}]


def test_a_cidr_that_is_no_network_is_an_error_in_words(home):
    import hermes_android

    reply = json.loads(hermes_android.call("discover_endpoints", json.dumps({"cidr": "not-a-network"})))

    assert "error" in reply


def test_an_ipv6_network_is_not_searched():
    """An IPv6 prefix is /64 at the narrowest a phone gets: no host list to walk within the 254-host bound."""
    with pytest.raises(ValueError):
        discovery.hosts("fe80::1/64")
