"""'Search this network': OpenAI-compatible servers on the phone's Wi-Fi network.

Only when the user taps it: the phone's /24 at most (254 hosts), the known ports, a short connect check, then
each open port asked once for its models through the core's ``probe_api_models``, a few side by side. The
whole scan has one deadline: a port still unanswered then is left out, so a hung server cannot hold the
scan (or the bridge's network thread, ``blocking`` in the contract) longer than that.
"""

from __future__ import annotations

import asyncio
import contextvars
import ipaddress
from concurrent.futures import ThreadPoolExecutor

from hermes_android.endpoints import KNOWN_PORTS


def hosts(cidr: str) -> list[str]:
    """The addresses to search for ``cidr`` (the phone's own address with its prefix): its network's hosts,
    clamped to the address's /24 when the prefix is wider. IPv4 only (``ValueError`` otherwise)."""
    iface = ipaddress.ip_interface(cidr)
    if iface.version != 4:
        raise ValueError(f"not an IPv4 network: {cidr}")
    network = iface.network if iface.network.prefixlen >= 24 else ipaddress.ip_interface(f"{iface.ip}/24").network
    return [str(h) for h in network.hosts()]


def _models(base_url: str, timeout: float) -> tuple[str, list[str]]:
    """The models a server at ``base_url`` lists, asked once with no key (a scan never sends one)."""
    from hermes_cli.models import probe_api_models

    result = probe_api_models(None, base_url, timeout=timeout)
    return result.get("resolved_base_url") or base_url, [m for m in result.get("models") or [] if m]


async def _scan(addresses: list[str], ports, connect_timeout: float, at_once: int, probe_timeout: float,
                deadline: float, probes: ThreadPoolExecutor) -> list[dict]:
    loop = asyncio.get_running_loop()
    connects, found = asyncio.Semaphore(at_once), {}

    async def check(order: int, host: str, port: int, kind: str) -> None:
        async with connects:
            try:
                _reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), connect_timeout)
            except OSError:  # refused, unreachable, or wait_for's TimeoutError (an OSError)
                return
            writer.close()
        # The probe runs in the caller's context (profile scope), on the scan's own small pool.
        call = contextvars.copy_context().run
        base_url, models = await loop.run_in_executor(probes, call, _models, f"http://{host}:{port}/v1", probe_timeout)
        if models:
            found[order] = {"base_url": base_url, "kind": kind, "models": models}

    targets = [(h, p, k) for h in addresses for p, k in ports]
    tasks = [asyncio.create_task(check(i, *target)) for i, target in enumerate(targets)]
    _done, pending = await asyncio.wait(tasks, timeout=deadline)
    for task in pending:
        task.cancel()
    await asyncio.gather(*pending, return_exceptions=True)
    return [found[i] for i in sorted(found)]


def discover(cidr: str, ports=KNOWN_PORTS, connect_timeout: float = 0.4, at_once: int = 64,
             probe_timeout: float = 2.0, deadline: float = 12.0) -> list[dict]:
    """``[{base_url, kind, models}]``, in scan order, for every open port among ``hosts(cidr)`` × ``ports``
    whose server lists models within ``deadline`` seconds of the start; ``kind`` is the scanned port's."""
    addresses = hosts(cidr)
    probes = ThreadPoolExecutor(max_workers=8, thread_name_prefix="hermes-discovery")
    try:
        return asyncio.run(_scan(addresses, ports, connect_timeout, at_once, probe_timeout, deadline, probes))
    finally:
        # A probe still waiting on a hung server ends by its own timeout; the scan does not wait for it.
        probes.shutdown(wait=False, cancel_futures=True)
