"""The app's 'Find server': the server behind an address the user typed, found through the core's own probe."""

from __future__ import annotations

import importlib
import inspect
import json
from pathlib import Path

import pytest

from tests.android.fake_endpoint import closed_port

probing = importlib.import_module("hermes_android.probing")
from hermes_android.probing import NOT_AN_ADDRESS, Outcome

CONTRACT = json.loads((Path(inspect.getfile(probing)).parent / "bridge_contract.json").read_text(encoding="utf-8"))


@pytest.fixture
def home(tmp_path, monkeypatch):
    monkeypatch.setenv("HERMES_HOME", str(tmp_path))
    return tmp_path


def probe(address, **kw):
    return probing.probe(address, timeout=2.0, **kw)


def test_a_bare_host_is_found_on_the_first_known_port_that_lists_models(home, servers):
    ollama = servers(models=["gemma4:12b-it-q8_0", "gemma3:12b-it-q8_0"])

    found = probe("127.0.0.1", ports=((closed_port(), "ollama"), (ollama.port, "lmstudio")))

    assert found == {"found": {"base_url": f"http://127.0.0.1:{ollama.port}/v1", "kind": "lmstudio",
                               "models": ["gemma4:12b-it-q8_0", "gemma3:12b-it-q8_0"]}, "failure": None}


def test_a_full_url_is_probed_as_given_and_its_v1_is_fixed(home, servers):
    server = servers(models=["m1"])

    found = probe(f"http://127.0.0.1:{server.port}")  # no /v1: the core's probe tries it

    assert found["failure"] is None and found["found"]["models"] == ["m1"]
    assert found["found"]["base_url"].startswith(f"http://127.0.0.1:{server.port}")


def test_a_host_with_a_port_is_tried_on_that_port_only(home, servers):
    server = servers(models=["m1"])

    found = probe(f"127.0.0.1:{server.port}", ports=((closed_port(), "ollama"),))

    assert found["failure"] is None and found["found"]["base_url"] == f"http://127.0.0.1:{server.port}/v1"


def test_a_server_behind_a_key_needs_it_and_answers_with_it(home, servers):
    server = servers(models=["m1"], key="sk-test-123")
    url = f"http://127.0.0.1:{server.port}/v1"

    assert probe(url)["failure"] == "needs_key"
    assert probe(url, api_key="sk-test-123")["found"]["models"] == ["m1"]
    assert "Bearer sk-test-123" in server.seen_auth


def test_a_refusing_server_says_refused_not_needs_key(home, servers):
    server = servers(status=403)

    assert probe(f"http://127.0.0.1:{server.port}/v1")["failure"] == "refused"


def test_a_full_url_without_v1_against_a_key_protected_server_needs_the_key(home, servers):
    # The server only answers /v1/models (as real Ollama/LM Studio/llama.cpp/vLLM do); a bare /models 404s.
    # The address is given without /v1, so the status check must also look at the /v1-toggled variant.
    server = servers(models=["m1"], key="sk-test-123")

    assert probe(f"http://127.0.0.1:{server.port}")["failure"] == "needs_key"


def test_a_full_url_without_v1_against_a_refusing_server_is_refused(home, servers):
    server = servers(status=403)

    assert probe(f"http://127.0.0.1:{server.port}")["failure"] == "refused"


def test_nothing_listening_is_unreachable_and_an_empty_list_is_no_models(home, servers):
    empty = servers(models=[])

    assert probe("127.0.0.1", ports=((closed_port(), "ollama"),)) == {"found": None, "failure": "unreachable"}
    assert probe(f"http://127.0.0.1:{empty.port}/v1")["failure"] == "no_models"


def test_the_key_never_comes_back_in_the_reply(home, servers):
    server = servers(models=["m1"], key="sk-test-123")

    reply = probing.probe(f"http://127.0.0.1:{server.port}/v1", api_key="sk-test-123", timeout=2.0)

    assert "sk-test-123" not in json.dumps(reply)


def test_the_bridge_call_answers_its_declared_reply(home, servers):
    import hermes_android
    server = servers(models=["m1"])

    reply = json.loads(hermes_android.call("probe_endpoint",
                                           json.dumps({"address": f"http://127.0.0.1:{server.port}/v1", "api_key": None})))

    assert reply["found"]["models"] == ["m1"] and reply["failure"] is None


def test_reading_why_a_probe_failed_closes_every_answer(home, servers):
    import gc
    import warnings

    from hermes_android.probing import status

    refusing = servers(status=403)
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always", ResourceWarning)
        assert status(f"http://127.0.0.1:{refusing.port}", None, 2.0) == 403
        gc.collect()

    assert [w for w in caught if issubclass(w.category, ResourceWarning)] == []


