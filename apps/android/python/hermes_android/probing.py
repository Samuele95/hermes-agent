"""'Find server': the OpenAI-compatible server behind an address the user typed, and its models.

A URL is probed as given, ``host:port`` on that port, a bare host on each known local-server port. A short
connect check comes first, then the core's own ``probe_api_models``, the one way this module asks a server
for its models; when none come back, the status of ``/models`` says why (a key needed, refused, no models).
Runs on the bridge's network thread (``blocking`` in the contract), never on the core's frames.
"""

from __future__ import annotations

import enum
import socket
import urllib.error
import urllib.parse
import urllib.request

KNOWN_PORTS = ((11434, "ollama"), (1234, "lmstudio"), (8080, "llamacpp"), (8000, "vllm"))
FOUND = "found"


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


def _candidates(address: str, ports) -> list[tuple[str, str]]:
    text = address.strip().rstrip("/")
    if "://" in text:
        return [(text, kind(urllib.parse.urlparse(text).port))]
    host, sep, port = text.rpartition(":")
    if sep and port.isdigit() and host:
        return [(f"http://{host}:{port}/v1", kind(int(port)))]
    return [(f"http://{text}:{p}/v1", name) for p, name in ports]


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
    ``ports``) and its models, through the core's own probe. ``api_key`` is used for this probe only."""
    outcome = Outcome.UNREACHABLE
    for base, name in _candidates(address, ports):
        if not _open(base, timeout):
            continue
        resolved, models = models_at(base, api_key, timeout)
        if models:
            return {"outcome": FOUND, "base_url": resolved, "kind": name, "models": models}
        outcome = max(outcome, _BY_STATUS.get(status(resolved, api_key, timeout) or 0, Outcome.UNREACHABLE))
    return {"outcome": outcome.wire, "base_url": None, "kind": None, "models": []}
