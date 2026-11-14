package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A job row's actions (automation-03, -04, -05): pause, resume and remove through `cron.manage` by the job's id, run
 * now only through the app's `cron_trigger` (D39 F1), delete only after a confirmation of the same job, and after each
 * accepted action the core's list again (§2.4 invariants 1-4).
 */
class JobActionsTest {
    private var listed = ALL
    private var answers: Map<String, String> = mapOf("pause" to OK, "resume" to OK, "remove" to OK)
    private var onAct: () -> Unit = {}
    private var onList: () -> Unit = {}
    private var actFails: CoreError? = null
    private val core = FakeCoreGateway { method, params ->
        check(method == "cron.manage") { "unexpected $method" }
        when (val action = (params["action"] as? JsonPrimitive)?.content) {
            null -> { onList(); listed }
            "pause", "resume", "remove" -> {
                onAct()
                actFails?.let { throw it }
                answers.getValue(action)
            }
            else -> error("unexpected action $action")
        }
    }
    private var triggered = """{"next_run_at":"2026-11-13T10:00:00+01:00"}"""
    private var triggerFails = false
    private val bridgeCalls = mutableListOf<Pair<String, String>>()
    private val bridge = AppBridge { function, arg ->
        bridgeCalls += function to arg
        when (function) {
            "cron_scheduler" -> FRESH
            "cron_delivery_targets" -> """{"targets":[]}"""
            "cron_trigger" -> { if (triggerFails) throw IllegalStateException("the bridge broke"); triggered }
            else -> error("unexpected $function")
        }
    }
    private var seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
    private var clock = 1_000L
    private val control = JobsControl(core, bridge, seen = { seen }, continueInChat = {}, now = { clock })

    private val j1 = JobId("j1")
    private val j2 = JobId("j2")
    private val j3 = JobId("j3")
    private val ui get() = control.state.value!!
    private val listCalls get() = core.calls.filter { it.second["action"] == null }
    private fun acts(action: String) = core.calls.filter { it.second["action"] == JsonPrimitive(action) }
    private val actCalls get() = core.calls.filter { it.second["action"] != null }
    private val triggers get() = bridgeCalls.filter { it.first == "cron_trigger" }
    private fun shown(id: JobId) = ui.jobs.single { it.id == id }

    @Test
    fun pauseAndResumeSendTheJobsIdThenListTheCoresAnswer() = runTest {
        control.open()
        answers = answers + ("pause" to """{"success":true,"job":{"job_id":"j1","name":"Patched","state":"paused"}}""")
        onAct = { listed = list(SCHEDULED_PAUSED, PAUSED, COMPLETED) }

        control.pause(j1)

        val sent = acts("pause").single().second
        assertEquals(JsonPrimitive("j1"), sent["name"])
        assertEquals(2, listCalls.size)  // the open's, then exactly one after the pause
        assertEquals("Morning news", shown(j1).name)  // the list's, not the answer's "Patched"
        assertEquals(JobState.PAUSED, shown(j1).state)
        assertFalse(ui.busy)
        assertNull(ui.error)

        onAct = { listed = list(SCHEDULED, SCHEDULED_J2, COMPLETED) }
        control.resume(j2)

        assertEquals(JsonPrimitive("j2"), acts("resume").single().second["name"])
        assertEquals(3, listCalls.size)
        assertEquals(JobState.SCHEDULED, shown(j2).state)
        assertFalse(ui.busy)
        assertNull(ui.error)
    }

    @Test
    fun aRefusedResumeShowsTheCoresWordsAndListsNothing() = runTest {
        control.open()
        val words = "Cannot resume: one-shot time 2026-11-13T08:00:00 is in the past (grace window: 120s) and will never fire."
        answers = answers + ("resume" to """{"success":false,"error":"$words"}""")

        control.resume(j2)

        assertEquals(words, ui.error)
        assertFalse(ui.busy)
        assertEquals(1, listCalls.size)
        assertEquals(1, acts("resume").size)
    }

