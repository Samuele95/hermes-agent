package com.nousresearch.hermes.android.chat

/**
 * The core's name for a job's agent run (`cron/scheduler.py::run_job`: `cron_{job_id}_{YYYYmmdd_HHMMSS}`), as the
 * core's `_CRON_RUN_SESSION_ID` (hermes_cli/web_routers/cron.py:447) and Desktop's `CRON_RUN_SESSION_ID` read it.
 */
private val CRON_RUN = Regex("""^cron_.+_\d{8}_\d{6}$""")

/** [id] names a job's run: only such a session is asked whether the gateway's scheduler still owns it (V3). */
internal fun isCronRun(id: StoredSessionId): Boolean = CRON_RUN.matches(id.value.trim())

/**
 * A send into a job's run is refused while the gateway's scheduler still owns it ([owned] true): its agent writes
 * the session from the `:gateway` process (/architect 20261113T220113Z #4). False (ended, or no live owner) and
 * null (not a run, an older core, a failed read) send as before. Desktop refuses the other case, a zombie (FOUND).
 */
internal fun refusesSend(owned: Boolean?): Boolean = owned == true
