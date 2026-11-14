"""The app's jobs calls over the core's own cron functions (D39, blueprint §3.2).

The core's JSON-RPC ``cron.manage`` stops at list / add / remove / pause / resume. Editing a job, making it
due now, its past runs, whether the scheduler still owns one of them, the platforms it can deliver to and the
ticker's record are declared bridge calls instead (``cron_update``, ``cron_trigger``, ``cron_runs``,
``cron_run_owned``, ``cron_delivery_targets``, ``cron_scheduler``), each a thin adapter over the core function
the Dashboard's route wraps. Every test goes through ``hermes_android.call``, so the contract's declaration
judges each reply, and runs against the real core store of the hermetic conftest's per-test home. Run now
only makes the job due: the gateway's ticker fires it, never the chat process.
"""

from __future__ import annotations

import importlib
import json
from datetime import UTC, datetime

import pytest

hermes_android = importlib.import_module("hermes_android")  # apps/android/python, put on sys.path by conftest

TOKEN = "123456:AAH-secret-token-for-tests"
KEY = "sk-proj-AbCdEf0123456789AbCdEf0123456789AbCd"
UNCHANGED = {"name": None, "schedule": None, "prompt": None, "deliver": None, "repeat": None}


@pytest.fixture
def home():
    from hermes_constants import get_hermes_home

    path = get_hermes_home()  # the hermetic conftest's per-test home
    path.mkdir(parents=True, exist_ok=True)
    return path


def ask(function: str, request: dict) -> dict:
    return json.loads(hermes_android.call(function, json.dumps(request)))


def _job(schedule: str = "every 30m") -> dict:
    from cron.jobs import create_job

    return create_job(prompt="Summarise the news", schedule=schedule, name="Morning brief", deliver="local")


def test_an_edit_changes_only_the_fields_it_names(home):
    from cron.jobs import get_job

    job = _job()

    assert ask("cron_update", {"job_id": job["id"], **UNCHANGED, "schedule": "every 2h"}) == {"job_id": job["id"]}

    stored = get_job(job["id"])
    assert stored["schedule"]["kind"] == "interval" and stored["schedule"]["minutes"] == 120
    assert stored["schedule_display"] == "every 120m"
    assert (stored["name"], stored["prompt"], stored["deliver"]) == ("Morning brief", "Summarise the news", "local")


def test_the_core_judges_an_edit_and_refuses_in_its_own_words(home):
    from cron.jobs import get_job

    job = _job()
    before = get_job(job["id"])

    refused = ask("cron_update", {"job_id": job["id"], **UNCHANGED, "schedule": "every banana"})
    assert set(refused) == {"error"} and "Invalid duration" in refused["error"]
    assert get_job(job["id"]) == before

    assert ask("cron_update", {"job_id": job["id"], **UNCHANGED}) == {"error": "No updates provided."}

    unknown = ask("cron_update", {"job_id": "000000000000", **UNCHANGED, "name": "x"})
    assert set(unknown) == {"error"} and "not found" in unknown["error"]


@pytest.mark.parametrize("schedule", ["every 30m", "30 9 * * *", "in 45m"])  # interval, cron, one-shot (§8 risk a)
def test_run_now_only_marks_the_job_due_for_the_gateways_tick(home, monkeypatch, schedule):
    import cron.scheduler
    import tools.cronjob_tools
    from cron.jobs import get_due_jobs, get_job

    started = []

    def recorder(where):
        def record(*args, **kwargs):
            started.append(where)
            return {}
        return record

    monkeypatch.setattr(tools.cronjob_tools, "_execute_job_now", recorder("_execute_job_now"))
    monkeypatch.setattr(tools.cronjob_tools, "_run_claimed_job", recorder("_run_claimed_job"))
    monkeypatch.setattr(cron.scheduler, "run_job", recorder("run_job"))
    job = _job(schedule)

    reply = ask("cron_trigger", {"job_id": job["id"]})

    assert started == [], "a run started in the chat process"
    assert datetime.fromisoformat(reply["next_run_at"]) <= datetime.now(UTC)
    assert job["id"] in [due["id"] for due in get_due_jobs()]
    assert get_job(job["id"])["last_run_at"] is None


def test_run_now_refuses_a_finished_or_unknown_job(home):
    from cron.jobs import update_job

    job = _job()
    update_job(job["id"], {"state": "completed", "enabled": False})

    finished = ask("cron_trigger", {"job_id": job["id"]})
    assert set(finished) == {"error"} and "terminal" in finished["error"]

    assert ask("cron_trigger", {"job_id": "000000000000"}) == {"error": "That job no longer exists."}


