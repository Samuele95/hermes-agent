package com.nousresearch.hermes.android.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the gateway's own record says, checked against the process that wrote it. */
class GatewayRecordTest {
    private fun record(state: GatewayRecord.State, pid: Int = 42, reason: String? = null) = GatewayRecord(state, pid, reason)

    @Test
    fun aLiveGatewayIsWhatItsRecordSays() {
        assertEquals(GatewaySeen(GatewayStatus.STARTING, pid = 42), statusOf(record(GatewayRecord.State.STARTING), alivePid = 42))
        assertEquals(GatewaySeen(GatewayStatus.RUNNING, pid = 42), statusOf(record(GatewayRecord.State.RUNNING), alivePid = 42))
        assertEquals(GatewaySeen(GatewayStatus.RESTARTING, pid = 42), statusOf(record(GatewayRecord.State.RESTARTING), alivePid = 42))
    }

    @Test
    fun aLiveRecordWhoseProcessIsGoneDiedUnplanned() {
        // Killed, or ended by its own watchdog, and nothing brought it back (Samsung can hold the sticky restart, L21).
        for (state in listOf(GatewayRecord.State.STARTING, GatewayRecord.State.RUNNING, GatewayRecord.State.RESTARTING)) {
            assertEquals(GatewaySeen(GatewayStatus.DIED), statusOf(record(state, pid = 42), alivePid = null))
        }
        // A new :gateway process alive with the old one's record: it is coming up and has not written its own yet.
        assertEquals(GatewaySeen(GatewayStatus.STARTING), statusOf(record(GatewayRecord.State.RUNNING, pid = 42), alivePid = 99))
    }

    @Test
    fun aGoneGatewayEndedOnPurposeIsStopped() {
        // The user's Force stop, or a reboot since the record: a planned end, not a death.
        assertEquals(GatewaySeen(GatewayStatus.STOPPED), statusOf(record(GatewayRecord.State.RUNNING), alivePid = null) { true })

        // Asked only for a live record whose process is gone.
        val never: (GatewayRecord) -> Boolean = { throw AssertionError("planned end asked for $it") }
        assertEquals(GatewaySeen(GatewayStatus.STOPPED), statusOf(record(GatewayRecord.State.STOPPED), alivePid = null, never))
        assertEquals(GatewaySeen(GatewayStatus.FAILED, "no network"),
                     statusOf(record(GatewayRecord.State.FAILED, reason = "no network"), alivePid = null, never))
        assertEquals(GatewaySeen(GatewayStatus.RUNNING, pid = 42), statusOf(record(GatewayRecord.State.RUNNING, pid = 42), alivePid = 42, never))
    }

    @Test
    fun aForceStopOrAnotherBootIsAPlannedEnd() {
        val written = GatewayRecord(GatewayRecord.State.RUNNING, 42, boot = 7)

        assertTrue(endedOnPurpose(written, bootNow = 7, userRequested = true))
        assertTrue(endedOnPurpose(written, bootNow = 8, userRequested = false))
        assertFalse(endedOnPurpose(written, bootNow = 7, userRequested = false))
        // Android cannot tell: the end counts as unplanned.
        assertFalse(endedOnPurpose(GatewayRecord(GatewayRecord.State.RUNNING, 42), bootNow = 8, userRequested = false))
        assertFalse(endedOnPurpose(written, bootNow = null, userRequested = false))
    }

    @Test
    fun aFailureKeepsItsReasonAfterTheProcessEnded() {
        assertEquals(GatewaySeen(GatewayStatus.FAILED, "the gateway exited with code 78"),
                     statusOf(record(GatewayRecord.State.FAILED, reason = "the gateway exited with code 78"), alivePid = null))
    }

    @Test
    fun noRecordOrAPlannedStopIsStopped() {
        assertEquals(GatewaySeen(GatewayStatus.STOPPED), statusOf(null, alivePid = null))
        assertEquals(GatewaySeen(GatewayStatus.STOPPED), statusOf(record(GatewayRecord.State.STOPPED), alivePid = null))
    }

    @Test
    fun aStopOrFailureWhoseProcessStillLivesIsStopping() {
        // The record is written before the process ends: until it has, nothing new may start in it.
        assertEquals(GatewaySeen(GatewayStatus.STOPPING, pid = 42), statusOf(record(GatewayRecord.State.STOPPED, pid = 42), alivePid = 42))
        assertEquals(GatewaySeen(GatewayStatus.STOPPING, pid = 42),
                     statusOf(record(GatewayRecord.State.FAILED, pid = 42, reason = "x"), alivePid = 42))
    }

    @Test
    fun aGatewayProcessWithoutARecordYetIsStarting() {
        assertEquals(GatewaySeen(GatewayStatus.STARTING), statusOf(null, alivePid = 7))
    }

    @Test
    fun thePidIsTheLiveProcessOnlyWhenItWroteTheRecord() {
        assertEquals(42, statusOf(record(GatewayRecord.State.RUNNING, pid = 42), alivePid = 42).pid)
        // A new process with the old one's record: no pid yet, so F3's `writer_pid == seen.pid` cannot believe a
        // platform entry while the gateway is still STARTING.
        assertNull(statusOf(record(GatewayRecord.State.RUNNING, pid = 42), alivePid = 99).pid)
        assertEquals(GatewaySeen(GatewayStatus.FAILED, "no network", null),
                     statusOf(record(GatewayRecord.State.FAILED, pid = 42, reason = "no network"), alivePid = 99))
        assertNull(statusOf(record(GatewayRecord.State.RUNNING, pid = 42), alivePid = null).pid)
        assertNull(statusOf(null, alivePid = 7).pid)
    }

    @Test
    fun theRecordRoundTripsAsJson() {
        val written = GatewayRecord(GatewayRecord.State.FAILED, 7, "no network", boot = 7)

        assertEquals(written, GatewayRecord.parse(written.json()))
        assertEquals(null, GatewayRecord.parse("not json"))
        // A record written by an older build has no boot count.
        assertEquals(GatewayRecord(GatewayRecord.State.RUNNING, 3), GatewayRecord.parse("""{"state":"RUNNING","pid":3}"""))
    }
}
