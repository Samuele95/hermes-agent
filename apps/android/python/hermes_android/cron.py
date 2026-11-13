"""The app's jobs calls over the core's own cron functions (D39, blueprint §3.2).

The core's JSON-RPC ``cron.manage`` stops at list / add / remove / pause / resume. Editing a job, making it
due now, its past runs, the platforms it can deliver to and the ticker's record are declared bridge calls
instead, each a thin adapter over the one core function the Dashboard's route wraps, so the core keeps
judging schedules and prompts. Run now only makes the job due: the gateway's ticker fires it, never this
(the chat) process. The cron store is written under the core's own ``_jobs_lock``, so no app lock is taken.
The core is importable only after ``prepare()``, so its imports sit inside the functions. Each function
takes and returns JSON; an ``{"error"}`` answer is for the user.
"""

from __future__ import annotations

import json
import time

ACTIVE_WITHIN_S = 300  # the route's "active" window (hermes_cli/web_routers/cron.py)


def update(arg: str) -> str:
    """Edits ``{"job_id", "name", "schedule", "prompt", "deliver", "repeat"}`` through the core's cronjob update;
    a null field is left as it is. The core's refusal is passed on in its own words."""
    from tools.cronjob_tools import cronjob

    r = json.loads(arg)
    answer = json.loads(cronjob(action="update", job_id=r["job_id"], name=r["name"], schedule=r["schedule"],
                                prompt=r["prompt"], deliver=r["deliver"], repeat=r["repeat"]))
    if answer.get("success"):
        return json.dumps({"job_id": answer["job"]["job_id"]})
    return json.dumps({"error": answer.get("error") or "The job could not be saved."})


def trigger(arg: str) -> str:
    """Makes ``{"job_id"}`` due now for the gateway's ticker (``cron.jobs.trigger_job``); nothing runs here."""
    from cron.jobs import AmbiguousJobReference, trigger_job

    try:
        job = trigger_job(json.loads(arg)["job_id"])
    except (AmbiguousJobReference, ValueError) as refused:  # a finished job: the core's words, as the CLI shows them
        return json.dumps({"error": str(refused)})
    if job is None:
        return json.dumps({"error": "That job no longer exists."})
    return json.dumps({"next_run_at": job.get("next_run_at")})


def runs(arg: str) -> str:
    """``{"job_id", "limit"}``'s own cron sessions, newest first, through the shared state.db handle."""
    import hermes_state_registry

    r = json.loads(arg)
    limit = max(1, min(r["limit"], 100))
    db = hermes_state_registry.acquire()
    try:
        rows = db.list_cron_job_runs(r["job_id"], limit=limit, offset=0)
    finally:
        hermes_state_registry.release_or_close(db)
    return json.dumps({"runs": [_run(row) for row in rows]})


def _run(row: dict) -> dict:
    ended = row.get("ended_at")
    return {"session_id": row["id"], "started_at": int(row["started_at"]),
            "ended_at": None if ended is None else int(ended), "messages": int(row.get("message_count") or 0),
            "preview": row.get("preview") or None,
            "active": ended is None and time.time() - (row.get("last_active") or row["started_at"]) < ACTIVE_WITHIN_S}


def delivery_targets(_arg: str) -> str:
    """The core's delivery platforms with their home-channel flag; Bot Chat is dropped, Local is the app's own."""
    from cron.scheduler_delivery import BOT_CHAT_PLATFORM, cron_delivery_targets

    return json.dumps({"targets": [
        {"id": t["id"], "name": t["name"], "home_set": bool(t["home_target_set"])}
        for t in cron_delivery_targets()
        if t["id"] != BOT_CHAT_PLATFORM and not t["id"].startswith(BOT_CHAT_PLATFORM + ":")]})


def scheduler(_arg: str) -> str:
    """The ticker's record in whole seconds; a missing stamp is null ("cannot tell"), never 0."""
    from agent.redact import redact_sensitive_text
    from cron.jobs import TICKER_INTERVAL_SECONDS, get_ticker_heartbeat_age, get_ticker_last_error, get_ticker_success_age

    heartbeat, success, error = get_ticker_heartbeat_age(), get_ticker_success_age(), get_ticker_last_error()
    return json.dumps({
        "heartbeat_age_s": None if heartbeat is None else int(heartbeat),
        "success_age_s": None if success is None else int(success),
        "last_error": None if error is None else redact_sensitive_text(error, force=True, redact_url_credentials=True),
        "stale_after_s": TICKER_INTERVAL_SECONDS * 3 + 20})
