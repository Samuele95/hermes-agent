"""The app's 'Search this network': the phone's Wi-Fi /24, the known ports, each open port probed for models."""

from __future__ import annotations

import asyncio
import importlib
import json
import time

import pytest

from tests.android.fake_endpoint import FakeEndpoint, closed_port, silent_listener

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

    assert set(reply) == {"error"} and isinstance(reply["error"], str)


def test_a_failure_inside_the_scan_is_not_read_as_a_bad_address_range(home, monkeypatch):
    import hermes_android

    def broken(_cidr):
        raise ValueError("a bug further down")

    monkeypatch.setattr(discovery, "discover", broken)
    with pytest.raises(ValueError, match="a bug further down"):
        hermes_android.call("discover_endpoints", json.dumps({"cidr": "192.168.1.37/24"}))


def test_a_port_that_never_answers_does_not_hold_the_scan(home):
    """Open ports are probed side by side, once each (no second look at why one failed): a hung server
    costs its own probe, not the scan's."""
    good, silent = FakeEndpoint(models=["m1"]), silent_listener()
    try:
        started = time.monotonic()
        found = discovery.discover("127.0.0.1/32", ports=((silent.getsockname()[1], "vllm"), (good.port, "ollama")))
        elapsed = time.monotonic() - started
    finally:
        good.close()
        silent.close()

    assert [f["base_url"] for f in found] == [f"http://127.0.0.1:{good.port}/v1"]
    assert elapsed < 6.0


def test_the_scan_ends_at_its_deadline(home):
    good, silent = FakeEndpoint(models=["m1"]), silent_listener()
    try:
        started = time.monotonic()
        found = discovery.discover("127.0.0.1/32", ports=((silent.getsockname()[1], "vllm"), (good.port, "ollama")),
                                   deadline=1.0)
        elapsed = time.monotonic() - started
    finally:
        good.close()
        silent.close()

    assert [f["kind"] for f in found] == ["ollama"]
    assert elapsed < 1.8


def test_a_scan_sends_no_key(home):
    good = FakeEndpoint(models=["m1"])
    try:
        discovery.discover("127.0.0.1/32", ports=((good.port, "ollama"),))
    finally:
        good.close()

    assert good.seen_auth and set(good.seen_auth) == {None}


def test_a_connect_that_hangs_is_given_up_quickly(home, monkeypatch):
    async def never(*_args, **_kwargs):
        await asyncio.Event().wait()  # a connect no one ever answers

    monkeypatch.setattr(discovery.asyncio, "open_connection", never)
    started = time.monotonic()

    assert discovery.discover("10.0.0.5/30", ports=((11434, "ollama"),)) == []
    assert time.monotonic() - started < 1.5


def test_no_more_than_at_once_connects_are_in_flight(home, monkeypatch):
    in_flight, peak = 0, 0

    async def refused(*_args, **_kwargs):
        nonlocal in_flight, peak
        in_flight += 1
        peak = max(peak, in_flight)
        await asyncio.sleep(0)  # let the other connects start while this one is open
        in_flight -= 1
        raise ConnectionRefusedError

    monkeypatch.setattr(discovery.asyncio, "open_connection", refused)

    assert discovery.discover("192.168.1.37/24", ports=((11434, "ollama"),)) == []
    assert 1 < peak <= 64


def test_an_ipv6_network_is_not_searched():
    """An IPv6 prefix is /64 at the narrowest a phone gets: no host list to walk within the 254-host bound."""
    with pytest.raises(ValueError):
        discovery.hosts("fe80::1/64")
