package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.contract.CronRun
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A job's past runs (automation-07, D39 F7): asked of the core by the job's id through the app's `cron_runs` (never
 * derived from the session list, never parsed out of a run's id), shown only in the sheet still open for that job,
 * read again on a refresh, and gone with the job; a run opens only through the chat's own resume, and the page closes
 * only once the chat took it.
 */
class JobRunsTest {
    private var listed = ALL
    private val core = FakeCoreGateway { method, params ->
        check(method == "cron.manage") { "unexpected $method" }  // a session.* call from jobs/ fails here
        check(params["action"] == null) { "unexpected action ${params["action"]}" }
        listed
    }
    private var runsReply = TWO
    private var onRuns: () -> Unit = {}
    private var runsFails: Exception? = null
    private val bridgeCalls = mutableListOf<Pair<String, String>>()
    private val bridge = AppBridge { function, arg ->
        bridgeCalls += function to arg
        when (function) {
            "cron_scheduler" -> FRESH
            "cron_delivery_targets" -> """{"targets":[]}"""
            "cron_runs" -> {
                onRuns()
                runsFails?.let { throw it }
                runsReply
            }
            else -> error("unexpected $function")
        }
    }
    private val resumed = mutableListOf<StoredSessionId>()
    private var onResume: () -> Unit = {}
    private var resumeFails: Exception? = null
    private val seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
    private val control = JobsControl(core, bridge, seen = { seen }, resume = { stored ->
        onResume()
        resumeFails?.let { throw it }
        resumed += stored
    })

    private val j1 = JobId("j1")
    private val ui get() = control.state.value!!
    private val listCalls get() = core.calls.filter { it.second["action"] == null }
    private val runsCalls get() = bridgeCalls.filter { it.first == "cron_runs" }

    private val newRun = JobRun(StoredSessionId(NEW_ID), 1794562200L, null, 3, "Summarise the news", active = true)
    private val oldRun = JobRun(StoredSessionId(OLD_ID), 1794475800L, 1794475920L, 4, null, active = false)

    @Test
    fun showRunsAsksTheCoreForThatJobsRunsByItsId() = runTest {
        control.open()

        control.showRuns(j1)

        assertEquals(listOf("cron_runs" to """{"job_id":"j1","limit":20}"""), runsCalls)
        assertEquals(RunsView(j1, listOf(newRun, oldRun), loaded = true), ui.runs)  // the core's order, newest first
        assertEquals(1, listCalls.size)  // the open's list only
        assertFalse(ui.busy)
        assertNull(ui.error)
    }

    @Test
    fun aRunsRowIsTheCoresOwnValues() = runTest {
        val run = JobRun.of(CronRun(NEW_ID, 1794562200, null, 3, "  ", true))!!

        assertEquals(StoredSessionId(NEW_ID), run.sessionId)
        assertEquals(1794562200L, run.startedAt)
        assertNull(run.endedAt)
        assertEquals(3, run.messages)
        assertNull(run.preview)  // blank: no preview
        assertTrue(run.active)
        assertEquals("Summarise the news", JobRun.of(CronRun(NEW_ID, 1, null, 1, " Summarise the news ", true))!!.preview)
        assertEquals(1794475920L, JobRun.of(CronRun(OLD_ID, 1794475800, 1794475920, 4, null, false))!!.endedAt)

        assertNull(JobRun.of(CronRun(" ", 1794562200, null, 3, null, true)))
        runsReply = """{"runs":[$RUN_NEW,{"session_id":"","started_at":1,"ended_at":null,"messages":0,"preview":null,"active":false}]}"""
        control.open()
        control.showRuns(j1)
        assertEquals(1, ui.runs!!.runs.size)

        assertEquals(RunPhase.RUNNING, JobRun.of(CronRun(NEW_ID, 1, null, 1, null, true))!!.phase)
        assertEquals(RunPhase.UNFINISHED, JobRun.of(CronRun(NEW_ID, 1, null, 1, null, false))!!.phase)
        assertEquals(RunPhase.ENDED, JobRun.of(CronRun(NEW_ID, 1, 2, 1, null, false))!!.phase)
        assertEquals(RunPhase.ENDED, JobRun.of(CronRun(NEW_ID, 1, 2, 1, null, true))!!.phase)  // an end wins
    }

