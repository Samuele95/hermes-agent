package com.nousresearch.hermes.android.jobs

import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.protocol.CronJobRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The core's job rows as the app reads them, and when the app says they will not run (D39 F2, F5). */
class JobsTest {
    private fun job(state: JobState, id: String = "j-${state.name}") =
        Job(JobId(id), "n", "every 30m", "p", state, nextRunAt = null, last = null, repeat = null)

    private fun ticker(age: Int?, stale: Int = 200, error: String? = null) = TickerFacts(age, age, error, stale)

    private val running = GatewaySeen(GatewayStatus.RUNNING, pid = 42)
    private val stopped = GatewaySeen(GatewayStatus.STOPPED)

    @Test
    fun aRowBecomesAJobKnownByItsId() {
        val job = Job.of(CronJobRow(jobId = "a1b2c3d4e5f6", name = "Morning news", schedule = "30 9 * * *",
                                    promptPreview = "Summarise the news", state = "scheduled",
                                    nextRunAt = "2026-11-14T09:30:00+01:00"))

        assertEquals(JobId("a1b2c3d4e5f6"), job?.id)
        assertEquals("Morning news", job?.name)  // the name is only the label
        assertEquals("30 9 * * *", job?.schedule)
        assertEquals("Summarise the news", job?.prompt)
        assertEquals(JobState.SCHEDULED, job?.state)
        assertEquals("2026-11-14T09:30:00+01:00", job?.nextRunAt)
        // A job without a name is labelled by its id.
        assertEquals("a1b2c3d4e5f6", Job.of(CronJobRow(jobId = "a1b2c3d4e5f6"))?.name)
    }

    @Test
    fun aRowWithoutAnIdIsSkipped() {
        assertNull(Job.of(CronJobRow(jobId = "", name = "Morning news")))
        assertNull(Job.of(CronJobRow(jobId = "  ", name = "Morning news")))
    }

    @Test
    fun theCoresStateWordsMapAndAnyOtherIsUnknown() {
        fun stateOf(word: String?) = Job.of(CronJobRow(jobId = "j", state = word))?.state

        assertEquals(JobState.SCHEDULED, stateOf("scheduled"))
        assertEquals(JobState.PAUSED, stateOf("paused"))
        assertEquals(JobState.COMPLETED, stateOf("completed"))
        assertEquals(JobState.ERROR, stateOf("error"))
        assertEquals(JobState.UNKNOWN, stateOf("frozen"))
        assertEquals(JobState.UNKNOWN, stateOf(null))
    }

    @Test
    fun theLastRunKeepsTheCoresWordAndItsFirstError() {
        fun lastOf(row: CronJobRow) = Job.of(row)?.last

        val at = "2026-11-13T09:30:00+01:00"
        assertEquals(LastRun(at, RunOutcome.OK, "ok", null), lastOf(CronJobRow(jobId = "j", lastRunAt = at, lastStatus = "ok")))
        assertEquals(LastRun(at, RunOutcome.FAILED, "error", "boom"),
                     lastOf(CronJobRow(jobId = "j", lastRunAt = at, lastStatus = "error", lastError = "boom")))
        assertEquals(RunOutcome.DELIVERY_FAILED, lastOf(CronJobRow(jobId = "j", lastRunAt = at, lastStatus = "delivery_failed"))?.outcome)
        assertEquals(LastRun(at, RunOutcome.OTHER, "blocked_config", null),
                     lastOf(CronJobRow(jobId = "j", lastRunAt = at, lastStatus = "blocked_config")))

        // last_error wins over last_delivery_error, which wins over last_fire_error; a blank one is skipped.
        assertEquals("run", lastOf(CronJobRow(jobId = "j", lastError = "run", lastDeliveryError = "delivery", lastFireError = "fire"))?.error)
        assertEquals("delivery", lastOf(CronJobRow(jobId = "j", lastError = " ", lastDeliveryError = "delivery", lastFireError = "fire"))?.error)
        assertEquals("fire", lastOf(CronJobRow(jobId = "j", lastFireError = "fire"))?.error)
        // A job that could not even be fired has its error and no run.
        assertEquals(LastRun(null, null, null, "fire"), lastOf(CronJobRow(jobId = "j", lastFireError = "fire")))

        // A job that never ran has no last run.
        assertNull(lastOf(CronJobRow(jobId = "j")))
        assertNull(lastOf(CronJobRow(jobId = "j", lastError = "")))
    }

