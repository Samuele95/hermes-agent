"""One lock around the app's config.yaml read-modify-writes.

``save_config`` locks its own write, not the span from a load to the save, so overlapping writers drop each
other's edits (as ``hermes_cli/web_server.py``'s ``_CONFIG_MUTATION_LOCK`` says). The app takes the flock the
core's plugin-setting writer takes on the sibling ``.config.yaml.lock`` (``plugins_state._locked_plugin_state``;
a sibling, since the atomic save changes config.yaml's inode). Not covered: ``config.set``, ``set_config_value``
and the gateway's ``persist_home_channel`` take only the core's private ``_CONFIG_LOCK``, which the app must not
take (D27); a public mutation scope in the core is an upstream candidate (Dv42).
"""

from __future__ import annotations

import copy
import threading
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any, TypeVar

T = TypeVar("T")

# Not reentrant: a nested mutate would block on its own flock (it belongs to the open file description), so
# it fails loudly in a test rather than half-working.
_THREADS = threading.Lock()


@contextmanager
def _locked(config_path: Path) -> Iterator[None]:
    """The span lock: this process's threads, then the flock other processes and the core's plugin-setting
    writer take. No timeout, as the core's: the kernel drops a flock when its process dies."""
    lock_path = config_path.with_name(f".{config_path.name}.lock")
    with _THREADS:
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        with open(lock_path, "a+b") as handle:
            try:
                import fcntl
            except ImportError:  # no flock on this host (Windows): threads only, as the core's _file_lock degrades
                yield
                return
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def mutate(fn: Callable[[dict[str, Any]], T]) -> T:
    """Runs ``fn`` on the loaded config and saves what it changed, with no other app write (or core
    plugin-setting write) between the load and the save, in this process or the other. ``fn`` edits the
    mapping in place and returns the reply; it does no I/O and never writes config itself. An unreadable
    config.yaml raises before ``fn`` runs (load_config would hand ``fn`` the last good copy)."""
    from hermes_cli.config import get_config_path, load_config, read_user_config_raw, save_config

    with _locked(get_config_path()):
        read_user_config_raw()  # fail closed, as save_plugin_setting does
        cfg = load_config()
        before = copy.deepcopy(cfg)
        result = fn(cfg)
        if cfg != before:  # a refusal inside fn writes nothing, and keeps the file's inode
            save_config(cfg)
        return result
