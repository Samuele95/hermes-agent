package com.nousresearch.hermes.android

import android.Manifest
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.gateway.GatewayRecord
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.android.gateway.writeGatewayRecord
import com.nousresearch.hermes.android.jobs.JobId
import com.nousresearch.hermes.android.jobs.JobState
import com.nousresearch.hermes.android.jobs.JobsWarning
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronManageParams
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Scheduled jobs page's controller over the real embedded core and the real `:gateway` (no model, no network):
 * a job the core holds is listed by its id, and the warning follows the app's own gateway record and the ticker's
 * heartbeat, and a gateway turned on under the open page clears its warning within seconds, told by the gateway's own
 * record. Each test adds a weekly job, which the ticker never finds due here, and removes it again.
 */
@RunWith(AndroidJUnit4::class)
class JobsDeviceTest {
    private companion object {
        const val TAG = "JobsDeviceTest"
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as HermesApplication
    private val gatewayProcess = "${context.packageName}:gateway"

    private fun <T> waitFor(what: String, seconds: Int = 120, value: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        while (true) {
            value()?.let { return it }
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting for $what (gateway: ${observeGateway(context)})" }
            SystemClock.sleep(200)
        }
    }

    private fun gatewayPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == gatewayProcess }?.pid

    private val ready by lazy { waitFor("the core") { app.core.value as? CoreState.Ready } }

    /** Adds a weekly job (its first run a week away) and returns its id. */
    private fun addJob(): String = runBlocking {
        val added = ready.gateway.call(
            Methods.CRON_MANAGE,
            CronManageParams(action = CronAction.ADD, name = "j5 probe ${System.nanoTime()}", schedule = "every 10080m",
                             prompt = "Say hello.", deliver = "local"),
        )
        checkNotNull(added.jobId) { "add answered ${added.error}" }
    }

    private fun removeJob(id: String) {
        runCatching { runBlocking { ready.gateway.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = id)) } }
    }

    private fun gatewayEnded() {
        waitFor("no :gateway process", 60) { if (gatewayPid() == null) true else null }
    }

    @Before
    fun aStoppedGatewayRecord() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // A record left by an earlier, interrupted run would revive a gateway into these tests when the app opens.
        if (gatewayPid() == null) writeGatewayRecord(context, GatewayRecord(GatewayRecord.State.STOPPED, 0))
    }

    @After
    fun stopTheGateway() {
        if (gatewayPid() == null) return
        app.gateway.turnOff()
        gatewayEnded()
    }

    @Test
    fun withTheGatewayOffAJobShowsTheGatewayOffWarning() {
        if (gatewayPid() != null) {
            app.gateway.turnOff()
            gatewayEnded()
        }
        val id = addJob()
        try {
            runBlocking { ready.pages.jobs.open() }

            val ui = checkNotNull(ready.pages.jobs.state.value)
            assertNull(ui.error)
            assertEquals(JobState.SCHEDULED, ui.jobs.single { it.id == JobId(id) }.state)
            assertEquals(JobsWarning.GatewayOff(GatewayStatus.STOPPED), ui.warning)

            // For the report, not asserted: the core's own guess, which the app does not read.
            val raw = runBlocking { ready.gateway.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true))) }
            Log.i(TAG, "gateway off: cron.manage list gateway_running=${raw.gatewayRunning} warning=${raw.warning}")
            Log.i(TAG, "gateway off: cron_scheduler ${runBlocking { EmbeddedBridge.ask(BridgeCalls.CRON_SCHEDULER) }}")
        } finally {
            removeJob(id)
            ready.pages.jobs.close()
        }
    }

    @Test
    fun withTheGatewayRunningAFreshTickerShowsNoWarning() {
        val changes = AtomicInteger()
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val watcher = GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            ready.gateway.events.collect { if (it.decode(Events.CRON_CHANGED) != null) changes.incrementAndGet() }
        }
        var id: String? = null
        try {
            app.gateway.turnOn()
            waitFor("RUNNING") { observeGateway(context).status.takeIf { it == GatewayStatus.RUNNING } }
            val running = SystemClock.elapsedRealtime()
            val fresh = waitFor("a fresh ticker heartbeat", 150) {
                runBlocking { EmbeddedBridge.ask(BridgeCalls.CRON_SCHEDULER) }
                    .also { Log.i(TAG, "gateway running: cron_scheduler $it") }
                    .takeIf { it.heartbeatAgeS != null && it.heartbeatAgeS!! <= it.staleAfterS }
                    .also { if (it == null) SystemClock.sleep(2_000) }
            }
            Log.i(TAG, "first fresh heartbeat ${SystemClock.elapsedRealtime() - running} ms after RUNNING: $fresh")

            id = addJob()
            runBlocking { ready.pages.jobs.open() }

            val ui = checkNotNull(ready.pages.jobs.state.value)
            assertNull(ui.error)
            assertEquals(JobState.SCHEDULED, ui.jobs.single { it.id == JobId(id) }.state)
            assertNull(ui.warning)
            SystemClock.sleep(3_000)  // a little while open, to count the signals that arrive
            Log.i(TAG, "cron.changed while running: ${changes.get()} (one add)")
        } finally {
            watcher.cancel()
            id?.let(::removeJob)
            ready.pages.jobs.close()
            app.gateway.turnOff()
            gatewayEnded()
        }
    }

    @Test
    fun aGatewayTurnedOnUnderTheOpenPageClearsItsWarningWithinSeconds() {
        if (gatewayPid() != null) {
            app.gateway.turnOff()
            gatewayEnded()
        }
        val id = addJob()  // first: it brings the core up, and with it the native library the bridge call below needs
        val before = runBlocking { EmbeddedBridge.ask(BridgeCalls.CRON_SCHEDULER) }
        Log.i(TAG, "before the start: cron_scheduler $before (the stale branch runs only if heartbeatAgeS > staleAfterS)")
        val warnings = java.util.Collections.synchronizedList(mutableListOf<String>())
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val recorder = GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            ready.pages.jobs.state.collect { warnings += "${SystemClock.elapsedRealtime()} ${it?.warning}" }
        }
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        val follower = GlobalScope.launch(Dispatchers.IO) { ready.pages.jobs.followGateway(app.gatewayChanges) }
        try {
            runBlocking { ready.pages.jobs.open() }
            assertEquals(JobsWarning.GatewayOff(GatewayStatus.STOPPED), ready.pages.jobs.state.value?.warning)

            app.gateway.turnOn()
            waitFor("RUNNING") { observeGateway(context).status.takeIf { it == GatewayStatus.RUNNING } }
            val running = SystemClock.elapsedRealtime()
            // Nothing else lists here: no JobsPanel loop runs and the test calls no refresh(). Only followGateway can clear it.
            waitFor("the warning cleared", 12) { if (ready.pages.jobs.state.value?.warning == null) true else null }
            Log.i(TAG, "warning cleared ${SystemClock.elapsedRealtime() - running} ms after RUNNING; warnings seen: $warnings")
            assertEquals(JobState.SCHEDULED, ready.pages.jobs.state.value!!.jobs.single { it.id == JobId(id) }.state)
        } finally {
            follower.cancel()
            recorder.cancel()
            removeJob(id)
            ready.pages.jobs.close()
        }
    }
}