    @Test
    fun runTimeIsTheWallMinuteInTheGivenZone() {
        assertEquals("2026-11-13 09:30", runTime(1794562200, TimeZone.getTimeZone("UTC")))
        assertEquals("2026-11-13 10:30", runTime(1794562200, TimeZone.getTimeZone("Europe/Rome")))
    }

    @Test
    fun whileTheRunsAreReadTheSheetSaysSo() = runTest {
        control.open()
        var checked = false
        onRuns = {
            assertEquals(RunsView(j1), control.state.value!!.runs)
            assertFalse(control.state.value!!.busy)
            checked = true
        }

        control.showRuns(j1)

        assertTrue(checked)
        assertTrue(ui.runs!!.loaded)
    }

    @Test
    fun aJobThatNeverRanIsAnEmptyLoadedSheet() = runTest {
        runsReply = NONE
        control.open()

        control.showRuns(j1)

        assertEquals(RunsView(j1, emptyList(), loaded = true), ui.runs)
    }

    @Test
    fun aFailedReadShowsItsWordsInTheSheet() = runTest {
        runsFails = CoreError(0, "database is locked")
        control.open()

        control.showRuns(j1)

        assertEquals(RunsView(j1, emptyList(), loaded = true, error = "database is locked"), ui.runs)
        assertNull(ui.error)
        assertFalse(ui.busy)
    }

    @Test
    fun runsOfAJobNotListedOrWithAFormOpenAreNotAsked() = runTest {
        control.showRuns(j1)
        assertNull(control.state.value)
        assertTrue(bridgeCalls.isEmpty())
        assertTrue(core.calls.isEmpty())

        control.open()
        control.showRuns(JobId("j9"))
        assertTrue(runsCalls.isEmpty())
        assertNull(ui.runs)

        control.newJob()
        control.showRuns(j1)
        assertTrue(runsCalls.isEmpty())
        assertNull(ui.runs)
    }

    @Test
    fun showRunsNullClosesTheSheet() = runTest {
        control.open()
        control.showRuns(j1)

        control.showRuns(null)

        assertNull(ui.runs)
        assertEquals(1, runsCalls.size)
    }

    @Test
    fun aSheetClosedWhileReadingStaysClosed() = runTest {
        control.open()
        onRuns = { runBlocking { control.showRuns(null) } }

        control.showRuns(j1)

        assertNull(ui.runs)
    }

    @Test
    fun openRunContinuesThatSessionAndClosesThePage() = runTest {
        control.open()
        control.showRuns(j1)
        val lists = listCalls.size
        val reads = runsCalls.size

        control.openRun(StoredSessionId(OLD_ID))

        assertEquals(listOf(StoredSessionId(OLD_ID)), resumed)
        assertNull(control.state.value)
        assertEquals(lists, listCalls.size)
        assertEquals(reads, runsCalls.size)
    }

    @Test
    fun whileARunOpensThePageIsBusy() = runTest {
        control.open()
        control.showRuns(j1)
        var checked = false
        onResume = {
            assertTrue(control.state.value!!.busy)
            checked = true
        }

        control.openRun(StoredSessionId(NEW_ID))

        assertTrue(checked)
        assertEquals(1, resumed.size)
    }

    @Test
    fun aRunNotInTheSheetOpensNothing() = runTest {
        control.open()
        control.openRun(StoredSessionId(NEW_ID))
        assertTrue(resumed.isEmpty())
        assertNotNull(control.state.value)

        control.showRuns(j1)
        val before = control.state.value
        control.openRun(StoredSessionId("cron_j2_20261113_093000"))

        assertTrue(resumed.isEmpty())
        assertEquals(before, control.state.value)
        assertFalse(ui.busy)
    }

