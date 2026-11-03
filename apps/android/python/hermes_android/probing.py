"""'Find server': the OpenAI-compatible server behind an address the user typed, and its models.

A URL is probed as given, ``host:port`` on that port, a bare host on each known local-server port. A short
connect check comes first, then the core's own ``probe_api_models``, the one way this module asks a server
for its models; when none come back, the status of ``/models`` says why (a key needed, refused, no models).
Runs on the bridge's network thread (``blocking`` in the contract), never on the core's frames.
"""

from __future__ import annotations

import enum
import ipaddress
import socket
import urllib.error
import urllib.parse
import urllib.request
from typing import NamedTuple

KNOWN_PORTS = ((11434, "ollama"), (1234, "lmstudio"), (8080, "llamacpp"), (8000, "vllm"))
FOUND = "found"
NOT_AN_ADDRESS = "That is not a server address: use a host name, host:port or an http(s) URL."


class Outcome(enum.IntEnum):
    """Why a probe found no models, ordered so that the most telling failure wins when several candidates
    fail: a server that answered beats one that did not. ``found`` is no failure, so it is :data:`FOUND`."""

    UNREACHABLE = 0
    NO_MODELS = 1
    REFUSED = 2
    NEEDS_KEY = 3

    @property
    def wire(self) -> str:
        """The contract's spelling (``EndpointProbe``)."""
        return self.name.lower()


_BY_STATUS = {401: Outcome.NEEDS_KEY, 403: Outcome.REFUSED, 200: Outcome.NO_MODELS}


def kind(port: int | None) -> str:
    """The local server a known port names (``"server"`` for any other port)."""
    return dict(KNOWN_PORTS).get(port or 0, "server")


def models_at(base_url: str, api_key: str | None, timeout: float) -> tuple[str, list[str]]:
    """The base URL the server answered at and the models it lists, asked once through the core's probe."""
    from hermes_cli.models import probe_api_models

    result = probe_api_models(api_key or None, base_url, timeout=timeout)
    return result.get("resolved_base_url") or base_url, [m for m in result.get("models") or [] if m]


class Address(NamedTuple):
    """Where Find server looks: ``host`` (never empty; an IPv6 literal without its brackets), ``port`` (None for
    a bare host, tried on the known ports) and ``url`` (a typed http(s) URL, probed as given)."""

    host: str
    port: int | None
    url: str | None


_NOT_IN_A_HOST = frozenset(":/@?#[]")


def _port(text: str) -> int | None:
    return int(text) if text.isascii() and text.isdigit() and 0 < int(text) <= 65535 else None


def parse_address(text: str) -> Address | None:
    """The address the user typed, or None when it names no server: an http(s) URL with a host, a bare host,
    ``host:port`` or a bracketed IPv6 literal (``[v6]``, ``[v6]:port``); port 1..65535."""
    text = text.strip().rstrip("/")
    if "://" in text:
        try:
            parsed = urllib.parse.urlparse(text)
            port = parsed.port  # ValueError: out of range, not a number, an unclosed or unbracketed IPv6 host
        except ValueError:
            return None
        if parsed.scheme not in ("http", "https") or not parsed.hostname or port == 0:
            return None
        return Address(parsed.hostname, port, text)
    if text.startswith("["):
        host, closed, rest = text[1:].partition("]")
        try:
            ipaddress.IPv6Address(host)
        except ValueError:
            return None
        if not closed or (rest and not rest.startswith(":")):
            return None
    else:
        host = text.partition(":")[0]
        if not host or any(c.isspace() or c in _NOT_IN_A_HOST for c in host):
            return None
        rest = text[len(host):]
    if not rest:
        return Address(host, None, None)
    port = _port(rest[1:])  # more than one ':' outside brackets (an unbracketed IPv6 literal) is no port
    return Address(host, port, None) if port else None


def _netloc(host: str, port: int) -> str:
    return f"[{host}]:{port}" if ":" in host else f"{host}:{port}"


def candidates(address: Address, ports) -> list[tuple[str, str]]:
    """The base URLs to try for ``address``, each with the kind of server its port names."""
    if address.url:
        return [(address.url, kind(address.port))]
    if address.port:
        return [(f"http://{_netloc(address.host, address.port)}/v1", kind(address.port))]
    return [(f"http://{_netloc(address.host, p)}/v1", name) for p, name in ports]


def _open(base_url: str, timeout: float) -> bool:
    parsed = urllib.parse.urlparse(base_url)
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        with socket.create_connection((parsed.hostname, port), timeout=min(timeout, 0.5)):
            return True
    except OSError:
        return False


def _toggle_v1(base_url: str) -> str:
    """The same base with ``/v1`` added or removed (``probe_api_models``'s own heuristic): the base a
    server answering only the other form would need."""
    trimmed = base_url.rstrip("/")
    return trimmed[:-3].rstrip("/") if trimmed.endswith("/v1") else trimmed + "/v1"


def status(base_url: str, api_key: str | None, timeout: float) -> int | None:
    """The HTTP status of ``<base>/models``, or of its ``/v1``-toggled variant when the first is a plain
    404 (None when neither answered): why a probe found no models, even when the server serves only the
    other form of the base URL (a bare base given without ``/v1``, or the reverse)."""
    headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
    answer: int | None = None
    for candidate in dict.fromkeys((base_url, _toggle_v1(base_url))):
        try:
            with urllib.request.urlopen(urllib.request.Request(candidate.rstrip("/") + "/models", headers=headers),
                                        timeout=timeout) as resp:
                return resp.status
        except urllib.error.HTTPError as answered:
            answered.close()  # an HTTPError holds the open response
            answer = answered.code
            if answered.code != 404:
                return answer
        except (urllib.error.URLError, OSError):
            continue
    return answer


def probe(address: str, api_key: str | None = None, ports=KNOWN_PORTS, timeout: float = 5.0) -> dict:
    """Finds the OpenAI-compatible server behind ``address`` (a URL, ``host:port`` or a bare host tried on
    ``ports``) and its models, through the core's own probe. ``api_key`` is used for this probe only. An
    address that names no server is answered ``{error}`` in words before anything is asked."""
    where = parse_address(address)
    if where is None:
        return {"error": NOT_AN_ADDRESS}
    outcome = Outcome.UNREACHABLE
    for base, name in candidates(where, ports):
        if not _open(base, timeout):
            continue
        resolved, models = models_at(base, api_key, timeout)
        if models:
            return {"outcome": FOUND, "base_url": resolved, "kind": name, "models": models}
        outcome = max(outcome, _BY_STATUS.get(status(resolved, api_key, timeout) or 0, Outcome.UNREACHABLE))
    return {"outcome": outcome.wire, "base_url": None, "kind": None, "models": []}
