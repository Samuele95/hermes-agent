package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The Scheduled jobs page's controller: the core's full list, and the app's own judgement of whether jobs will run. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a launched collector runs until it suspends
class JobsControlTest {
    private var listed = TWO_JOBS
    private var onList: () -> Unit = {}
    private val core = FakeCoreGateway { method, _ ->
        check(method == "cron.manage") { "unexpected $method" }
        onList()
        listed
    }
    private var ticker = FRESH
    private var tickerFails = false
    private val bridge = AppBridge { function, _ ->
        check(function == "cron_scheduler") { "unexpected $function" }
        if (tickerFails) throw IllegalStateException("the bridge broke")
        ticker
    }
    private var seen = GatewaySeen(GatewayStatus.STOPPED)
    private val control = JobsControl(core, bridge, seen = { seen }, continueInChat = {})

    private val listCalls get() = core.calls.filter { it.first == "cron.manage" }

    @Test
    fun openingListsEveryJobPausedOnesToo() = runTest {
        control.open()

        val sent = listCalls.single().second
        assertEquals(JsonPrimitive(true), sent["include_disabled"])
        val ui = control.state.value!!
        assertEquals(listOf(JobId("j1"), JobId("j2")), ui.jobs.map { it.id })
        assertEquals(listOf(JobState.SCHEDULED, JobState.PAUSED), ui.jobs.map { it.state })
        assertFalse(ui.busy)
        assertNull(ui.error)
        assertEquals(JobsWarning.GatewayOff(GatewayStatus.STOPPED), ui.warning)
    }

    @Test
    fun theWarningIsTheAppsOwnNotTheCores() = runTest {
        listed = """{"success":true,"count":1,"gateway_running":true,"jobs":[$SCHEDULED]}"""

        control.open()

        assertEquals(JobsWarning.GatewayOff(GatewayStatus.STOPPED), control.state.value?.warning)

        listed = """{"success":true,"count":1,"gateway_running":false,
                     "warning":"The Hermes gateway is not running, so this job will NOT fire until the gateway is started.",
                     "jobs":[$SCHEDULED]}"""
        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        ticker = FRESH

        control.refresh()

        assertNull(control.state.value?.warning)
        assertEquals(listOf(JobId("j1")), control.state.value?.jobs?.map { it.id })
    }

    @Test
    fun aStaleTickerUnderARunningGatewayIsNotTicking() = runTest {
        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        ticker = """{"heartbeat_age_s":900,"success_age_s":900,"last_error":"[REDACTED] timeout","stale_after_s":200}"""

        control.open()

        assertEquals(JobsWarning.NotTicking(15, "[REDACTED] timeout"), control.state.value?.warning)
    }

    @Test
    fun anUnreadableTickerIsNoWarningAndNoError() = runTest {
        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        ticker = """{"error":"x"}"""

        control.open()

        control.state.value!!.let { ui ->
            assertNull(ui.warning)
            assertNull(ui.error)
            assertEquals(2, ui.jobs.size)
        }

        tickerFails = true

        control.refresh()

        control.state.value!!.let { ui ->
            assertNull(ui.warning)
            assertNull(ui.error)
            assertEquals(2, ui.jobs.size)
        }
    }

    @Test
    fun aRefusalIsShownInTheCoresWordsAndTheListStays() = runTest {
        control.open()
        listed = """{"success":false,"error":"cron store unreadable"}"""

        control.refresh()

        val ui = control.state.value!!
        assertEquals("cron store unreadable", ui.error)
        assertEquals(listOf(JobId("j1"), JobId("j2")), ui.jobs.map { it.id })
        assertFalse(ui.busy)
    }

    @Test
    fun aFailedCallIsNamedByTheFailureRule() = runTest {
        onList = { throw CoreError(4016, "unknown cron action: list") }

        control.open()

        val ui = control.state.value!!
        assertEquals("unknown cron action: list", ui.error)
        assertFalse(ui.busy)
        assertEquals(emptyList<Job>(), ui.jobs)
    }

    @Test
    fun cronChangedListsAgainAndOtherSignalsDoNot() = runTest(UnconfinedTestDispatcher()) {
        control.open()
        backgroundScope.launch { control.follow() }

        core.emit("platforms.changed", "")

        assertEquals(1, listCalls.size)

        listed = """{"success":true,"count":3,"jobs":[$SCHEDULED,$PAUSED,{"job_id":"j3","name":"New","state":"scheduled"}]}"""
        core.emit("cron.changed", "")

        assertEquals(2, listCalls.size)
        assertEquals(listOf(JobId("j1"), JobId("j2"), JobId("j3")), control.state.value?.jobs?.map { it.id })
    }

    @Test
    fun theGatewayIsSeenAgainAtEachRefresh() = runTest {
        control.open()
        assertEquals(JobsWarning.GatewayOff(GatewayStatus.STOPPED), control.state.value?.warning)

        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        control.refresh()

        assertNull(control.state.value?.warning)
    }

