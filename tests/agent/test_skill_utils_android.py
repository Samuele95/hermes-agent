"""On Android (an embedding app, Termux) a skill that lists ``linux`` is offered: Android runs a Linux
kernel and a POSIX userland. Skills limited to other systems stay hidden. The platform is data here,
never a faked ``sys.platform``."""

from __future__ import annotations

from agent.skill_utils import skill_matches_platform_list


def test_a_linux_skill_is_offered_on_android():
    assert skill_matches_platform_list(["linux", "macos", "windows"], family="android")
    assert skill_matches_platform_list(["linux"], family="android")


def test_a_skill_for_other_systems_stays_hidden_on_android():
    assert not skill_matches_platform_list(["macos"], family="android")
    assert not skill_matches_platform_list(["macos", "windows"], family="android")


def test_an_explicit_android_entry_and_no_list_still_match():
    assert skill_matches_platform_list(["android"], family="android")
    assert skill_matches_platform_list(None, family="android")


def test_desktop_families_are_unchanged():
    assert skill_matches_platform_list(["linux"], family="linux")
    assert not skill_matches_platform_list(["macos"], family="linux")
    assert skill_matches_platform_list(["macos"], family="darwin")
    assert not skill_matches_platform_list(["linux"], family="darwin")
    assert skill_matches_platform_list(["windows"], family="win32")
