package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.contract.CronSchedulerReply
import com.nousresearch.hermes.bridge.protocol.CronJobRow
import com.nousresearch.hermes.bridge.protocol.CronManageResult
import kotlinx.serialization.json.JsonPrimitive

/** The core's `job_id`: the only reference to a job the app ever sends (a name may repeat or change, EJ-62). */
@JvmInline
value class JobId(val value: String)

/** A job's state as the core judges it (`effective_job_state`); a word the app does not know is [UNKNOWN]. */
enum class JobState {
    SCHEDULED, PAUSED, COMPLETED, ERROR, UNKNOWN;

    internal companion object {
        fun of(word: String?): JobState = when (word) {
            "scheduled" -> SCHEDULED
            "paused" -> PAUSED
            "completed" -> COMPLETED
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}

/** How a job's last run ended, from the core's `last_status`; any word of its own is [OTHER]. */
enum class RunOutcome { OK, FAILED, DELIVERY_FAILED, OTHER }

/**
 * A job's last run: when ([at]), how it ended ([outcome], with the core's own [word] for it, e.g. `blocked_config`),
 * and the first error the core recorded for it (its run, its delivery, or its fire, already redacted by the core).
 */
data class LastRun(val at: String?, val outcome: RunOutcome?, val word: String?, val error: String?)

/**
 * One scheduled job as the core lists it: [schedule] and [repeat] are the core's own display, [prompt] its preview,
 * [nextRunAt] and [LastRun.at] its own wall time.
 */
data class Job(val id: JobId, val name: String, val schedule: String, val prompt: String, val state: JobState,
               val nextRunAt: String?, val last: LastRun?, val repeat: String?) {
    /**
     * The job could still fire: not paused (a disabled job reads paused) and not completed. A recurring job in error
     * still counts, as the gateway's due-scan heals it (the Dashboard's rule).
     */
    val firing: Boolean get() = state != JobState.PAUSED && state != JobState.COMPLETED

    companion object {
        /** The job a core row describes, or null for a row without an id (skipped, never a crash). */
        fun of(row: CronJobRow): Job? {
            if (row.jobId.isBlank()) return null
            val error = listOf(row.lastError, row.lastDeliveryError, row.lastFireError).firstOrNull { !it.isNullOrBlank() }
            val outcome = row.lastStatus?.let {
                when (it) {
                    "ok" -> RunOutcome.OK
                    "error" -> RunOutcome.FAILED
                    "delivery_failed" -> RunOutcome.DELIVERY_FAILED
                    else -> RunOutcome.OTHER
                }
            }
            val last = if (row.lastRunAt == null && row.lastStatus == null && error == null) null
                       else LastRun(row.lastRunAt, outcome, row.lastStatus, error)
            return Job(id = JobId(row.jobId), name = row.name.ifBlank { row.jobId }, schedule = row.schedule,
                       prompt = row.promptPreview, state = JobState.of(row.state), nextRunAt = row.nextRunAt, last = last,
                       repeat = (row.repeat as? JsonPrimitive)?.takeIf { it.isString }?.content)
        }
    }
}

/**
 * The core ticker's own record: seconds since its last heartbeat and its last good tick (null: no stamp, so the age
 * cannot be told), its last error (redacted), and the core's threshold past which a heartbeat is stale.
 */
data class TickerFacts(val heartbeatAgeS: Int?, val successAgeS: Int?, val lastError: String?, val staleAfterS: Int) {
    companion object {
        fun of(reply: CronSchedulerReply): TickerFacts =
            TickerFacts(reply.heartbeatAgeS, reply.successAgeS, reply.lastError, reply.staleAfterS)
    }
}

/** Why the scheduled jobs will not run. */
sealed interface JobsWarning {
    /** The always-on gateway, where jobs run, is not running: off, starting, stopping, failed or died. */
    data class GatewayOff(val status: GatewayStatus) : JobsWarning

    /** The gateway runs, but its ticker last stamped [minutes] ago; [error] is the last one it recorded. */
    data class NotTicking(val minutes: Int, val error: String?) : JobsWarning
}

/**
 * Whether the [jobs] will not run, and why (D39 bet 8, F2): asked only while one of them could still fire; the app's
 * own gateway record ([seen]) first, then the core ticker's heartbeat ([ticker]), stale only past the core's own
 * threshold. An unknown heartbeat (no stamp, or an unreadable ticker) is no warning: it cannot be told, which is not
 * dead (EJ-55). The core's own `gateway_running` and `warning` in its [CronManageResult] are deliberately not inputs:
 * they are a guess made in the chat process, and their remedy names commands a phone cannot run.
 */
fun jobsWarning(seen: GatewaySeen, ticker: TickerFacts?, jobs: List<Job>): JobsWarning? {
    if (jobs.none { it.firing }) return null
    if (seen.status != GatewayStatus.RUNNING) return JobsWarning.GatewayOff(seen.status)
    val age = ticker?.heartbeatAgeS ?: return null
    return if (age > ticker.staleAfterS) JobsWarning.NotTicking(maxOf(1, age / 60), ticker.lastError) else null
}

private val WALL_MINUTE = Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})""")

/** The core's ISO time as its own wall time to the minute (`2026-11-13 18:05`); any other text as the core wrote it. */
fun wallTime(iso: String): String = WALL_MINUTE.find(iso)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" } ?: iso

/** A span of [minutes] in the largest whole unit: `12 min`, `3 h`, `2 d`. */
fun elapsed(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 1440 -> "${minutes / 60} h"
    else -> "${minutes / 1440} d"
}