def test_a_jobs_runs_are_its_own_sessions_newest_first(home):
    import hermes_state_registry

    a, b = "a1b2c3d4e5f6", "f6e5d4c3b2a1"
    db = hermes_state_registry.acquire()
    try:
        db.create_session(f"cron_{a}_20261113_090000", source="cron")
        db.end_session(f"cron_{a}_20261113_090000", "cron_complete")
        db.create_session(f"cron_{a}_20261113_100000", source="cron")
        db.create_session(f"cron_{b}_20261113_093000", source="cron")
        db.create_session(f"cron_{a}_x", source="cli")
        handles = hermes_state_registry.stats()["total_refcounts"]

        runs = ask("cron_runs", {"job_id": a, "limit": 20})["runs"]

        assert hermes_state_registry.stats()["total_refcounts"] == handles, "a state.db handle leaked"
    finally:
        hermes_state_registry.release_or_close(db)

    assert [r["session_id"] for r in runs] == [f"cron_{a}_20261113_100000", f"cron_{a}_20261113_090000"]
    open_run, ended = runs
    assert open_run["ended_at"] is None and open_run["active"] is True
    assert type(ended["ended_at"]) is int and ended["active"] is False
    for run in runs:
        assert type(run["started_at"]) is int and run["messages"] == 0 and run["preview"] is None

    assert len(ask("cron_runs", {"job_id": a, "limit": 1})["runs"]) == 1
    assert len(ask("cron_runs", {"job_id": a, "limit": 0})["runs"]) == 1  # clamped to 1..100, as the route does
    assert "runs" in ask("cron_runs", {"job_id": a, "limit": 500})


def test_an_open_run_idle_for_five_minutes_is_not_active(home, monkeypatch):
    import time

    import hermes_state_registry

    a = "a1b2c3d4e5f6"
    db = hermes_state_registry.acquire()
    try:
        db.create_session(f"cron_{a}_20261113_100000", source="cron")
    finally:
        hermes_state_registry.release_or_close(db)
    later = time.time() + 301
    with monkeypatch.context() as clock:  # the call only: hermes_android.cron reads this same time.time
        clock.setattr(time, "time", lambda: later)
        (run,) = ask("cron_runs", {"job_id": a, "limit": 20})["runs"]

    assert run["ended_at"] is None and run["active"] is False


def _claim(job_id: str) -> dict:
    """A ledger attempt for ``job_id`` claimed and running under THIS (live) process, in the per-test home (the
    core's own ``_claim`` in tests/hermes_cli/test_cron_run_scheduler_ownership.py, with no profile override)."""
    from cron.executions import create_execution, mark_execution_running

    record = create_execution(job_id, source="test")
    return mark_execution_running(record["id"]) or record


def test_a_run_the_scheduler_holds_is_owned_even_idle_past_the_window(home, monkeypatch):
    import time

    import hermes_state_registry

    a = "a1b2c3d4e5f6"
    run = f"cron_{a}_20261113_100000"
    _claim(a)
    db = hermes_state_registry.acquire()
    try:
        db.create_session(run, source="cron")  # it starts after the claim, as the scheduler writes it
        handles = hermes_state_registry.stats()["total_refcounts"]
        later = time.time() + 301
        with monkeypatch.context() as clock:  # a run inside a long tool call: no heartbeat for over 300 s
            clock.setattr(time, "time", lambda: later)
            owned = ask("cron_run_owned", {"session_id": run})
            (idle,) = ask("cron_runs", {"job_id": a, "limit": 20})["runs"]

        assert hermes_state_registry.stats()["total_refcounts"] == handles, "a state.db handle leaked"
    finally:
        hermes_state_registry.release_or_close(db)

    assert owned == {"owned": True}
    assert idle["session_id"] == run and idle["active"] is False  # the window alone would call it quiet
    (now,) = ask("cron_runs", {"job_id": a, "limit": 20})["runs"]
    assert now["session_id"] == run and now["active"] is True


def test_a_run_that_ended_is_not_owned(home):
    import hermes_state_registry

    a = "a1b2c3d4e5f6"
    run = f"cron_{a}_20261113_100000"
    _claim(a)
    db = hermes_state_registry.acquire()
    try:
        db.create_session(run, source="cron")
        db.end_session(run, "cron_complete")
    finally:
        hermes_state_registry.release_or_close(db)

    assert ask("cron_run_owned", {"session_id": run}) == {"owned": False}


def test_a_never_closed_run_with_no_live_owner_is_not_owned(home):
    import hermes_state_registry

    a = "a1b2c3d4e5f6"
    run = f"cron_{a}_20261113_100000"
    db = hermes_state_registry.acquire()
    try:
        db.create_session(run, source="cron")  # a zombie: never closed, and nothing claims its job
    finally:
        hermes_state_registry.release_or_close(db)

    assert ask("cron_run_owned", {"session_id": run}) == {"owned": False}


