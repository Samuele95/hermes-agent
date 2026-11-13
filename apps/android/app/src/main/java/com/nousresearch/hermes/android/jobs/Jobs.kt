package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.contract.CronDeliveryTargetsReply
import com.nousresearch.hermes.bridge.contract.CronSchedulerReply
import com.nousresearch.hermes.bridge.contract.CronUpdateParams
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronJobRow
import com.nousresearch.hermes.bridge.protocol.CronManageParams
import com.nousresearch.hermes.bridge.protocol.CronManageResult
import kotlinx.serialization.json.JsonPrimitive
import com.nousresearch.hermes.bridge.contract.DeliveryTarget as TargetRow

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
 * [nextRunAt] and [LastRun.at] its own wall time, [delivery] where its result goes (last, so J5's positional calls
 * stay as they are: V1).
 */
data class Job(val id: JobId, val name: String, val schedule: String, val prompt: String, val state: JobState,
               val nextRunAt: String?, val last: LastRun?, val repeat: String?, val delivery: Delivery = Delivery.Local) {
    /** The total runs [repeat] allows, read from the core's display (V4): `once`, `1/1` → 1; `3 times`, `2/5`; null = no limit or unknown. */
    val times: Int? get() = repeat?.trim()?.let { shown ->
        when {
            shown == "once" -> 1
            else -> (TIMES.matchEntire(shown) ?: OF_TIMES.matchEntire(shown))?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    /** [prompt] is the core's preview of a longer prompt (`prompt[:100] + "..."`): it must never be saved back (V3). */
    val promptCut: Boolean get() = prompt.length == PREVIEW_CUT && prompt.endsWith("...")

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
                       repeat = (row.repeat as? JsonPrimitive)?.takeIf { it.isString }?.content,
                       delivery = Delivery.parse(row.deliver))
        }
    }
}

private val TIMES = Regex("""^(\d+) times$""")
private val OF_TIMES = Regex("""^\d+/(\d+)$""")
private const val PREVIEW_CUT = 103  // tools/cronjob_job_args.py: prompt[:100] + "..."

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

/** When a job runs, as one of the form's presets; [wire] is the core's own grammar, and the core judges it (F6, DC-J7). */
sealed interface Schedule {
    data class Every(val minutes: Int) : Schedule
    data class Daily(val hour: Int, val minute: Int) : Schedule
    data class Weekdays(val hour: Int, val minute: Int) : Schedule
    data class Once(val inMinutes: Int) : Schedule
    data class Custom(val text: String) : Schedule
}

/** The schedule in the core's grammar (`parse_schedule`); a clock is a cron line, minute first (`30 9 * * *` is 09:30). */
fun Schedule.wire(): String = when (this) {
    is Schedule.Every -> "every ${minutes}m"
    is Schedule.Daily -> "$minute $hour * * *"
    is Schedule.Weekdays -> "$minute $hour * * 1-5"
    is Schedule.Once -> "in ${inMinutes}m"
    is Schedule.Custom -> text.trim()
}

enum class SchedulePreset { EVERY, DAILY, WEEKDAYS, ONCE, CUSTOM }

/**
 * The schedule fields as typed (V2); [schedule] is what they make, null while they make none. Only the input is
 * shaped here (whole minutes from 1, a clock's hour and minute); the grammar, and all of [SchedulePreset.CUSTOM], is
 * the core's to judge.
 */
data class ScheduleDraft(val preset: SchedulePreset = SchedulePreset.DAILY, val minutes: String = "30",
                         val hour: String = "9", val minute: String = "0", val text: String = "") {
    val schedule: Schedule? get() {
        val span = minutes.trim().toIntOrNull()?.takeIf { it >= 1 }
        val h = hour.trim().toIntOrNull()?.takeIf { it in 0..23 }
        val m = minute.trim().toIntOrNull()?.takeIf { it in 0..59 }
        return when (preset) {
            SchedulePreset.EVERY -> span?.let { Schedule.Every(it) }
            SchedulePreset.ONCE -> span?.let { Schedule.Once(it) }
            SchedulePreset.DAILY -> if (h != null && m != null) Schedule.Daily(h, m) else null
            SchedulePreset.WEEKDAYS -> if (h != null && m != null) Schedule.Weekdays(h, m) else null
            SchedulePreset.CUSTOM -> text.trim().takeIf { it.isNotEmpty() }?.let { Schedule.Custom(it) }
        }
    }
}

/** Where a job's result goes (F10): saved only, a platform's home chat, or the core's own words, kept as they are. */
sealed interface Delivery {
    /** Saved on the phone only (`local`). */
    data object Local : Delivery

