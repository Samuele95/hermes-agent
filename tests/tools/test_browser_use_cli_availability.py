"""The default Browser Use mode needs a launchable interpreter, not just an importable harness.

``browser_exec`` runs ``[sys.executable, "-m", "browser_harness.run"]``. On a host that embeds CPython
in-process (a mobile app) the harness imports but ``sys.executable`` cannot be launched, so the
default mode must step aside for the built-in browser tools (which hide themselves without a browser)
and must not tell the user the harness is missing.
"""

import sys

import pytest

import tools.browser_use_cli as bu
from tools.browser_use_cli import default_downgrade_notice, is_browser_use_cli_mode


@pytest.fixture(autouse=True)
def _real_cli_discovery(monkeypatch):
    # tests/tools/conftest.py pins discovery to "not installed"; these tests are about discovery.
    monkeypatch.setattr(bu, "_find_cli", bu._find_cli_unpatched)


@pytest.mark.parametrize("executable", [None, ""])
def test_default_mode_steps_aside_without_a_launchable_interpreter(monkeypatch, executable):
    monkeypatch.setattr(sys, "executable", executable)

    assert is_browser_use_cli_mode() is False


def test_default_mode_is_on_with_a_launchable_interpreter():
    assert is_browser_use_cli_mode() is True


def test_no_harness_missing_notice_when_only_the_interpreter_is_unlaunchable(monkeypatch):
    monkeypatch.setattr(sys, "executable", "")

    assert default_downgrade_notice() is None


def test_a_legacy_cloud_key_does_not_enable_browser_exec_without_a_launchable_interpreter(monkeypatch):
    monkeypatch.setattr(bu, "_browser_use_api_key_set", lambda: True)
    monkeypatch.setattr(sys, "executable", "")

    assert is_browser_use_cli_mode() is False