    @Test
    fun aThrownFailureIsForTheUser() = runTest {
        control.open()
        actFails = CoreError(5023, "cron.manage failed")

        control.pause(j1)

        assertEquals("cron.manage failed", ui.error)
        assertFalse(ui.busy)
        assertEquals(1, listCalls.size)
    }

    @Test
    fun noActionWhileAFormIsOpenOrOnAJobNotListed() = runTest {
        control.pause(j1)
        assertNull(control.state.value)
        assertTrue(core.calls.isEmpty())

        control.open()
        control.newJob()
        control.pause(j1)
        control.resume(j2)
        control.runNow(j1)
        control.askDelete(j1)
        assertTrue(actCalls.isEmpty())
        assertTrue(triggers.isEmpty())
        assertNull(ui.confirmDelete)

        control.cancelForm()
        control.pause(JobId("j9"))
        control.resume(JobId("j9"))
        assertTrue(actCalls.isEmpty())
        assertNull(ui.error)
    }

    @Test
    fun aSecondActionWhileOneRunsIsNotSent() = runTest {
        control.open()
        onAct = { runBlocking { control.pause(j1) } }

        control.pause(j1)

        assertEquals(1, acts("pause").size)
        assertFalse(ui.busy)
    }

    @Test
    fun aReListWhileAnActionRunsAdmitsNoSecond() = runTest {
        control.open()
        onAct = {
            onAct = {}
            runBlocking {
                control.refresh()  // the re-list follow() makes on cron.changed, while the pause is on its way
                control.pause(j1)  // the same tap again
            }
        }

        control.pause(j1)

        assertEquals(1, acts("pause").size)
        assertFalse(ui.busy)
    }

    @Test
    fun deleteRunsOnlyAfterAConfirmationOfTheSameJob() = runTest {
        control.open()

        control.askDelete(j1)
        assertEquals(j1, ui.confirmDelete)
        assertTrue(acts("remove").isEmpty())

        control.askDelete(null)
        assertNull(ui.confirmDelete)
        control.confirmDelete()
        assertTrue(acts("remove").isEmpty())
        assertEquals(1, listCalls.size)

        control.askDelete(j1)
        onAct = { listed = list(PAUSED, COMPLETED) }
        control.confirmDelete()

        assertEquals(JsonPrimitive("j1"), acts("remove").single().second["name"])
        assertEquals(2, listCalls.size)
        assertNull(ui.confirmDelete)
        assertEquals(listOf(j2, j3), ui.jobs.map { it.id })
        assertFalse(ui.busy)
        assertNull(ui.error)
    }

    @Test
    fun askingToDeleteAJobNotListedAsksNothing() = runTest {
        control.open()

        control.askDelete(JobId("j9"))

        assertNull(ui.confirmDelete)
        control.confirmDelete()
        assertTrue(acts("remove").isEmpty())
    }

    @Test
    fun aConfirmationLastsOnlyWhileItsJobIsListed() = runTest {
        control.open()
        control.askDelete(j1)

        control.refresh()
        assertEquals(j1, ui.confirmDelete)

        listed = list(PAUSED, COMPLETED)
        control.refresh()
        assertNull(ui.confirmDelete)
        control.confirmDelete()
        assertTrue(acts("remove").isEmpty())
    }

    @Test
    fun aSecondConfirmWhileOneRunsIsNotSent() = runTest {
        control.open()
        control.askDelete(j1)
        onAct = { runBlocking { control.confirmDelete() } }

        control.confirmDelete()

        assertEquals(1, acts("remove").size)
        assertFalse(ui.busy)

        // The core refused nothing and still lists j1: the confirmation was spent on that one remove.
        onAct = {}
        assertNull(ui.confirmDelete)
        control.confirmDelete()
        assertEquals(1, acts("remove").size)
    }

