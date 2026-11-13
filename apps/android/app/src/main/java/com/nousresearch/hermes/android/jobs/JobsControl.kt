package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.CronTriggerParams
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronManageParams
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Scheduled jobs page: the core's jobs and why they will not run, if they will not. [jobs] is the core's last
 * answer, [busy] while the first list or a save is on its way, [error] the core's words when the list or a save was
 * refused. [form] is the job being made or edited (its starting values; the typed draft is the sheet's), [targets]
 * where it may deliver, read when the form opens. [confirmDelete] the job a delete waits to be confirmed for; [queued]
 * the jobs Run now was asked for, until the core shows their run (V1); [gatewayRunning] whether the always-on gateway
 * was running at the last list (V2).
 */
data class JobsUi(val jobs: List<Job> = emptyList(), val warning: JobsWarning? = null, val busy: Boolean = false,
                  val error: String? = null, val form: JobForm? = null, val targets: List<DeliveryTarget> = emptyList(),
                  val confirmDelete: JobId? = null, val queued: Map<JobId, RunMark> = emptyMap(),
                  val gatewayRunning: Boolean = false)

/** Run now is offered for [job] (§2.4 invariant 3, F1): the gateway runs, the job is scheduled, and not already queued. */
fun JobsUi.offersRunNow(job: Job): Boolean = gatewayRunning && job.state == JobState.SCHEDULED && job.id !in queued

/** Saving the open form: busy, or null when not admitted (a save already running, or no form: V12). */
private fun JobsUi.saving(): JobsUi? = if (busy || form == null) null else copy(busy = true, error = null)

/** Acting on the listed job [id]: busy, or null when not admitted (busy, a form open, or not listed). */
private fun JobsUi.acting(id: JobId): JobsUi? =
    if (busy || form != null || jobs.none { it.id == id }) null else copy(busy = true, error = null)

/** Deleting the confirmed job: busy with the confirmation gone, or null when not admitted (invariant 4). */
private fun JobsUi.deleting(): JobsUi? =
    if (busy || form != null || confirmDelete == null) null else copy(confirmDelete = null, busy = true, error = null)

/** Asking Run now for the listed job [id], with the gateway as seen at the tap: busy, or null when not offered (V10). */
private fun JobsUi.triggering(id: JobId): JobsUi? {
    val job = jobs.find { it.id == id } ?: return null
    return if (busy || form != null || !offersRunNow(job)) null else copy(busy = true, error = null)
}

/**
 * Lists the core's jobs through `cron.manage` (paused ones too) and judges the warning from the app's own gateway
 * record and the core ticker's heartbeat (D39 F2, F5). It never patches the list: every read is a full re-list.
 * It creates a job through `cron.manage add` and edits one through the app's `cron_update` call, sending only what
 * changed and the job's id (D39 F6, F10); after either it lists again, never patching the list.
 * It pauses, resumes and deletes a job through `cron.manage` by its id, deletes only the one the user confirmed, and
 * runs one now only by making it due for the gateway's ticker (the app's `cron_trigger`, never a run in this process),
 * offered while the gateway runs and the job is scheduled, marked queued until the core shows its run or two minutes
 * pass (D39 F1, §2.4); after each it lists again, never patching the list.
 * [state] is null while closed. [gateway] is the core, [bridge] the app's Python half (the ticker's record), [seen]
 * the app's own observation of the always-on gateway, [now] a monotonic clock in milliseconds (V3).
 */
class JobsControl(
    private val gateway: CoreGateway,
    private val bridge: AppBridge,
    private val seen: () -> GatewaySeen,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val screen = MutableStateFlow<JobsUi?>(null)
    val state: StateFlow<JobsUi?> = screen.asStateFlow()

    suspend fun open() {
        screen.value = JobsUi(busy = true)
        relist()
    }

    fun close() {
        screen.value = null
    }

    /** Lists the jobs again into an open page; a closed page asks nothing. */
    suspend fun refresh() {
        if (screen.value == null) return
        relist()
    }

    /** Lists again on each `cron.changed` until cancelled (the page runs it while it shows); other signals are not the jobs'. */
    suspend fun follow() {
        gateway.events.collect { if (it.decode(Events.CRON_CHANGED) != null) refresh() }
    }

    /** Pauses the listed job [id] through `cron.manage` by its id; the core judges whether it can be (V5). */
    suspend fun pause(id: JobId) {
        command(id, CronAction.PAUSE, "The job could not be paused.")
    }

    /** Resumes the listed job [id]; a one-shot whose time has passed is refused in the core's words. */
    suspend fun resume(id: JobId) {
        command(id, CronAction.RESUME, "The job could not be resumed.")
    }

    /**
     * Makes the listed job [id] due for the gateway's ticker through the app's `cron_trigger` (F1): nothing runs in
     * this process. Offered only while the gateway runs, read at the tap, and the job is scheduled (invariant 3; a
     * paused job would be resumed by it); a failed guard sends nothing and drops the offer (V10). Accepted, the job is
     * marked queued from the last run the page showed, then the jobs are listed again.
     */
    suspend fun runNow(id: JobId) {
        val running = seen().status == GatewayStatus.RUNNING
        // One atomic step: the offer follows the gateway as seen now, and a double tap triggers once.
        val before = screen.getAndUpdate { ui -> ui?.copy(gatewayRunning = running)?.let { it.triggering(id) ?: it } }
        val job = before?.copy(gatewayRunning = running)?.takeIf { it.triggering(id) != null }?.jobs?.find { it.id == id } ?: return
        finish {
            bridge.ask(BridgeCalls.CRON_TRIGGER, CronTriggerParams(id.value))  // its next_run_at is not shown (invariant 1)
            screen.update { it?.copy(queued = it.queued + (id to RunMark(job.last?.at, now()))) }
            null
        }
    }

    /** Asks to delete the listed job [id] (null stops asking); a job not listed asks nothing (V9). Nothing is sent. */
    fun askDelete(id: JobId?) {
        screen.update { ui ->
            if (ui == null || ui.busy || ui.form != null) ui
            else ui.copy(confirmDelete = id?.takeIf { i -> ui.jobs.any { it.id == i } }, error = null)
        }
    }

    /** Deletes the job [askDelete] named, through `cron.manage remove` by its id; nothing else is ever deleted (invariant 4). */
    suspend fun confirmDelete() {
        val before = screen.getAndUpdate { it?.deleting() ?: it }  // one atomic step: a double tap deletes once
        val id = before?.takeIf { it.deleting() != null }?.confirmDelete ?: return
        finish { manage(CronAction.REMOVE, id, "The job could not be deleted.") }
    }

    /** Pause or resume the listed job [id]: a closed or busy page, an open form or a job not listed sends nothing. */
    private suspend fun command(id: JobId, action: CronAction, fallback: String) {
        val before = screen.getAndUpdate { it?.acting(id) ?: it }
        before?.acting(id) ?: return
        finish { manage(action, id, fallback) }
    }

    /** `cron.manage [action]` on the job [id], sent by its id in the core's `name` field (invariant 2); the refusal, or null. */
    private suspend fun manage(action: CronAction, id: JobId, fallback: String): String? {
        val answer = gateway.call(Methods.CRON_MANAGE, CronManageParams(action = action, name = id.value))
        // The answer's job (or removed_job) is never shown: the re-list is (invariant 1).
        return if (answer.success == false || answer.error != null) answer.error ?: fallback else null
    }

    /**
     * The one tail of an action this page entered busy for: [act] returns the refusal (or throws). Refused, its words
     * show and nothing is listed, as the core changed nothing (V7); accepted, the jobs are listed again (invariant 1).
     */
    private suspend inline fun finish(act: () -> String?) {
        holdingBusy {
            val refused = try {
                act()
            } catch (failed: Exception) {
                forUser(failed)
            }
            if (refused != null) {
                screen.update { it?.copy(busy = false, error = refused) }
                return
            }
            relist()
        }
    }

    /** Opens the form for a new job, into an open page that is not busy. */
    suspend fun newJob() {
        openForm(JobForm.blank())
    }

    /** Opens the form on the listed job [id]; a job no longer listed opens nothing. */
    suspend fun edit(id: JobId) {
        val job = screen.value?.jobs?.find { it.id == id } ?: return
        openForm(JobForm.of(job))
    }

    /** Closes the form unsaved: the draft is dropped. */
    fun cancelForm() {
        screen.update { it?.copy(form = null, targets = emptyList(), error = null) }
    }

    /**
     * Saves [form]: a new job through `cron.manage add`, an edit through `cron_update` with only what changed (nothing
     * changed: nothing is sent). A refusal is the core's words, with the form still open and no re-list; once the
     * core accepts, the form closes and the jobs are listed again (invariant 1). One save at a time (V12).
     */
    suspend fun save(form: JobForm) {
        val before = screen.getAndUpdate { it?.saving() ?: it }  // one atomic step: a double tap saves once
        before?.saving() ?: return
        holdingBusy {
            val refused = try {
                if (form.id == null) add(form) else update(form, before.jobs)
            } catch (failed: Exception) {
                forUser(failed)
            }
            if (refused != null) {
                screen.update { it?.copy(busy = false, error = refused) }
                return
            }
            screen.update { it?.copy(form = null, targets = emptyList(), busy = false) }
            relist()
        }
    }

    /** Adds [form] as a new job; the core's words when it refuses. Its answer's job is not shown: the re-list is. */
    private suspend fun add(form: JobForm): String? {
        val params = form.addParams() ?: return "Give the job a prompt and a schedule."
        val answer = gateway.call(Methods.CRON_MANAGE, params)
        return if (answer.success == false || answer.error != null) answer.error ?: "The job could not be saved." else null
    }

    /** Edits the listed job [form] is for, by its id, with only what changed; the core's words when it refuses. */
    private suspend fun update(form: JobForm, jobs: List<Job>): String? {
        val job = jobs.find { it.id == form.id } ?: return "That job no longer exists."
        val changes = form.changesFrom(job) ?: return null  // nothing changed: nothing to send
        bridge.ask(BridgeCalls.CRON_UPDATE, changes)  // an {error} reply is a CoreError in the core's words
        return null
    }

    /** Opens [form] with Local as its only target, then the core's targets once read (V7: unread stays Local alone). */
    private suspend fun openForm(form: JobForm) {
        screen.update { ui -> if (ui == null || ui.busy) ui else ui.copy(form = form, targets = deliveryTargets(null), error = null) }
        if (screen.value?.form != form) return
        val targets = try {
            deliveryTargets(bridge.ask(BridgeCalls.CRON_DELIVERY_TARGETS))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            deliveryTargets(null)
        }
        // A form closed or replaced meanwhile keeps what it has.
        screen.update { ui -> if (ui?.form == form) ui.copy(targets = targets) else ui }
    }

    /** Runs a save or an action this page entered busy for; a cancellation leaves it not busy (forUser rethrows it), and is rethrown. */
    private suspend inline fun holdingBusy(change: () -> Unit) {
        try {
            change()
        } catch (cancelled: CancellationException) {
            screen.update { it?.copy(busy = false) }
            throw cancelled
        }
    }

    /** The one read path: the core's full list, the ticker's record, then the gateway as seen now. */
    private suspend fun relist() {
        val answer = try {
            gateway.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true)))
        } catch (failed: Exception) {
            val words = forUser(failed)
            screen.update { it?.copy(busy = false, error = words) }
            return
        }
        if (answer.success == false || answer.error != null) {
            screen.update { it?.copy(busy = false, error = answer.error ?: "The core did not list the jobs.") }
            return
        }
        val jobs = answer.jobs.orEmpty().mapNotNull(Job::of)
        // An unreadable ticker is "cannot tell", as the core's own probe treats a crash (V10).
        val ticker = try {
            TickerFacts.of(bridge.ask(BridgeCalls.CRON_SCHEDULER))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        // Read last, so the gateway's state is the freshest input; a page closed meanwhile stays closed, and an open
        // form keeps its targets and its save's busy and error (V11). Outside a form a list ends busy: a cron.changed
        // from an action's own write may end it a moment before that action's re-list, which is accepted, as the list
        // it shows is already the core's state after the write. Queued marks and a confirmation last only while the
        // core's list still bears them out (V4, V9, V12).
        val seenNow = seen()
        val warning = jobsWarning(seenNow, ticker, jobs)
        val at = now()
        screen.update {
            it?.let { ui -> ui.copy(jobs = jobs, warning = warning, gatewayRunning = seenNow.status == GatewayStatus.RUNNING,
                                    queued = stillQueued(ui.queued, jobs, at),
                                    confirmDelete = ui.confirmDelete?.takeIf { id -> jobs.any { j -> j.id == id } },
                                    busy = ui.form != null && ui.busy, error = if (ui.form != null) ui.error else null) }
        }
    }
}
