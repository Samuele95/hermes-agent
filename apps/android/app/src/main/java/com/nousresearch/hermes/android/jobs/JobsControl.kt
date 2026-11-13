package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
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
 * where it may deliver, read when the form opens.
 */
data class JobsUi(val jobs: List<Job> = emptyList(), val warning: JobsWarning? = null, val busy: Boolean = false,
                  val error: String? = null, val form: JobForm? = null, val targets: List<DeliveryTarget> = emptyList())

/** Saving the open form: busy, or null when not admitted (a save already running, or no form: V12). */
private fun JobsUi.saving(): JobsUi? = if (busy || form == null) null else copy(busy = true, error = null)

/**
 * Lists the core's jobs through `cron.manage` (paused ones too) and judges the warning from the app's own gateway
 * record and the core ticker's heartbeat (D39 F2, F5). It never patches the list: every read is a full re-list.
 * It creates a job through `cron.manage add` and edits one through the app's `cron_update` call, sending only what
 * changed and the job's id (D39 F6, F10); after either it lists again, never patching the list.
 * [state] is null while closed. [gateway] is the core, [bridge] the app's Python half (the ticker's record), [seen]
 * the app's own observation of the always-on gateway.
 */
class JobsControl(
    private val gateway: CoreGateway,
    private val bridge: AppBridge,
    private val seen: () -> GatewaySeen,
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

    /** Runs a save this page entered busy for; a cancellation leaves it not busy (forUser rethrows it), and is rethrown. */
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
        // form keeps its targets and its save's busy and error (V11).
        val warning = jobsWarning(seen(), ticker, jobs)
        screen.update {
            it?.let { ui -> ui.copy(jobs = jobs, warning = warning, busy = ui.form != null && ui.busy,
                                    error = if (ui.form != null) ui.error else null) }
        }
    }
}
