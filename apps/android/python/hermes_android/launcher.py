"""``$HOME/bin/python3``: a Python executable the phone lets the app run.

An app targeting API 29+ may ``execve`` only what the installer extracted into its native-library directory, so the
launcher ships among the native libraries as ``libhermes_pyexec.so``. That directory and the runtime directory change
with every app update; a stdio MCP server configured as ``${userHome}/bin/python3`` stays valid because both of the
app's interpreters call :func:`ensure` at each start, rewriting the ``python3`` / ``python`` symlinks and the
``.hermes-runtime`` pointer the launcher reads its runtime from. Each is written under a temporary name holding the
pid, then renamed: the chat process and ``:gateway`` may do it at once. Without an extracted launcher nothing is left
pointing at an old path.
"""

from __future__ import annotations

import os
from collections.abc import Callable
from pathlib import Path

LAUNCHER = "libhermes_pyexec.so"
POINTER = ".hermes-runtime"
NAMES = ("python3", "python")


def ensure(home: str, native_libs: str, runtime: str) -> bool:
    """Point ``home/bin/python3`` and ``python`` at the extracted launcher and the pointer at ``runtime``; with no
    launcher in ``native_libs``, remove all three. Returns whether the launcher is offered."""
    bin_dir = Path(home) / "bin"
    launcher = Path(native_libs) / LAUNCHER
    if not launcher.is_file():
        for name in (*NAMES, POINTER):
            (bin_dir / name).unlink(missing_ok=True)
        return False
    bin_dir.mkdir(parents=True, exist_ok=True)
    for name in NAMES:
        _replace(bin_dir / name, lambda temp: os.symlink(launcher, temp))
    _replace(bin_dir / POINTER, lambda temp: temp.write_text(runtime, encoding="utf-8"))
    return True


def available(home: str) -> bool:
    """Whether ``home/bin/python3`` leads to an existing launcher and its runtime pointer is there."""
    bin_dir = Path(home) / "bin"
    link = bin_dir / "python3"
    return link.is_symlink() and link.is_file() and (bin_dir / POINTER).is_file()


def _replace(target: Path, write: Callable[[Path], object]) -> None:
    temp = target.with_name(f".{target.name}.{os.getpid()}.tmp")
    temp.unlink(missing_ok=True)
    try:
        write(temp)
        os.replace(temp, target)
    finally:
        temp.unlink(missing_ok=True)
