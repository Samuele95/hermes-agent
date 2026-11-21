"""execute_code is offered only where its interpreter can run.

The local backend launches a child interpreter (``_resolve_child_python``). An embedded host — a
mobile app running CPython in-process — has no launchable ``sys.executable``, so the tool must be
hidden there instead of failing on every call. Remote backends run the cell on the target and need
no local child interpreter.
"""

import sys

import pytest

from tools.code_execution_tool import check_sandbox_requirements


@pytest.mark.parametrize("executable", [None, "", "relative/python3", "/nonexistent/hermes-probe/python3"])
def test_local_backend_without_a_launchable_interpreter_hides_execute_code(monkeypatch, executable):
    monkeypatch.setenv("TERMINAL_ENV", "local")
    monkeypatch.setattr(sys, "executable", executable)

    assert check_sandbox_requirements() is False


def test_local_backend_with_a_launchable_interpreter_offers_execute_code(monkeypatch):
    monkeypatch.setenv("TERMINAL_ENV", "local")

    assert check_sandbox_requirements() is True


def test_remote_backend_offers_execute_code_without_a_local_interpreter(monkeypatch):
    monkeypatch.setenv("TERMINAL_ENV", "ssh")
    monkeypatch.setattr(sys, "executable", "")

    assert check_sandbox_requirements() is True


def test_the_availability_check_never_launches_an_interpreter(monkeypatch, tmp_path):
    # check_fn is re-evaluated on a TTL: it must stay a filesystem query, never a probe run.
    venv_python = tmp_path / "venv" / "bin" / "python"
    venv_python.parent.mkdir(parents=True)
    venv_python.write_text("#!/bin/sh\nexit 1\n")
    venv_python.chmod(0o755)
    monkeypatch.setenv("TERMINAL_ENV", "local")
    monkeypatch.setenv("VIRTUAL_ENV", str(tmp_path / "venv"))

    def no_probe(*args, **kwargs):
        raise AssertionError("check_sandbox_requirements launched an interpreter probe")

    monkeypatch.setattr("tools.code_execution_env._probe_python", no_probe)

    assert check_sandbox_requirements() is True