    @Test
    fun aClosedPageAsksNothing() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.launch { control.follow() }
        control.open()
        control.close()

        control.refresh()
        core.emit("cron.changed", "")

        assertEquals(1, listCalls.size)
        assertNull(control.state.value)
    }

    @Test
    fun aPageClosedWhileListingStaysClosed() = runTest {
        onList = { control.close() }

        control.open()

        assertNull(control.state.value)
    }

    @Test
    fun theGatewayComingUpListsOnceAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val changes = MutableStateFlow(seen)  // STOPPED, as the open's list saw it
        control.open()
        backgroundScope.launch { control.followGateway(changes) }
        assertEquals(1, listCalls.size)

        changes.value = GatewaySeen(GatewayStatus.STARTING)
        assertEquals(1, listCalls.size)  // not up yet: nothing

        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        changes.value = seen
        assertEquals(2, listCalls.size)  // at once, with no 30 s tick
        assertNull(control.state.value?.warning)  // FRESH ticker: the GatewayOff banner is gone

        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, listCalls.size)  // a fresh heartbeat is not re-checked
    }

    @Test
    fun aStaleHeartbeatAtRunningIsListedAgainSecondsLater() = runTest(UnconfinedTestDispatcher()) {
        ticker = STALE
        val changes = MutableStateFlow(seen)
        control.open()
        backgroundScope.launch { control.followGateway(changes) }

        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        changes.value = seen
        assertEquals(2, listCalls.size)
        assertEquals(JobsWarning.NotTicking(3, null), control.state.value?.warning)

        ticker = FRESH  // the new ticker's first stamp lands
        advanceTimeBy(RECHECK_AFTER_MS - 1); runCurrent()
        assertEquals(2, listCalls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, listCalls.size)
        assertNull(control.state.value?.warning)

        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, listCalls.size)
    }

    @Test
    fun aHeartbeatStillStaleIsCheckedTwiceThenLeftToTheCadence() = runTest(UnconfinedTestDispatcher()) {
        ticker = STALE
        val changes = MutableStateFlow(seen)
        control.open()
        backgroundScope.launch { control.followGateway(changes) }

        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        changes.value = seen
        assertEquals(2, listCalls.size)
        advanceTimeBy(RECHECK_AFTER_MS); runCurrent()
        assertEquals(3, listCalls.size)
        advanceTimeBy(RECHECK_AFTER_MS); runCurrent()
        assertEquals(4, listCalls.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, listCalls.size)
        assertEquals(JobsWarning.NotTicking(3, null), control.state.value?.warning)
    }

    @Test
    fun aGatewayTheLastListSawRunningListsNothingButARestartDoes() = runTest(UnconfinedTestDispatcher()) {
        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        val changes = MutableStateFlow(seen)
        control.open()
        backgroundScope.launch { control.followGateway(changes) }
        assertEquals(1, listCalls.size)  // the open's list already saw this gateway

        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 43)
        changes.value = seen
        assertEquals(2, listCalls.size)  // a new process is a new start
    }

    @Test
    fun aGatewayGoingDownCancelsTheRecheck() = runTest(UnconfinedTestDispatcher()) {
        ticker = STALE
        val changes = MutableStateFlow(seen)
        control.open()
        backgroundScope.launch { control.followGateway(changes) }

        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        changes.value = seen
        assertEquals(2, listCalls.size)

        advanceTimeBy(2_000)
        seen = GatewaySeen(GatewayStatus.STOPPING)
        changes.value = seen
        assertEquals(2, listCalls.size)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, listCalls.size)  // the pending re-check was cancelled
    }

    @Test
    fun aClosedPageListsNothingWhenTheGatewayComesUp() = runTest(UnconfinedTestDispatcher()) {
        val changes = MutableStateFlow(seen)
        backgroundScope.launch { control.followGateway(changes) }
        control.open()
        control.close()

        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        changes.value = seen

        assertEquals(1, listCalls.size)
        assertNull(control.state.value)
    }

    private companion object {
        const val SCHEDULED = """{"job_id":"j1","name":"Morning news","schedule":"30 9 * * *","state":"scheduled",
                                 "next_run_at":"2026-11-14T09:30:00+01:00","repeat":"forever"}"""
        const val PAUSED = """{"job_id":"j2","name":"Weekly report","schedule":"every 10080m","state":"paused","enabled":false}"""
        const val TWO_JOBS = """{"success":true,"count":2,"jobs":[$SCHEDULED,$PAUSED]}"""
        const val FRESH = """{"heartbeat_age_s":30,"success_age_s":30,"last_error":null,"stale_after_s":200}"""
        const val STALE = """{"heartbeat_age_s":207,"success_age_s":207,"last_error":null,"stale_after_s":200}"""
    }
}