    /** The home chat of platform [id] (`telegram`). */
    data class Platform(val id: String) : Delivery

    /** The core's own words for anything else (`origin`, an explicit chat, a list): shown as they are and kept unchanged on edit. */
    data class Other(val raw: String) : Delivery

    companion object {
        private val PLATFORM = Regex("^[a-z][a-z0-9_]*$")

        /** A row's `deliver` (null or blank is the core's default, `local`); `parse(d.wire()) == d` for every value it returns. */
        fun parse(raw: String?): Delivery {
            val word = raw?.trim()
            return when {
                word.isNullOrEmpty() || word == "local" -> Local
                word != "origin" && PLATFORM.matches(word) -> Platform(word)
                else -> Other(raw)
            }
        }
    }
}

/** The delivery as the core's `deliver` word. */
fun Delivery.wire(): String = when (this) {
    Delivery.Local -> "local"
    is Delivery.Platform -> id
    is Delivery.Other -> raw
}

/** One place a job may deliver to; [homeSet] false: the platform has no home chat yet ("send /sethome to the bot first"). */
data class DeliveryTarget(val delivery: Delivery, val name: String, val homeSet: Boolean)

/** Local first, as the Dashboard's route prepends it, then the core's own targets with their home flag; null (unread) is Local alone (V7). */
fun deliveryTargets(reply: CronDeliveryTargetsReply?): List<DeliveryTarget> =
    listOf(DeliveryTarget(Delivery.Local, "local", homeSet = true)) +
        reply?.targets.orEmpty().map { row: TargetRow -> DeliveryTarget(Delivery.parse(row.id), row.name, row.homeSet) }
            .filter { it.delivery != Delivery.Local }

/**
 * The job being made ([id] null) or edited: the form's parameter object (§3.3). [schedule] null on an edit keeps the
 * job's own; [repeat] null is no limit. The draft is UI state, never saved.
 */
data class JobForm(val id: JobId?, val name: String, val prompt: String, val schedule: Schedule?,
                   val delivery: Delivery, val repeat: Int?) {
    companion object {
        /** A new job: daily at 09:00, delivered locally, no limit. */
        fun blank(): JobForm = JobForm(null, "", "", ScheduleDraft().schedule, Delivery.Local, null)

        /** [job]'s own values; its schedule is kept (null), and a prompt the core shortened starts empty (V3). */
        fun of(job: Job): JobForm =
            JobForm(job.id, job.name, if (job.promptCut) "" else job.prompt, schedule = null, job.delivery, job.times)
    }
}

/** `cron.manage add` in the core's words, or null while the form has no prompt or no schedule. */
fun JobForm.addParams(): CronManageParams? {
    val schedule = schedule ?: return null
    if (prompt.isBlank()) return null
    return CronManageParams(action = CronAction.ADD, name = name.trim().ifEmpty { null }, schedule = schedule.wire(),
                            prompt = prompt.trim(), repeat = repeat?.let(::JsonPrimitive), deliver = delivery.wire())
}

/**
 * The edit of [job] as `cron_update` takes it: the job's id and only the fields the user changed, each other one
 * null (DC-J8, invariant 2); null when nothing changed. The schedule is sent only when a preset was picked, never
 * derived from the job's own: that is the core's display, which does not parse back (`once in 45m` is refused).
 * Choosing no limit on a limited job sends 0, which the core reads as forever.
 */
fun JobForm.changesFrom(job: Job): CronUpdateParams? {
    val name = name.trim().takeIf { it.isNotEmpty() && it != job.name }
    val prompt = prompt.trim().takeIf { it.isNotEmpty() && it != job.prompt.trim() }
    val schedule = schedule?.wire()
    val deliver = delivery.takeIf { it != job.delivery }?.wire()
    val repeat = if (repeat == job.times) null else repeat ?: 0
    if (name == null && prompt == null && schedule == null && deliver == null && repeat == null) return null
    return CronUpdateParams(jobId = job.id.value, name = name, schedule = schedule, prompt = prompt, deliver = deliver, repeat = repeat)
}

/** Whether the form can be saved: a new one with a prompt and a schedule; an edit of the listed [job] that changes something. */
fun JobForm.canSave(job: Job?): Boolean = if (id == null) addParams() != null else job != null && changesFrom(job) != null

/** The runs a job may make: a one-shot runs once (as the core sets it); otherwise the typed limit, blank or 0 being none. */
fun repeatOf(schedule: Schedule?, typed: String): Int? =
    if (schedule is Schedule.Once) 1 else typed.trim().toIntOrNull()?.takeIf { it > 0 }
