"""'Search this network': OpenAI-compatible servers on the phone's Wi-Fi network.

Only when the user taps it: the phone's /24 at most (254 hosts), the known ports, a short connect check, and
each open port probed for its models through :func:`hermes_android.endpoints.probe`.
"""

from __future__ import annotations

import asyncio
import ipaddress

from hermes_android.endpoints import KNOWN_PORTS, probe


def hosts(cidr: str) -> list[str]:
    """The addresses to search for ``cidr`` (the phone's own address with its prefix): its network's hosts,
    clamped to the address's /24 when the prefix is wider. IPv4 only (``ValueError`` otherwise)."""
    iface = ipaddress.ip_interface(cidr)
    if iface.version != 4:
        raise ValueError(f"not an IPv4 network: {cidr}")
    network = iface.network if iface.network.prefixlen >= 24 else ipaddress.ip_interface(f"{iface.ip}/24").network
    return [str(h) for h in network.hosts()]


async def _open_ports(addresses: list[str], ports, connect_timeout: float, at_once: int) -> list[tuple[str, int, str]]:
    gate = asyncio.Semaphore(at_once)

    async def check(host: str, port: int, kind: str):
        async with gate:
            try:
                _reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), connect_timeout)
            except OSError:  # refused, unreachable, or wait_for's TimeoutError (an OSError)
                return None
            writer.close()
            return host, port, kind

    found = await asyncio.gather(*(check(h, p, k) for h in addresses for p, k in ports))
    return [hit for hit in found if hit]


def discover(cidr: str, ports=KNOWN_PORTS, connect_timeout: float = 0.4, at_once: int = 64) -> list[dict]:
    """``[{base_url, kind, models}]`` for every open port among ``hosts(cidr)`` × ``ports`` whose server lists
    models; ``kind`` is the scanned port's (``probe`` would name it after the port number)."""
    open_ports = asyncio.run(_open_ports(hosts(cidr), ports, connect_timeout, at_once))
    found = []
    for host, port, kind in open_ports:
        result = probe(f"{host}:{port}", timeout=3.0)
        if result["outcome"] == "found":
            found.append({"base_url": result["base_url"], "kind": kind, "models": result["models"]})
    return found
