package com.nousresearch.hermes.android.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the gateway's own record says, checked against the process that wrote it. */
class GatewayRecordTest {
    private fun record(state: GatewayRecord.State, pid: Int = 42, reason: String? = null) = GatewayRecord(state, pid, reason)

    @Test
    fun aLiveGatewayIsWhatItsRecordSays() {
        assertEquals(GatewayStatus.STARTING to null, statusOf(record(GatewayRecord.State.STARTING), alivePid = 42))
        assertEquals(GatewayStatus.RUNNING to null, statusOf(record(GatewayRecord.State.RUNNING), alivePid = 42))
        assertEquals(GatewayStatus.RESTARTING to null, statusOf(record(GatewayRecord.State.RESTARTING), alivePid = 42))
    }

    @Test
    fun aRecordFromAProcessThatIsGoneIsStopped() {
        // Killed, or ended by its own watchdog: Android restarts it, and the new process writes a new record.
        assertEquals(GatewayStatus.STOPPED to null, statusOf(record(GatewayRecord.State.RUNNING, pid = 42), alivePid = null))
        // A new :gateway process alive with the old one's record: it is coming up and has not written its own yet.
        assertEquals(GatewayStatus.STARTING to null, statusOf(record(GatewayRecord.State.RUNNING, pid = 42), alivePid = 99))
    }

    @Test
    fun aFailureKeepsItsReasonAfterTheProcessEnded() {
        assertEquals(GatewayStatus.FAILED to "the gateway exited with code 78",
                     statusOf(record(GatewayRecord.State.FAILED, reason = "the gateway exited with code 78"), alivePid = null))
    }

    @Test
    fun noRecordOrAPlannedStopIsStopped() {
        assertEquals(GatewayStatus.STOPPED to null, statusOf(null, alivePid = null))
        assertEquals(GatewayStatus.STOPPED to null, statusOf(record(GatewayRecord.State.STOPPED), alivePid = null))
    }

    @Test
    fun aStopOrFailureWhoseProcessStillLivesIsStopping() {
        // The record is written before the process ends: until it has, nothing new may start in it.
        assertEquals(GatewayStatus.STOPPING to null, statusOf(record(GatewayRecord.State.STOPPED, pid = 42), alivePid = 42))
        assertEquals(GatewayStatus.STOPPING to null, statusOf(record(GatewayRecord.State.FAILED, pid = 42, reason = "x"), alivePid = 42))
    }

    @Test
    fun aGatewayProcessWithoutARecordYetIsStarting() {
        assertEquals(GatewayStatus.STARTING to null, statusOf(null, alivePid = 7))
    }

    @Test
    fun theRecordRoundTripsAsJson() {
        val written = record(GatewayRecord.State.FAILED, pid = 7, reason = "no network")

        assertEquals(written, GatewayRecord.parse(written.json()))
        assertEquals(null, GatewayRecord.parse("not json"))
    }
}