def test_the_probes_outcomes_are_the_contracts():
    assert {o.wire for o in Outcome} == set(CONTRACT["enums"]["EndpointProbe"])
    assert sorted(Outcome) == [Outcome.UNREACHABLE, Outcome.NO_MODELS, Outcome.REFUSED, Outcome.NEEDS_KEY]


def test_every_kind_a_probe_names_is_the_contracts():
    assert {name for _, name in probing.KNOWN_PORTS} | {probing.OTHER_SERVER} == set(CONTRACT["enums"]["ServerKind"])
    assert probing.kind(65000) == probing.OTHER_SERVER


@pytest.mark.parametrize("server", ["listing", "keyed", "refusing", "empty", "closed"])
def test_a_reply_is_a_server_or_a_failure_never_both(home, servers, server):
    made = {"listing": lambda: servers(models=["m1"]).port,
            "keyed": lambda: servers(models=["m1"], key="sk-test-123").port,
            "refusing": lambda: servers(status=403).port,
            "empty": lambda: servers(models=[]).port,
            "closed": closed_port}

    reply = probe(f"http://127.0.0.1:{made[server]()}/v1")

    assert set(reply) == {"found", "failure"}
    assert (reply["found"] is None) != (reply["failure"] is None), reply


# Each row puts its strongest failure before a weaker one, so 'the last failure wins' cannot pass.
@pytest.mark.parametrize(("ports", "expected"), [
    (("keyed", "empty", "closed"), "needs_key"),
    (("refusing", "keyed"), "needs_key"),
    (("empty", "refusing"), "refused"),
    (("closed", "empty"), "no_models"),
])
def test_the_most_telling_failure_wins_across_ports(home, servers, ports, expected):
    made = {"keyed": lambda: servers(models=["m1"], key="sk-test-123").port,
            "empty": lambda: servers(models=[]).port,
            "refusing": lambda: servers(status=403).port,
            "closed": closed_port}

    assert probe("127.0.0.1", ports=tuple((made[name](), "ollama") for name in ports))["failure"] == expected


@pytest.fixture
def reached(monkeypatch):
    """Records every connection and every core probe a probe makes (connections still go through)."""
    import hermes_cli.models

    seen = {"connections": [], "probes": []}
    connect, core_probe = probing.socket.create_connection, hermes_cli.models.probe_api_models

    def connection(where, *args, **kw):
        seen["connections"].append(where)
        return connect(where, *args, **kw)

    def probed(*args, **kw):
        seen["probes"].append(args)
        return core_probe(*args, **kw)

    monkeypatch.setattr(probing.socket, "create_connection", connection)
    monkeypatch.setattr(hermes_cli.models, "probe_api_models", probed)
    return seen


@pytest.mark.parametrize("address", ["", "http://", "file:///x", "ftp://127.0.0.1", "::1", "fe80::1", "host:",
                                     "127.0.0.1:0", "http://127.0.0.1:99999", "http://[::1", "a b", "user@host"])
def test_an_address_that_names_no_server_is_refused_in_words(home, reached, address):
    assert probe(address, ports=((closed_port(), "ollama"),)) == {"error": NOT_AN_ADDRESS}
    assert reached == {"connections": [], "probes": []}


def test_an_empty_address_never_reaches_a_server_on_the_phone(home, servers, reached):
    # An empty host resolves to the loopback: on the phone (adb reverse) that is the host's Ollama.
    server = servers(models=["m1"])

    assert probing.probe("", ports=((server.port, "ollama"),), timeout=2.0) == {"error": NOT_AN_ADDRESS}
    assert server.seen_auth == [] and reached["connections"] == []


def test_a_bracketed_ipv6_host_is_probed_there(home, servers):
    try:
        server = servers(models=["m1"], host="::1")
    except OSError as unbound:
        pytest.skip(f"this host cannot bind ::1 ({unbound}): the brackets are not proven here")
    ports = ((server.port, "ollama"),)

    for address in (f"[::1]:{server.port}", f"http://[::1]:{server.port}/v1", "[::1]"):
        found = probe(address, ports=ports)
        assert found["failure"] is None, address
        assert found["found"]["base_url"] == f"http://[::1]:{server.port}/v1", address


def test_the_bridge_call_answers_a_refusal_as_its_error(home):
    import hermes_android

    assert json.loads(hermes_android.call("probe_endpoint", '{"address":"http://","api_key":null}')) == {
        "error": NOT_AN_ADDRESS}