    @Test
    fun theRepeatIsTheCoresOwnDisplay() {
        fun repeatOf(repeat: kotlinx.serialization.json.JsonElement?) = Job.of(CronJobRow(jobId = "j", repeat = repeat))?.repeat

        assertEquals("forever", repeatOf(JsonPrimitive("forever")))
        assertEquals("2/5", repeatOf(JsonPrimitive("2/5")))
        assertNull(repeatOf(null))
        assertNull(repeatOf(JsonObject(mapOf("times" to JsonPrimitive(5)))))
        assertNull(repeatOf(JsonPrimitive(5)))
    }

    @Test
    fun timesAreTheCoresWallTimeToTheMinute() {
        assertEquals("2026-11-13 18:05", wallTime("2026-11-13T18:05:00+01:00"))
        assertEquals("2026-11-13 18:05", wallTime("2026-11-13T18:05:00.123456"))
        assertEquals("soon", wallTime("soon"))
    }

    @Test
    fun elapsedIsInMinutesHoursOrDays() {
        assertEquals("1 min", elapsed(1))
        assertEquals("59 min", elapsed(59))
        assertEquals("1 h", elapsed(60))
        assertEquals("23 h", elapsed(1439))
        assertEquals("1 d", elapsed(1440))
    }

    @Test
    fun noJobThatCouldFireNoWarning() {
        assertNull(jobsWarning(stopped, ticker(900), emptyList()))
        assertNull(jobsWarning(stopped, ticker(900), listOf(job(JobState.PAUSED), job(JobState.COMPLETED))))
        assertNull(jobsWarning(running, ticker(900), listOf(job(JobState.PAUSED), job(JobState.COMPLETED))))
    }

    @Test
    fun aGatewayNotRunningComesFirst() {
        val jobs = listOf(job(JobState.SCHEDULED))
        for (status in GatewayStatus.entries - GatewayStatus.RUNNING) {
            assertEquals(JobsWarning.GatewayOff(status), jobsWarning(GatewaySeen(status), ticker(900, error = "boom"), jobs))
            assertEquals(JobsWarning.GatewayOff(status), jobsWarning(GatewaySeen(status), ticker(30), jobs))
            assertEquals(JobsWarning.GatewayOff(status), jobsWarning(GatewaySeen(status), null, jobs))
        }
    }

    @Test
    fun aJobInErrorStillNeedsTheGateway() {
        assertEquals(JobsWarning.GatewayOff(GatewayStatus.STOPPED), jobsWarning(stopped, ticker(30), listOf(job(JobState.ERROR))))
        assertNotNull(jobsWarning(stopped, null, listOf(job(JobState.UNKNOWN))))
    }

    @Test
    fun aRunningGatewayWithAnOldHeartbeatIsNotTicking() {
        val jobs = listOf(job(JobState.PAUSED), job(JobState.SCHEDULED))

        assertEquals(JobsWarning.NotTicking(12, "boom"), jobsWarning(running, ticker(750, error = "boom"), jobs))
        assertEquals(JobsWarning.NotTicking(3, null), jobsWarning(running, ticker(201), jobs))
        assertNull(jobsWarning(running, ticker(200), jobs))  // the core's own rule: age <= threshold is fresh
        assertNull(jobsWarning(running, ticker(30), jobs))
        // The threshold is the core's, not the app's.
        assertEquals(JobsWarning.NotTicking(1, null), jobsWarning(running, ticker(31, stale = 30), jobs))
    }

    @Test
    fun anUnknownHeartbeatIsNoWarning() {
        val jobs = listOf(job(JobState.SCHEDULED))

        assertNull(jobsWarning(running, TickerFacts(heartbeatAgeS = null, successAgeS = 900, lastError = "boom", staleAfterS = 200), jobs))
        assertNull(jobsWarning(running, null, jobs))
    }
}
