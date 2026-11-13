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
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/**
 * The Scheduled jobs page: the core's jobs and why they will not run, if they will not. [jobs] is the core's last
 * answer, [busy] while the first list is on its way, [error] the core's words when the list was refused.
 */
data class JobsUi(val jobs: List<Job> = emptyList(), val warning: JobsWarning? = null, val busy: Boolean = false,
                  val error: String? = null)

/**
 * Lists the core's jobs through `cron.manage` (paused ones too) and judges the warning from the app's own gateway
 * record and the core ticker's heartbeat (D39 F2, F5). It never patches the list: every read is a full re-list.
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
        // Read last, so the gateway's state is the freshest input; a page closed meanwhile stays closed.
        val warning = jobsWarning(seen(), ticker, jobs)
        screen.update { it?.let { JobsUi(jobs, warning) } }
    }
}
