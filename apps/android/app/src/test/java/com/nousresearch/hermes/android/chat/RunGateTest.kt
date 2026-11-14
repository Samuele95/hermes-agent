package com.nousresearch.hermes.android.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which sessions are a job's run, and which ownership refuses a send (/architect 20261113T220113Z #4): the chat's gate. */
class RunGateTest {
    @Test
    fun aJobsRunIsNamedTheCoresWay() {
        assertTrue(isCronRun(StoredSessionId("cron_ab12cd34ef56_20261113_100000")))
        assertTrue(isCronRun(StoredSessionId("cron_a_b_20261113_100000")))  // a job id holding `_`
        assertTrue(isCronRun(StoredSessionId("  cron_ab12cd34ef56_20261113_100000 ")))
    }

    @Test
    fun nothingElseIsARun() {
        for (id in listOf("cron_ab12_x", "db-old", "cron__20261113_100000", "xcron_a_20261113_100000")) {
            assertFalse(id, isCronRun(StoredSessionId(id)))
        }
    }

    @Test
    fun onlyARunTheSchedulerStillOwnsRefusesASend() {
        assertTrue(refusesSend(true))
        assertFalse("ended, or no live owner", refusesSend(false))
        assertFalse("the core cannot say", refusesSend(null))
    }
}