def test_what_is_not_a_run_cannot_be_told(home):
    import hermes_state_registry

    a = "a1b2c3d4e5f6"
    _claim(a)
    db = hermes_state_registry.acquire()
    try:
        db.create_session(f"cron_{a}_20261113_110000", source="cli")  # a run's name, not a run
        db.create_session("db-x", source="android")
    finally:
        hermes_state_registry.release_or_close(db)

    for session_id in (f"cron_{a}_20261113_110000", "db-x", "cron_000000000000_20261113_100000"):
        assert ask("cron_run_owned", {"session_id": session_id}) == {"owned": None}, session_id


def test_a_core_without_the_ownership_function_cannot_tell(home, monkeypatch):
    import hermes_state_registry

    monkeypatch.delattr(importlib.import_module("hermes_cli.web_routers.cron"), "cron_run_scheduler_owned")
    a = "a1b2c3d4e5f6"
    run = f"cron_{a}_20261113_100000"
    _claim(a)
    db = hermes_state_registry.acquire()
    try:
        db.create_session(run, source="cron")
    finally:
        hermes_state_registry.release_or_close(db)

    assert ask("cron_run_owned", {"session_id": run}) == {"owned": None}


def test_delivery_targets_are_the_phones_platforms_with_their_home_flag(home, monkeypatch):
    from cron.scheduler_delivery import BOT_CHAT_PLATFORM, cron_delivery_targets

    monkeypatch.setenv("TELEGRAM_BOT_TOKEN", TOKEN)
    monkeypatch.delenv("TELEGRAM_HOME_CHANNEL", raising=False)
    # Not vacuous: the core itself lists a Bot Chat target here, which the app drops.
    assert any(t["id"].startswith(BOT_CHAT_PLATFORM) for t in cron_delivery_targets())
    replies = [ask("cron_delivery_targets", {})]
    assert replies[-1] == {"targets": [{"id": "telegram", "name": "Telegram", "home_set": False}]}

    monkeypatch.setenv("TELEGRAM_HOME_CHANNEL", "123456789")
    replies.append(ask("cron_delivery_targets", {}))
    assert replies[-1] == {"targets": [{"id": "telegram", "name": "Telegram", "home_set": True}]}

    monkeypatch.delenv("TELEGRAM_BOT_TOKEN")
    replies.append(ask("cron_delivery_targets", {}))
    assert replies[-1] == {"targets": []}

    ids = [t["id"] for reply in replies for t in reply["targets"]]
    assert not [i for i in ids if i.startswith("bot-chat") or i == "local"]


def test_with_no_stamp_the_ticker_cannot_be_told(home):
    assert ask("cron_scheduler", {}) == {"heartbeat_age_s": None, "success_age_s": None, "last_error": None,
                                         "stale_after_s": 200}


def test_the_tickers_record_is_read_in_whole_seconds(home):
    from cron.jobs import record_ticker_heartbeat
    from hermes_cli.cron import _ticker_age_is_fresh

    record_ticker_heartbeat(success=True)

    reply = ask("cron_scheduler", {})

    for age in (reply["heartbeat_age_s"], reply["success_age_s"]):
        assert type(age) is int and 0 <= age <= 5
    assert _ticker_age_is_fresh(reply["stale_after_s"]) is True  # the core's own threshold, not a copy
    assert _ticker_age_is_fresh(reply["stale_after_s"] + 1) is False


def test_the_tickers_last_error_leaves_redacted(home):
    from cron.jobs import record_ticker_error

    record_ticker_error(f"provider refused key {KEY}")

    last_error = ask("cron_scheduler", {})["last_error"]

    assert KEY not in last_error
    assert "provider refused key" in last_error


@pytest.mark.parametrize(
    ("wire", "kind", "display"),
    [
        ("every 30m", "interval", "every 30m"),
        ("30 9 * * *", "cron", "30 9 * * *"),
        ("30 9 * * 1-5", "cron", "30 9 * * 1-5"),
        ("in 45m", "once", "once in 45m"),
    ],
)
def test_the_forms_schedule_presets_parse_in_the_core(home, wire, kind, display):
    """The forms the app's presets render (JobFormTest pins the same text) are the core's grammar."""
    from cron.jobs import parse_schedule

    parsed = parse_schedule(wire)

    assert (parsed["kind"], parsed["display"]) == (kind, display)


@pytest.mark.parametrize("display", ["once in 45m", "once at 2026-11-13 18:05"])
def test_a_schedules_display_is_not_its_grammar(home, display):
    """Why an edit never sends the schedule back unless it was changed (DC-J8)."""
    from cron.jobs import parse_schedule

    with pytest.raises(ValueError, match="Invalid schedule"):
        parse_schedule(display)