    @Test
    fun aRunTheChatCannotOpenKeepsThePageWithTheCoresWords() = runTest {
        resumeFails = CoreError(4007, "session not found")
        control.open()
        control.showRuns(j1)

        control.openRun(StoredSessionId(NEW_ID))

        assertNotNull(control.state.value)
        assertFalse(ui.busy)
        assertEquals("session not found", ui.runs!!.error)
        assertEquals(listOf(newRun, oldRun), ui.runs!!.runs)
    }

    @Test
    fun aSecondOpenWhileOneRunsIsNotSent() = runTest {
        control.open()
        control.showRuns(j1)
        onResume = {
            onResume = {}
            runBlocking { control.openRun(StoredSessionId(OLD_ID)) }
        }

        control.openRun(StoredSessionId(NEW_ID))

        assertEquals(listOf(StoredSessionId(NEW_ID)), resumed)
    }

    @Test
    fun aRefreshReadsTheOpenSheetsRunsAgain() = runTest {
        control.refresh()
        control.open()
        control.refresh()
        assertTrue(runsCalls.isEmpty())  // no sheet: no runs read

        control.showRuns(j1)
        runsReply = """{"runs":[${RUN_NEW.replace("\"ended_at\":null", "\"ended_at\":1794562320").replace("true", "false")},$RUN_OLD]}"""

        control.refresh()

        assertEquals(2, runsCalls.size)
        assertEquals(runsCalls[0], runsCalls[1])
        assertEquals(RunPhase.ENDED, ui.runs!!.runs.first().phase)
        assertEquals(2, ui.runs!!.runs.size)
    }

    @Test
    fun aFailedRereadKeepsTheRunsShown() = runTest {
        control.open()
        control.showRuns(j1)
        runsFails = CoreError(0, "database is locked")

        control.refresh()

        assertEquals(2, ui.runs!!.runs.size)
        assertEquals("database is locked", ui.runs!!.error)

        runsFails = null
        control.refresh()

        assertNull(ui.runs!!.error)
        assertEquals(2, ui.runs!!.runs.size)
    }

    @Test
    fun theSheetGoesWithItsJob() = runTest {
        control.open()
        control.showRuns(j1)
        listed = """{"success":true,"count":1,"jobs":[$PAUSED]}"""

        control.refresh()

        assertNull(ui.runs)
        assertEquals(1, runsCalls.size)
    }

    private companion object {
        const val NEW_ID = "cron_j1_20261113_093000"
        const val OLD_ID = "cron_j1_20261112_093000"
        const val SCHEDULED = """{"job_id":"j1","name":"Morning news","schedule":"30 9 * * *","state":"scheduled",
                                 "next_run_at":"2026-11-14T09:30:00+01:00","last_run_at":"2026-11-13T09:30:00+01:00",
                                 "last_status":"ok","prompt_preview":"Summarise the news","repeat":"forever"}"""
        const val PAUSED = """{"job_id":"j2","name":"Evening digest","schedule":"every 1440m","state":"paused",
                              "prompt_preview":"Digest the day","repeat":"forever"}"""
        const val ALL = """{"success":true,"count":2,"jobs":[$SCHEDULED,$PAUSED]}"""
        const val FRESH = """{"heartbeat_age_s":30,"success_age_s":30,"last_error":null,"stale_after_s":200}"""
        const val RUN_NEW = """{"session_id":"$NEW_ID","started_at":1794562200,"ended_at":null,
                               "messages":3,"preview":"Summarise the news","active":true}"""
        const val RUN_OLD = """{"session_id":"$OLD_ID","started_at":1794475800,"ended_at":1794475920,
                               "messages":4,"preview":"","active":false}"""
        const val TWO = """{"runs":[$RUN_NEW,$RUN_OLD]}"""
        const val NONE = """{"runs":[]}"""
    }
}