    @Test
    fun aRefusedDeleteKeepsTheJobAndTheCoresWords() = runTest {
        control.open()
        answers = answers + ("remove" to """{"success":false,"error":"Failed to remove job 'j1'"}""")
        control.askDelete(j1)

        control.confirmDelete()

        assertEquals("Failed to remove job 'j1'", ui.error)
        assertEquals("Morning news", shown(j1).name)
        assertNull(ui.confirmDelete)
        assertFalse(ui.busy)
        assertEquals(1, listCalls.size)
    }

    @Test
    fun runNowMakesTheJobDueAndMarksItQueued() = runTest {
        control.open()
        assertTrue(ui.offersRunNow(shown(j1)))

        control.runNow(j1)

        assertEquals("""{"job_id":"j1"}""", triggers.single().second)
        assertTrue(actCalls.isEmpty())
        assertEquals(3, listCalls.size)  // the open's, the fresh read, the re-list after (K7)
        assertEquals(mapOf(j1 to RunMark("2026-11-13T09:30:00+01:00", 1_000)), ui.queued)
        assertFalse(ui.offersRunNow(shown(j1)))
        assertFalse(ui.busy)
        assertNull(ui.error)
    }

    @Test
    fun runNowIsOfferedOnlyWhileTheGatewayRunsAndTheJobIsScheduled() = runTest {
        for (status in listOf(GatewayStatus.STOPPED, GatewayStatus.STARTING, GatewayStatus.RESTARTING,
                              GatewayStatus.STOPPING, GatewayStatus.FAILED, GatewayStatus.DIED)) {
            seen = GatewaySeen(status)
            control.open()
            assertFalse(status.name, ui.gatewayRunning)
            assertFalse(status.name, ui.offersRunNow(shown(j1)))

            control.runNow(j1)

            assertTrue(status.name, triggers.isEmpty())
            assertTrue(status.name, ui.queued.isEmpty())
        }

        seen = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
        control.refresh()

        assertTrue(ui.gatewayRunning)
        assertTrue(ui.offersRunNow(shown(j1)))
        assertFalse(ui.offersRunNow(shown(j2)))
        assertFalse(ui.offersRunNow(shown(j3)))
        control.runNow(j2)
        control.runNow(j3)
        assertTrue(triggers.isEmpty())
        assertTrue(ui.queued.isEmpty())
    }

    @Test
    fun aTapAfterTheGatewayStoppedSendsNothingAndDropsTheOffer() = runTest {
        control.open()
        assertTrue(ui.gatewayRunning)
        seen = GatewaySeen(GatewayStatus.STOPPED)

        control.runNow(j1)

        assertTrue(triggers.isEmpty())
        assertFalse(ui.gatewayRunning)
        assertFalse(ui.offersRunNow(shown(j1)))
        assertEquals(1, listCalls.size)
        assertNull(ui.error)
        assertFalse(ui.busy)
    }

    @Test
    fun theQueuedMarkClearsWhenTheCoresLastRunMoves() = runTest {
        control.open()
        control.runNow(j1)
        control.refresh()
        assertEquals(setOf(j1), ui.queued.keys)

        listed = list(RAN, PAUSED, COMPLETED)
        control.refresh()

        assertTrue(ui.queued.isEmpty())
        assertTrue(ui.offersRunNow(shown(j1)))
    }

    @Test
    fun theQueuedMarkClearsAfterTwoMinutes() = runTest {
        control.open()
        control.runNow(j1)

        clock = 120_999
        control.refresh()
        assertEquals(setOf(j1), ui.queued.keys)

        clock = 121_000
        control.refresh()
        assertTrue(ui.queued.isEmpty())
    }

