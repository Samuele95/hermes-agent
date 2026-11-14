package com.nousresearch.hermes.android

import android.Manifest
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.gateway.GatewayRecord
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.android.gateway.writeGatewayRecord
import com.nousresearch.hermes.android.jobs.JobId
import com.nousresearch.hermes.android.jobs.JobState
import com.nousresearch.hermes.android.jobs.JobsControl
import com.nousresearch.hermes.android.jobs.JobsUi
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronJobRow
import com.nousresearch.hermes.bridge.protocol.CronManageParams
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import java.time.OffsetDateTime
import java.util.Collections
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A job row's actions over the real embedded core (no model, no network, no token): pause, resume and delete change
 * the core's own job; Run now is not offered while the gateway is off, and with the gateway's record stubbed it only
 * makes the job due (D39 F1, V8). The `:gateway` stays off in every test, so no ticker ever meets a due job, and every
 * `j7 probe` job a test adds is removed again.
 */
@RunWith(AndroidJUnit4::class)
class JobActionsDeviceTest {
    private companion object {
        const val TAG = "JobActionsDeviceTest"
        const val PROBE = "j7 probe"
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

    /** A fresh `cron.manage list include_disabled=true`: the core's own rows, not any page's. */
    private fun coreList(): List<CronJobRow> = runBlocking {
        ready.gateway.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true))).jobs.orEmpty()
    }

    /** Adds a probe a week away, delivered locally, and returns its id. */
    private fun addProbe(n: String): JobId = runBlocking {
        val added = ready.gateway.call(Methods.CRON_MANAGE,
                                       CronManageParams(action = CronAction.ADD, name = "$PROBE ${System.nanoTime()} $n",
                                                        schedule = "every 10080m", prompt = "Say hello.", deliver = "local"))
        JobId(checkNotNull(added.jobId) { "add answered ${added.error}" })
    }

    /** Removes every job a test of this class added, found by name in a fresh list. */
    private fun removeProbes() {
        runCatching {
            runBlocking {
                coreList().filter { it.name.startsWith(PROBE) }.forEach {
                    ready.gateway.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = it.jobId))
                }
            }
        }
    }

    private fun gatewayOff() {
        if (gatewayPid() == null) return
        app.gateway.turnOff()
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
        gatewayOff()
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun pauseResumeAndDeleteChangeTheCoresJob() {
        gatewayOff()
        val jobs = ready.jobs
        val ui: () -> JobsUi = { checkNotNull(jobs.state.value) }
        // As the page runs it while it shows; the timeline records whether a cron.changed re-list left the action's activity alone.
        val start = SystemClock.elapsedRealtime()
        val timeline = Collections.synchronizedList(mutableListOf<String>())
        val watcher = GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            ready.gateway.events.collect {
                if (it.decode(Events.CRON_CHANGED) != null) timeline += "${SystemClock.elapsedRealtime() - start} cron.changed"
            }
        }
        val busy = GlobalScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            jobs.state.collect { timeline += "${SystemClock.elapsedRealtime() - start} activity=${it?.activity}" }
        }
        val follower = GlobalScope.launch(Dispatchers.IO) { jobs.follow() }
        try {
            val id = addProbe("actions")
            runBlocking { jobs.open() }
            val listed = { coreList().single { it.jobId == id.value } }

            timeline += "pause"
            runBlocking { jobs.pause(id) }
            assertNull(ui().error)
            assertEquals(JobState.PAUSED, ui().jobs.single { it.id == id }.state)
            assertEquals("paused", listed().state)
            Log.i(TAG, "after pause: listed ${ui().jobs.single { it.id == id }.state}, core '${listed().state}', next ${listed().nextRunAt}")

            timeline += "resume"
            runBlocking { jobs.resume(id) }
            assertNull(ui().error)
            val resumed = ui().jobs.single { it.id == id }
            assertEquals(JobState.SCHEDULED, resumed.state)
            assertNotNull(resumed.nextRunAt)
            Log.i(TAG, "after resume: listed ${resumed.state}, next ${resumed.nextRunAt}; core '${listed().state}', next ${listed().nextRunAt}")

            jobs.askDelete(id)
            assertEquals(id, ui().confirmDelete)
            assertTrue(coreList().any { it.jobId == id.value })

            timeline += "remove"
            runBlocking { jobs.confirmDelete() }
            assertNull(ui().error)
            assertNull(ui().confirmDelete)
            assertTrue(ui().jobs.none { it.id == id })
            assertTrue(coreList().none { it.jobId == id.value })
            SystemClock.sleep(1_000)  // let a late cron.changed land in the timeline
            Log.i(TAG, "after remove: gone from the page and the core; timeline ${timeline.toList()}")
        } finally {
            follower.cancel()
            busy.cancel()
            watcher.cancel()
            removeProbes()
            jobs.close()
        }
    }

    @SdkSuppress(minSdkVersion = 26)  // java.time; the phone is API 36
    @Test
    fun withTheGatewayOffRunNowIsNotOfferedAndSendsNothing() {
        gatewayOff()
        val jobs = ready.jobs
        try {
            val id = addProbe("off")
            val before = coreList().single { it.jobId == id.value }.nextRunAt
            runBlocking { jobs.open() }
            val ui = checkNotNull(jobs.state.value)
            assertFalse(ui.gatewayRunning)

            runBlocking { jobs.runNow(id) }

            val after = coreList().single { it.jobId == id.value }.nextRunAt
            Log.i(TAG, "gateway off: next_run_at $before -> $after")
            assertEquals(before, after)
            assertTrue(OffsetDateTime.parse(checkNotNull(after)).isAfter(OffsetDateTime.now().plusDays(6)))
            assertTrue(checkNotNull(jobs.state.value).queued.isEmpty())
            assertNull(checkNotNull(jobs.state.value).error)
        } finally {
            removeProbes()
            jobs.close()
        }
    }

    @SdkSuppress(minSdkVersion = 26)  // java.time; the phone is API 36
    @Test
    fun runNowOnlyMakesTheJobDueHere() {
        gatewayOff()
        var triggerMs = -1L
        val timed = AppBridge { function, arg ->
            val t0 = SystemClock.elapsedRealtime()
            EmbeddedBridge.call(function, arg).also { if (function == "cron_trigger") triggerMs = SystemClock.elapsedRealtime() - t0 }
        }
        // The page's controller over the same core, with only the gateway's record stubbed: no ticker runs here.
        val control = JobsControl(ready.gateway, timed, seen = { GatewaySeen(GatewayStatus.RUNNING, pid = -1) }, resume = {})
        try {
            val id = addProbe("now")
            val before = OffsetDateTime.now()
            runBlocking { control.open() }
            assertTrue(checkNotNull(control.state.value).gatewayRunning)
            check(gatewayPid() == null) { "a :gateway is alive: a due job must never meet its ticker" }

            runBlocking { control.runNow(id) }

            val row = coreList().single { it.jobId == id.value }
            // Removed at once, before any other assertion or log: a due job must not wait in the store.
            runBlocking { ready.gateway.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = id.value)) }
            val ui = checkNotNull(control.state.value)
            assertNull(ui.error)
            assertTrue(id in ui.queued)
            val next = OffsetDateTime.parse(checkNotNull(row.nextRunAt))
            Log.i(TAG, "run now: next_run_at ${row.nextRunAt} against the phone's ${OffsetDateTime.now()}, state '${row.state}', " +
                       "last_run_at ${row.lastRunAt}; cron_trigger held hermes-python $triggerMs ms")
            assertTrue("$next before $before", !next.isBefore(before.minusSeconds(5)))
            assertTrue("$next after now", !next.isAfter(OffsetDateTime.now().plusSeconds(5)))
            assertEquals("scheduled", row.state)
            assertNull(row.lastRunAt)
            assertTrue(coreList().none { it.jobId == id.value })
        } finally {
            removeProbes()
            control.close()
        }
    }
}
