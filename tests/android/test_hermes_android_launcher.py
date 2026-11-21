"""The app's Python launcher: ``$HOME/bin/python3`` and the runtime pointer beside it.

Android lets the app ``execve`` only what the installer extracted into its native-library directory, which changes with
every update, as does the runtime directory. A stdio MCP server configured as ``${userHome}/bin/python3`` stays valid
because each interpreter start rewrites the symlinks and the pointer; with no launcher extracted nothing may be left
pointing at an old path.
"""

from __future__ import annotations

import importlib
import os

import pytest

launcher = importlib.import_module("hermes_android.launcher")  # apps/android/python, put on sys.path by conftest


@pytest.fixture
def layout(tmp_path):
    home, native_libs, runtime = tmp_path / "files", tmp_path / "app-lib" / "arm64", tmp_path / "runtime" / "b1"
    native_libs.mkdir(parents=True)
    (native_libs / "libhermes_pyexec.so").write_bytes(b"\x7fELF")
    runtime.mkdir(parents=True)
    return home, native_libs, runtime


def _bin(home):
    return home / "bin"


def test_python3_and_python_point_at_the_launcher_and_the_pointer_names_the_runtime(layout):
    home, native_libs, runtime = layout

    launcher.ensure(str(home), str(native_libs), str(runtime))

    for name in ("python3", "python"):
        link = _bin(home) / name
        assert link.is_symlink() and os.readlink(link) == str(native_libs / "libhermes_pyexec.so")
    assert (_bin(home) / ".hermes-runtime").read_text(encoding="utf-8").strip() == str(runtime)
    assert launcher.available(str(home)) is True


def test_an_update_rewrites_links_and_pointer_that_name_the_old_paths(layout, tmp_path):
    home, native_libs, runtime = layout
    old_libs, old_runtime = tmp_path / "old-lib", tmp_path / "runtime" / "b0"
    old_libs.mkdir()
    (old_libs / "libhermes_pyexec.so").write_bytes(b"\x7fELF")
    old_runtime.mkdir()
    launcher.ensure(str(home), str(old_libs), str(old_runtime))

    launcher.ensure(str(home), str(native_libs), str(runtime))

    for name in ("python3", "python"):
        assert os.readlink(_bin(home) / name) == str(native_libs / "libhermes_pyexec.so")
    assert (_bin(home) / ".hermes-runtime").read_text(encoding="utf-8").strip() == str(runtime)


def test_with_no_launcher_extracted_no_link_and_no_pointer_is_left(layout, tmp_path):
    # The packaging keeps the native libraries inside the APK: nothing executable exists, so nothing may point at one
    # (an old install's links would name a directory the update removed).
    home, native_libs, runtime = layout
    launcher.ensure(str(home), str(native_libs), str(runtime))
    stored = tmp_path / "stored-lib"
    stored.mkdir()

    launcher.ensure(str(home), str(stored), str(runtime))

    assert sorted(p.name for p in _bin(home).iterdir()) == []
    assert launcher.available(str(home)) is False


def test_with_no_launcher_and_no_bin_directory_nothing_is_created(tmp_path):
    home = tmp_path / "files"

    launcher.ensure(str(home), str(tmp_path / "lib"), str(tmp_path / "runtime"))

    assert not _bin(home).exists()
    assert launcher.available(str(home)) is False


def test_ensure_is_idempotent_and_leaves_no_temporary_file(layout):
    home, native_libs, runtime = layout

    launcher.ensure(str(home), str(native_libs), str(runtime))
    launcher.ensure(str(home), str(native_libs), str(runtime))

    assert sorted(p.name for p in _bin(home).iterdir()) == [".hermes-runtime", "python", "python3"]
    assert os.readlink(_bin(home) / "python3") == str(native_libs / "libhermes_pyexec.so")


def test_a_link_without_its_pointer_is_not_available(layout):
    home, native_libs, runtime = layout
    launcher.ensure(str(home), str(native_libs), str(runtime))

    (_bin(home) / ".hermes-runtime").unlink()

    assert launcher.available(str(home)) is False
