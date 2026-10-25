"""What an embedding host runs beside :class:`~tui_gateway.embedded.EmbeddedTransport`.

A host that embeds the interpreter (a mobile app over JNI, a Swift shell) never runs ``hermes_cli.main``,
so two things the CLI does at its startup are the host's to call: seeding the bundled skills into the home,
and re-reading the secret sources after the host changed a secret they serve.
"""

from __future__ import annotations

import os
import stat
import threading
from pathlib import Path

import pytest

from hermes_cli import banner, env_loader
from hermes_constants import get_hermes_home
from tui_gateway import embedded


@pytest.fixture
def home():
    path = get_hermes_home()  # the hermetic conftest's per-test home
    path.mkdir(parents=True, exist_ok=True)
    return path


@pytest.fixture
def fresh_banner(monkeypatch):
    monkeypatch.setattr(banner, "_available_skills_cache", None)


def test_an_unseeded_home_gets_the_bundled_skills_before_the_call_returns(home, fresh_banner):
    assert banner.get_available_skills() == {}  # computed, and cached, before the seed

    embedded.seed_bundled_skills()

    assert next((home / "skills").rglob("SKILL.md"), None) is not None
    listed = banner.get_available_skills()  # the stale empty index was dropped
    assert "github" in [name for names in listed.values() for name in names]


def test_a_seeded_home_is_brought_up_to_date_in_the_background(home, monkeypatch):
    (home / "skills" / "mine").mkdir(parents=True)
    (home / "skills" / "mine" / "SKILL.md").write_text("---\nname: mine\n---\n", encoding="utf-8")
    ran = threading.Event()
    seen: list[str] = []

    def sync_skills(quiet=False):
        seen.append(threading.current_thread().name)
        ran.set()

    monkeypatch.setattr("tools.skills_sync.sync_skills", sync_skills)

    embedded.seed_bundled_skills()

    assert ran.wait(10)
    assert seen == ["bundled-skills-sync"]


@pytest.mark.platforms("posix")  # the command secret source is POSIX-only
def test_reloading_the_secrets_applies_and_revokes_what_the_source_now_serves(home, monkeypatch):
    served = home / "served.env"
    helper = home / "helper.sh"
    helper.write_text(f"#!/bin/sh\ncat '{served}' 2>/dev/null\n", encoding="utf-8")
    helper.chmod(helper.stat().st_mode | stat.S_IXUSR)
    (home / "config.yaml").write_text(f"secrets:\n  command:\n    enabled: true\n    command: {helper}\n",
                                      encoding="utf-8")
    for name in ("EMBEDTEST_API_KEY", "EMBEDTEST_OTHER"):
        monkeypatch.delenv(name, raising=False)
    env_loader.load_hermes_dotenv(hermes_home=home)
    assert "EMBEDTEST_API_KEY" not in os.environ

    served.write_text("EMBEDTEST_API_KEY=sk-new\n", encoding="utf-8")
    embedded.reload_secrets(home)
    assert os.environ.get("EMBEDTEST_API_KEY") == "sk-new"

    served.write_text("EMBEDTEST_OTHER=1\n", encoding="utf-8")  # the source answers, without the key
    embedded.reload_secrets(home)
    assert "EMBEDTEST_API_KEY" not in os.environ


def test_reloading_reloads_the_home_it_is_given_else_the_current_one(home, tmp_path, monkeypatch):
    reloaded: list[Path] = []
    monkeypatch.setattr(env_loader, "load_hermes_dotenv", lambda *, hermes_home=None, **_: reloaded.append(Path(hermes_home)))

    embedded.reload_secrets(tmp_path / "profile")
    embedded.reload_secrets()

    assert reloaded == [tmp_path / "profile", home]