    @Test
    fun aMarkGoesWithItsJobOrItsSchedule() = runTest {
        control.open()
        control.runNow(j1)
        listed = list(SCHEDULED_PAUSED, PAUSED, COMPLETED)
        control.refresh()
        assertTrue(ui.queued.isEmpty())

        listed = ALL
        control.refresh()
        control.runNow(j1)
        assertEquals(setOf(j1), ui.queued.keys)
        listed = list(PAUSED, COMPLETED)
        control.refresh()
        assertTrue(ui.queued.isEmpty())
    }

    @Test
    fun aRefusedRunNowShowsTheCoresWordsAndMarksNothing() = runTest {
        control.open()
        val words = "Cannot run: job 'Morning news' is completed (terminal). Create a new job instead."
        triggered = """{"error":"$words"}"""

        control.runNow(j1)

        assertEquals(words, ui.error)
        assertTrue(ui.queued.isEmpty())
        assertEquals(2, listCalls.size)  // the open's, then the tap's fresh read; none after the core's refusal (V7)
        assertFalse(ui.busy)

        triggerFails = true
        control.runNow(j1)

        assertEquals("Something went wrong (IllegalStateException).", ui.error)
        assertTrue(ui.queued.isEmpty())
        assertEquals(3, listCalls.size)  // one fresh read per tap (K7)
        assertFalse(ui.busy)
    }

    @Test
    fun aJobPausedSinceTheLastListIsRefusedAndNotTriggered() = runTest {
        control.open()
        assertTrue(ui.offersRunNow(shown(j1)))
        listed = list(SCHEDULED_PAUSED, PAUSED, COMPLETED)  // paused elsewhere: the page has not listed since

        control.runNow(j1)

        assertTrue(triggers.isEmpty())                       // not resumed and fired by trigger_job
        assertEquals("The job is no longer scheduled, so it was not run.", ui.error)
        assertEquals(JobState.PAUSED, shown(j1).state)       // the fresh list is shown
        assertFalse(ui.offersRunNow(shown(j1)))
        assertTrue(ui.queued.isEmpty())
        assertEquals(2, listCalls.size)                      // the open's, then the fresh read; none after a refusal
        assertTrue(actCalls.isEmpty())
        assertFalse(ui.busy)
    }

    @Test
    fun aJobDeletedSinceTheLastListIsRefusedInTheAppsWords() = runTest {
        control.open()
        listed = list(PAUSED, COMPLETED)  // deleted elsewhere: the page has not listed since

        control.runNow(j1)

        assertTrue(triggers.isEmpty())
        assertEquals("That job no longer exists.", ui.error)
        assertTrue(ui.jobs.none { it.id == j1 })
        assertEquals(2, listCalls.size)
        assertFalse(ui.busy)
    }

    @Test
    fun aFreshListThatFailsSendsNothing() = runTest {
        control.open()
        listed = """{"success":false,"error":"cron store unreadable"}"""

        control.runNow(j1)

        assertTrue(triggers.isEmpty())
        assertEquals("cron store unreadable", ui.error)      // the list's words, told by `telling`
        assertEquals(JobState.SCHEDULED, shown(j1).state)    // the jobs as last listed
        assertTrue(ui.queued.isEmpty())
        assertEquals(2, listCalls.size)
        assertFalse(ui.busy)
    }

    @Test
    fun aGatewaySeenStoppedByTheFreshListSendsNothingAndSaysNothing() = runTest {
        control.open()
        onList = { onList = {}; seen = GatewaySeen(GatewayStatus.STOPPED) }  // stopped while the fresh read is on its way

        control.runNow(j1)

        assertTrue(triggers.isEmpty())
        assertNull(ui.error)                                 // V10: the offer drops without words
        assertFalse(ui.gatewayRunning)
        assertFalse(ui.offersRunNow(shown(j1)))
        assertEquals(2, listCalls.size)
        assertFalse(ui.busy)
    }

