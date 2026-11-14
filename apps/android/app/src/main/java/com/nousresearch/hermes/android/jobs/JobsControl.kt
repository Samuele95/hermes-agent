package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.CronRunOwnedParams
import com.nousresearch.hermes.bridge.contract.CronRunsParams
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
 * answer, [activity] what the page is doing (the first list, a save, an action on one job, a delete, a runs read,
 * opening a run, a Run now), one at a time, each ended only by the step that began it; [busy] whether one is. [error]
 * the core's words when the list or a save was refused, shown on the page and in the open form's sheet. [form] is
 * the job being made or edited (its starting values; the typed draft is the sheet's), [targets] where it may deliver, read when the form opens. [confirmDelete] the job a delete waits to be confirmed for; [queued]
 * the jobs Run now was asked for, until the core shows their run (V1); [gatewayRunning] whether the always-on gateway
 * was running at the last list (V2); [runs] the job whose past runs the sheet shows, while it shows.
 */
data class JobsUi(val jobs: List<Job> = emptyList(), val warning: JobsWarning? = null,
                  val activity: JobActivity = JobActivity.Idle,
                  val error: String? = null, val form: JobForm? = null, val targets: List<DeliveryTarget> = emptyList(),
                  val confirmDelete: JobId? = null, val queued: Map<JobId, RunMark> = emptyMap(),
                  val gatewayRunning: Boolean = false, val runs: RunsView? = null) {
    /** Whether any activity is on its way: the screens' one read (§7 row 1). */
    val busy: Boolean get() = activity != JobActivity.Idle
}

/**
 * The runs sheet of [job]: its past runs, newest first, as the core last listed them; [loaded] false while the
 * first read is on its way; [error] the core's words when a read, an open or a list failed while the sheet shows (V3).
 */
data class RunsView(val job: JobId, val runs: List<JobRun> = emptyList(), val loaded: Boolean = false,
                    val error: String? = null)

/** Run now is offered for [job] (§2.4 invariant 3, F1): the gateway runs, the job is scheduled, and not already queued. */
fun JobsUi.offersRunNow(job: Job): Boolean = gatewayRunning && job.state == JobState.SCHEDULED && job.id !in queued

/** This page with [mine] ended while it is still the activity; any other is left as it is (§2 JobActivity). */
private fun JobsUi.ending(mine: JobActivity): JobsUi = if (activity == mine) copy(activity = JobActivity.Idle) else this

/**
 * This page with a failure's [words] where the user is looking (/improve 20261113T221531Z #7): the open form's line
 * (the form sheet shows [JobsUi.error]), else the open runs sheet's, else the page's error line.
 */
private fun JobsUi.telling(words: String): JobsUi = when {
    form != null -> copy(error = words)
    runs != null -> copy(runs = runs.copy(error = words))
    else -> copy(error = words)
}

/** Saving the open form: [JobActivity.Saving], or null when not admitted (the page not idle, or no form: V12). */
private fun JobsUi.saving(): JobsUi? =
    if (activity != JobActivity.Idle || form == null) null else copy(activity = JobActivity.Saving, error = null)

/** Acting on the listed job [id]: [JobActivity.Acting], or null when not admitted (the page not idle, a form open, or not listed). */
private fun JobsUi.acting(id: JobId): JobsUi? =
    if (activity != JobActivity.Idle || form != null || jobs.none { it.id == id }) null
    else copy(activity = JobActivity.Acting(id), error = null)

/** Deleting the confirmed job: [JobActivity.Deleting] with the confirmation gone, or null when not admitted (invariant 4). */
private fun JobsUi.deleting(): JobsUi? =
    if (activity != JobActivity.Idle || form != null) null
    else confirmDelete?.let { id -> copy(confirmDelete = null, activity = JobActivity.Deleting(id), error = null) }

/** Showing [id]'s runs: the sheet open and reading, or null when not admitted (the page not idle, a form open, or not listed). */
private fun JobsUi.showing(id: JobId): JobsUi? =
    if (activity != JobActivity.Idle || form != null || jobs.none { it.id == id }) null
    else copy(activity = JobActivity.Showing(id), runs = RunsView(id), error = null)

/** Opening the shown run [run]: [JobActivity.Opening], or null when not admitted (the page not idle, a form open, or [run] not in the sheet). */
private fun JobsUi.opening(run: StoredSessionId): JobsUi? {
    val view = runs ?: return null
    return if (activity != JobActivity.Idle || form != null || view.runs.none { it.sessionId == run }) null
           else copy(activity = JobActivity.Opening(run), runs = view.copy(error = null))
}

/** Asking Run now for the listed job [id], with the gateway as seen at the tap: [JobActivity.Triggering], or null when not offered (V10). */
private fun JobsUi.triggering(id: JobId): JobsUi? {
    val job = jobs.find { it.id == id } ?: return null
    return if (activity != JobActivity.Idle || form != null || !offersRunNow(job)) null
           else copy(activity = JobActivity.Triggering(id), error = null)
}

/** Asks the app's `cron_run_owned` whether the gateway's scheduler still owns [run]: a value read each time, never kept here. */
suspend fun AppBridge.runOwnership(run: StoredSessionId): RunOwnership =
    RunOwnership.of(ask(BridgeCalls.CRON_RUN_OWNED, CronRunOwnedParams(run.value)))

/**
 * Lists the core's jobs through `cron.manage` (paused ones too) and judges the warning from the app's own gateway
 * record and the core ticker's heartbeat (D39 F2, F5). It never patches the list: every read is a full re-list.
 * It creates a job through `cron.manage add` and edits one through the app's `cron_update` call, sending only what
 * changed and the job's id (D39 F6, F10); after either it lists again, never patching the list.
 * It pauses, resumes and deletes a job through `cron.manage` by its id, deletes only the one the user confirmed, and
 * runs one now only by making it due for the gateway's ticker (the app's `cron_trigger`, never a run in this process),
 * offered while the gateway runs and the job is scheduled, marked queued until the core shows its run or two minutes
 * pass (D39 F1, §2.4); after each it lists again, never patching the list.
 * It shows a job's past runs as the core's own per-job query lists them (the app's `cron_runs`, by the job's id,
 * never derived from the session list), and opens one by continuing its session in the chat, the drawer's way,
 * closing the page only once the chat took it (D39 F7).
 * One activity runs at a time, admitted only while the page is idle and ended only by the step that began it; a
 * re-list on `cron.changed` ends none but its own (/architect 20261113T220113Z #1).
 * [state] is null while closed. [gateway] is the core, [bridge] the app's Python half (the ticker's record), [seen]
 * the app's own observation of the always-on gateway, [resume] continues a stored session in the chat (the chat's own
 * `session.resume`, the call the drawer's pick makes), [now] a monotonic clock in milliseconds (V3).
 */
class JobsControl(
    private val gateway: CoreGateway,
    private val bridge: AppBridge,
    private val seen: () -> GatewaySeen,
    private val resume: suspend (StoredSessionId) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val screen = MutableStateFlow<JobsUi?>(null)
    val state: StateFlow<JobsUi?> = screen.asStateFlow()

    suspend fun open() {
        screen.value = JobsUi(activity = JobActivity.Listing)
        relist()
    }

    fun close() {
        screen.value = null
    }

    /**
     * Lists the jobs again into an open page, and the open sheet's runs; a closed page asks nothing; a list that failed
     * reads no runs: the sheet keeps its runs, with the list's words.
     */
    suspend fun refresh() {
        if (screen.value == null) return
        // The open sheet's runs, read again (V6), only after a list that landed: a read's answer would wipe the list's
        // words (K6 V2).
        if (relist()) screen.value?.runs?.job?.let { readRuns(it) }
    }

    /** Shows the past runs of the listed job [id] in a sheet, read from the core by its id (F7); null closes the sheet. */
    suspend fun showRuns(id: JobId?) {
        if (id == null) {
            // A sheet may close while its own first read runs (V4).
            screen.update { ui ->
                if (ui == null || (ui.activity != JobActivity.Idle && ui.activity !is JobActivity.Showing)) ui
                else ui.copy(runs = null)
            }
            return
        }
        val before = screen.getAndUpdate { it?.showing(id) ?: it }  // its own read, as Showing (V4)
        before?.showing(id) ?: return
        holding(JobActivity.Showing(id)) { readRuns(id) }
        screen.update { it?.ending(JobActivity.Showing(id)) }
    }

    /**
     * Continues the shown run [run] in the chat, then closes the page; a run the core cannot open keeps the sheet, with
     * its words, or tells the page when a re-list closed the sheet meanwhile (its job left the list).
     */
    suspend fun openRun(run: StoredSessionId) {
        val before = screen.getAndUpdate { it?.opening(run) ?: it }  // one atomic step: a double tap opens once
        before?.opening(run) ?: return
        holding(JobActivity.Opening(run)) {
            try {
                resume.invoke(run)  // the chat's own session.resume: jobs/ sends no session call of its own
            } catch (failed: Exception) {
                val words = forUser(failed)
                screen.update { it?.ending(JobActivity.Opening(run))?.telling(words) }
                return
            }
            screen.value = null  // only once the chat took the run; the chat now shows it
        }
    }

    /** Reads [id]'s runs into the sheet still open for it; a sheet closed or moved to another job meanwhile is left as it is. */
    private suspend fun readRuns(id: JobId) {
        val read = try {
            // By the job's id (invariant 2), in the core's order, newest first: never re-sorted, never parsed.
            Result.success(bridge.ask(BridgeCalls.CRON_RUNS, CronRunsParams(id.value, RUNS_SHOWN)).runs.mapNotNull(JobRun::of))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            Result.failure(failed)
        }
        // A failed read keeps the runs shown, with the words (V6).
        val words = read.exceptionOrNull()?.let { forUser(it as Exception) }
        screen.update { ui ->
            val view = ui?.runs?.takeIf { it.job == id } ?: return@update ui
            ui.copy(runs = read.fold({ view.copy(runs = it, loaded = true, error = null) },
                                     { view.copy(loaded = true, error = words) }))
        }
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
        finish(JobActivity.Triggering(id)) {
            bridge.ask(BridgeCalls.CRON_TRIGGER, CronTriggerParams(id.value))  // its next_run_at is not shown (invariant 1)
            screen.update { it?.copy(queued = it.queued + (id to RunMark(job.last?.at, now()))) }
            null
        }
    }

    /** Asks to delete the listed job [id] (null stops asking); a job not listed asks nothing (V9). Nothing is sent. */
    fun askDelete(id: JobId?) {
        screen.update { ui ->
            if (ui == null || ui.activity != JobActivity.Idle || ui.form != null) ui
            else ui.copy(confirmDelete = id?.takeIf { i -> ui.jobs.any { it.id == i } }, error = null)
        }
    }

    /** Deletes the job [askDelete] named, through `cron.manage remove` by its id; nothing else is ever deleted (invariant 4). */
    suspend fun confirmDelete() {
        val before = screen.getAndUpdate { it?.deleting() ?: it }  // one atomic step: a double tap deletes once
        val id = before?.takeIf { it.deleting() != null }?.confirmDelete ?: return
        finish(JobActivity.Deleting(id)) { manage(CronAction.REMOVE, id, "The job could not be deleted.") }
    }

    /** Pause or resume the listed job [id]: a closed page or one not idle, an open form or a job not listed sends nothing. */
    private suspend fun command(id: JobId, action: CronAction, fallback: String) {
        val before = screen.getAndUpdate { it?.acting(id) ?: it }
        before?.acting(id) ?: return
        finish(JobActivity.Acting(id)) { manage(action, id, fallback) }
    }

    /** `cron.manage [action]` on the job [id], sent by its id in the core's `name` field (invariant 2); the refusal, or null. */
    private suspend fun manage(action: CronAction, id: JobId, fallback: String): String? {
        val answer = gateway.call(Methods.CRON_MANAGE, CronManageParams(action = action, name = id.value))
        // The answer's job (or removed_job) is never shown: the re-list is (invariant 1).
        return if (answer.success == false || answer.error != null) answer.error ?: fallback else null
    }

    /**
     * The one tail of an action this page entered [mine] for: [act] returns the refusal (or throws). Refused, its words
     * show and nothing is listed, as the core changed nothing (V7); accepted, the jobs are listed again (invariant 1),
     * and [mine] ends only once that list landed.
     */
    private suspend inline fun finish(mine: JobActivity, act: () -> String?) {
        holding(mine) {
            val refused = try {
                act()
            } catch (failed: Exception) {
                forUser(failed)
            }
            if (refused != null) {
                screen.update { it?.ending(mine)?.copy(error = refused) }
                return
            }
            relist()
            screen.update { it?.ending(mine) }
        }
    }

    /** Opens the form for a new job, into an open page that is idle. */
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
        holding(JobActivity.Saving) {
            val refused = try {
                if (form.id == null) add(form) else update(form, before.jobs)
            } catch (failed: Exception) {
                forUser(failed)
            }
            if (refused != null) {
                screen.update { it?.ending(JobActivity.Saving)?.copy(error = refused) }
                return
            }
            screen.update { it?.ending(JobActivity.Saving)?.copy(form = null, targets = emptyList()) }
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
        screen.update { ui ->
            if (ui == null || ui.activity != JobActivity.Idle) ui else ui.copy(form = form, targets = deliveryTargets(null), error = null)
        }
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

    /** Runs a step this page entered [mine] for; a cancellation ends that step's own activity (forUser rethrows it), and is rethrown. */
    private suspend inline fun holding(mine: JobActivity, change: () -> Unit) {
        try {
            change()
        } catch (cancelled: CancellationException) {
            screen.update { it?.ending(mine) }
            throw cancelled
        }
    }

    /**
     * The one read path: the core's full list, the ticker's record, then the gateway as seen now; true when the list
     * landed. A failure's words go where the user is looking (`telling`).
     */
    private suspend fun relist(): Boolean {
        val answer = try {
            gateway.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true)))
        } catch (failed: Exception) {
            val words = forUser(failed)
            screen.update { it?.ending(JobActivity.Listing)?.telling(words) }
            return false
        }
        if (answer.success == false || answer.error != null) {
            screen.update { it?.ending(JobActivity.Listing)?.telling(answer.error ?: "The core did not list the jobs.") }
            return false
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
        // form keeps its targets and its error (V11). A list ends only the page's own Listing (the open's); any other
        // activity on its way (a save, an action, a runs read, an open, a Run now) survives it, and its own step ends
        // it (§2 JobActivity). Queued marks and a confirmation last only while the core's list still bears them out
        // (V4, V9, V12), and the runs sheet only while its job is listed. A failed list (above) never reaches here: its
        // words stay where `telling` put them.
        val seenNow = seen()
        val warning = jobsWarning(seenNow, ticker, jobs)
        val at = now()
        screen.update {
            it?.let { ui -> ui.copy(jobs = jobs, warning = warning, gatewayRunning = seenNow.status == GatewayStatus.RUNNING,
                                    queued = stillQueued(ui.queued, jobs, at),
                                    confirmDelete = ui.confirmDelete?.takeIf { id -> jobs.any { j -> j.id == id } },
                                    runs = ui.runs?.takeIf { view -> jobs.any { j -> j.id == view.job } },
                                    activity = ui.ending(JobActivity.Listing).activity,
                                    error = if (ui.form != null) ui.error else null) }
        }
        return true
    }
}
