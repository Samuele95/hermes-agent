"""The always-on messaging gateway, hosted in the app's own ``:gateway`` process (D12).

The desktop runs the gateway under a service manager; here the app's foreground service is that
manager and this module is its in-process half. The gateway runs on a thread of the process, as
``start_gateway`` allows: a planned stop drains it, a restart (``/restart``, exit 75 under
``HERMES_GATEWAY_EXTERNAL_SUPERVISOR``) runs it again in place, and every other end is reported to the
app, which decides what follows. The gateway's own watchdogs may still ``os._exit`` the process; the
service is sticky, so Android starts it again.

The app hears one JSON object per state change: ``running``, ``restarting``, ``stopped`` (planned)
or ``failed`` with a ``reason``.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
from collections.abc import Callable

logger = logging.getLogger(__name__)

_RESTART_EXIT_CODE = 75  # gateway.restart.GATEWAY_SERVICE_RESTART_EXIT_CODE, read without importing the core


def _run_gateway() -> bool:
    import asyncio

    from gateway.run import start_gateway

    return asyncio.run(start_gateway(config=None, replace=False, verbosity=None, force=False))


class GatewayHost:
    """Runs the gateway on its own thread until a planned stop or an end the app must hear about.

    A restart that comes back within ``quick_run_s`` of the previous start waits first, doubling from
    ``first_backoff_s`` up to ``max_backoff_s``, so a gateway that keeps asking to restart (a lock
    another holder keeps, say) cannot spin; :meth:`stop` ends such a wait at once.
    """

    def __init__(self, emit: Callable[[str], object], *, run_gateway: Callable[[], bool] = _run_gateway,
                 quick_run_s: float = 30.0, first_backoff_s: float = 1.0, max_backoff_s: float = 60.0):
        self._emit = emit
        self._run_gateway = run_gateway
        self._quick_run_s = quick_run_s
        self._first_backoff_s = first_backoff_s
        self._max_backoff_s = max_backoff_s
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self._stopping = threading.Event()
        self._state = "stopped"

    def state(self) -> str:
        return self._state

    def alive(self) -> bool:
        thread = self._thread
        return thread is not None and thread.is_alive()

    def start(self) -> None:
        """Starts the gateway unless it is already running."""
        with self._lock:
            if self.alive():
                return
            self._stopping.clear()
            # The app's process supervises: a restart must surface as exit 75, never as a spawned CLI.
            os.environ["HERMES_GATEWAY_EXTERNAL_SUPERVISOR"] = "1"
            from agent.memory_provider import spawn_context_thread

            self._thread = spawn_context_thread(self._supervise, name="hermes-gateway")
            self._thread.start()

    def stop(self) -> None:
        """Asks the running gateway to drain and stop, as ``hermes gateway stop`` does."""
        self._stopping.set()
        if self.alive():
            from gateway.status import write_planned_stop_marker

            write_planned_stop_marker(os.getpid())

    def _supervise(self) -> None:
        backoff = self._first_backoff_s
        while True:
            began = time.monotonic()
            self._report("running")
            try:
                ended = "stopped" if self._run_gateway() else "the gateway did not start"
            except SystemExit as exit_:
                ended = "restart" if exit_.code == _RESTART_EXIT_CODE else f"the gateway exited with code {exit_.code}"
            except Exception as error:  # the app must hear why its gateway is gone
                logger.warning("the gateway ended with an error", exc_info=True)
                ended = f"{type(error).__name__}: {error}"
            if ended == "stopped" or (ended == "restart" and self._stopping.is_set()):
                self._report("stopped")
                return
            if ended != "restart":
                self._report("failed", reason=ended)
                return
            self._report("restarting")
            if time.monotonic() - began < self._quick_run_s:
                if self._stopping.wait(backoff):
                    self._report("stopped")
                    return
                backoff = min(backoff * 2, self._max_backoff_s)
            else:
                backoff = self._first_backoff_s

    def _report(self, state: str, **detail: str) -> None:
        self._state = state
        self._emit(json.dumps({"gateway": state, **detail}))