    @Test
    fun theQueuedMarkIsTakenFromTheFreshList() = runTest {
        control.open()
        listed = list(RAN, PAUSED, COMPLETED)                // the gateway ran j1 since the last list

        control.runNow(j1)

        assertEquals("""{"job_id":"j1"}""", triggers.single().second)
        assertEquals(mapOf(j1 to RunMark("2026-11-13T10:00:30+01:00", 1_000)), ui.queued)  // still queued after the re-list
        assertFalse(ui.busy)
    }

    @Test
    fun stillQueuedIsAPureRule() {
        val job = Job(j1, "Morning news", "every 60m", "", JobState.SCHEDULED, null,
                      LastRun("2026-11-13T09:30:00+01:00", RunOutcome.OK, "ok", null), null)
        val marks = mapOf(j1 to RunMark("2026-11-13T09:30:00+01:00", 0))

        assertEquals(marks, stillQueued(marks, listOf(job), 119_999))
        assertEquals(emptyMap<JobId, RunMark>(), stillQueued(marks, listOf(job), 120_000))
        assertEquals(emptyMap<JobId, RunMark>(),
                     stillQueued(marks, listOf(job.copy(last = job.last!!.copy(at = "2026-11-13T10:00:30+01:00"))), 1))
        val never = mapOf(j1 to RunMark(null, 0))
        assertEquals(never, stillQueued(never, listOf(job.copy(last = null)), 1))
        assertEquals(emptyMap<JobId, RunMark>(), stillQueued(never, listOf(job), 1))
        assertEquals(emptyMap<JobId, RunMark>(), stillQueued(marks, emptyList(), 1))
        assertEquals(emptyMap<JobId, RunMark>(), stillQueued(marks, listOf(job.copy(state = JobState.ERROR)), 1))
    }

    private companion object {
        const val SCHEDULED = """{"job_id":"j1","name":"Morning news","schedule":"30 9 * * *","state":"scheduled",
                                 "next_run_at":"2026-11-14T09:30:00+01:00","last_run_at":"2026-11-13T09:30:00+01:00",
                                 "last_status":"ok","prompt_preview":"Summarise the news","repeat":"forever"}"""
        const val SCHEDULED_PAUSED = """{"job_id":"j1","name":"Morning news","schedule":"30 9 * * *","state":"paused",
                                        "last_run_at":"2026-11-13T09:30:00+01:00","last_status":"ok",
                                        "prompt_preview":"Summarise the news","repeat":"forever"}"""
        const val RAN = """{"job_id":"j1","name":"Morning news","schedule":"30 9 * * *","state":"scheduled",
                           "next_run_at":"2026-11-14T09:30:00+01:00","last_run_at":"2026-11-13T10:00:30+01:00",
                           "last_status":"ok","prompt_preview":"Summarise the news","repeat":"forever"}"""
        const val PAUSED = """{"job_id":"j2","name":"Evening digest","schedule":"every 1440m","state":"paused",
                              "prompt_preview":"Digest the day","repeat":"forever"}"""
        const val SCHEDULED_J2 = """{"job_id":"j2","name":"Evening digest","schedule":"every 1440m","state":"scheduled",
                                    "next_run_at":"2026-11-14T18:00:00+01:00","prompt_preview":"Digest the day",
                                    "repeat":"forever"}"""
        const val COMPLETED = """{"job_id":"j3","name":"Reminder","schedule":"once at 2026-11-12 08:00","state":"completed",
                                 "last_run_at":"2026-11-12T08:00:10+01:00","last_status":"ok",
                                 "prompt_preview":"Remind me","repeat":"1/1"}"""
        const val OK = """{"success":true}"""
        const val ALL = """{"success":true,"count":3,"jobs":[$SCHEDULED,$PAUSED,$COMPLETED]}"""
        const val FRESH = """{"heartbeat_age_s":30,"success_age_s":30,"last_error":null,"stale_after_s":200}"""

        fun list(vararg rows: String) = """{"success":true,"count":${rows.size},"jobs":[${rows.joinToString(",")}]}"""
    }
}
