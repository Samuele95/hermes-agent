package com.nousresearch.hermes.android

import android.Manifest
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.android.gateway.GatewayRecord
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.android.gateway.writeGatewayRecord
import com.nousresearch.hermes.android.jobs.JobId
import com.nousresearch.hermes.android.jobs.JobsControl
import com.nousresearch.hermes.android.jobs.RunsView
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronJobRow
import com.nousresearch.hermes.bridge.protocol.CronManageParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionListParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A job's past runs over the real embedded core (no model, no network, no token): a job that never ran has an empty
 * list from the app's `cron_runs`, and a run the core cannot open keeps the jobs page with the core's words while the
 * chat stays where it was (D39 F7, V10). The `:gateway` stays off in every test, so no job ever fires, and every
 * `j8 probe` job a test adds is removed again.
 */
@RunWith(AndroidJUnit4::class)
class JobRunsDeviceTest {
    private companion object {
        const val TAG = "JobRunsDeviceTest"
        const val PROBE = "j8 probe"
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

    /** How many stored sessions the drawer's listing calls cron runs: read only, never opened (FOUND). */
    private fun logCronSessions() {
        val rows = runBlocking { ready.gateway.call(Methods.SESSION_LIST, SessionListParams(limit = 100)).sessions }
        Log.i(TAG, "session.list: ${rows.count { it.source == "cron" }} of ${rows.size} rows have source cron")
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

    @Test
    fun aJobThatNeverRanHasNoRunsOnThePhone() {
        gatewayOff()
        val jobs = ready.pages.jobs
        try {
            val id = addProbe("never ran")
            runBlocking { jobs.open() }

            val t0 = SystemClock.elapsedRealtime()
            runBlocking { jobs.showRuns(id) }
            val ms = SystemClock.elapsedRealtime() - t0

            val ui = checkNotNull(jobs.state.value)
            assertEquals(RunsView(id, emptyList(), loaded = true), ui.runs)
            assertNull(ui.error)
            Log.i(TAG, "cron_runs of a job that never ran: ${ui.runs?.runs?.size} runs, showRuns held hermes-python $ms ms")
            logCronSessions()

            runBlocking { jobs.showRuns(null) }
            assertNull(checkNotNull(jobs.state.value).runs)
        } finally {
            removeProbes()
            jobs.close()
        }
    }

    @Test
    fun aRunTheCoreCannotOpenKeepsThePageWithItsWords() {
        gatewayOff()
        var probe = ""
        // One run that was never stored, for the probe; every other call goes to the real Python half.
        val runs = AppBridge { function, arg ->
            if (function == "cron_runs") {
                """{"runs":[{"session_id":"cron_${probe}_20261113_093000","started_at":${System.currentTimeMillis() / 1000},
                    "ended_at":null,"messages":1,"preview":null,"active":false}]}"""
            } else {
                EmbeddedBridge.call(function, arg)
            }
        }
        val control = JobsControl(ready.gateway, runs, seen = { GatewaySeen(GatewayStatus.STOPPED) }, continueInChat = ready.chat::resume)
        val before = ready.chat.sessionId
        try {
            val id = addProbe("cannot open")
            probe = id.value
            runBlocking { control.open() }
            runBlocking { control.showRuns(id) }
            assertEquals(1, checkNotNull(control.state.value).runs?.runs?.size)

            runBlocking { control.openRun(StoredSessionId("cron_${id.value}_20261113_093000")) }

            val ui = control.state.value
            assertNotNull(ui)
            assertFalse(ui!!.busy)
            val words = ui.runs!!.error
            assertNotNull(words)
            Log.i(TAG, "a run the core cannot open: '$words'; the chat stayed on ${ready.chat.sessionId} (was $before)")
            assertEquals(before, ready.chat.sessionId)
            logCronSessions()
        } finally {
            removeProbes()
            control.close()
        }
    }
}
