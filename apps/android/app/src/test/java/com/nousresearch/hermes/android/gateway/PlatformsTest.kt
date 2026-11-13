package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.bridge.contract.PlatformEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bot's live state: the core's own entry, believed only from the running gateway's pid (D39 F3, bet 7). */
class PlatformsTest {
    private fun entry(state: String?, pid: Int? = 42, message: String? = null) =
        PlatformEntry(state, message, pid, "2026-11-13T15:48:13.243611+00:00")

    private val running = GatewaySeen(GatewayStatus.RUNNING, pid = 42)

    private fun live(seen: GatewaySeen, entry: PlatformEntry?, enabled: Boolean = true, tokenHeld: Boolean = true) =
        telegramLive(enabled, tokenHeld, seen, entry)

    @Test
    fun eachCoreWordIsItsState() {
        assertEquals(PlatformState.CONNECTED, PlatformState.of("connected"))
        assertEquals(PlatformState.CONNECTING, PlatformState.of("connecting"))
        assertEquals(PlatformState.RETRYING, PlatformState.of("retrying"))
        assertEquals(PlatformState.FATAL, PlatformState.of("fatal"))
        assertEquals(PlatformState.PAUSED, PlatformState.of("paused"))
        assertEquals(PlatformState.DISABLED, PlatformState.of("disabled"))
    }

    @Test
    fun anUnknownWordIsUnknownNeverADerivedState() {
        for (word in listOf("disconnected", "pending_restart", "not_configured", "CONNECTED", null)) {
            assertEquals("word $word", PlatformState.UNKNOWN, PlatformState.of(word))
        }
    }

    @Test
    fun theRunningGatewaysOwnEntryIsBelievedWithItsWords() {
        assertEquals(PlatformLive(PlatformState.FATAL, "Conflict: terminated by other getUpdates request",
                                  "2026-11-13T15:48:13.243611+00:00"),
                     live(running, entry("fatal", 42, "Conflict: terminated by other getUpdates request")))
        assertEquals(PlatformState.CONNECTED, live(running, entry("connected")).state)
    }

    @Test
    fun aStaleWritersEntryReadsAsPendingRestart() {
        assertEquals(PlatformLive(PlatformState.PENDING_RESTART), live(running, entry("connected", 41, "old words")))
        assertEquals(PlatformLive(PlatformState.PENDING_RESTART), live(running, entry("connected", null)))
        assertEquals(PlatformLive(PlatformState.PENDING_RESTART), live(running, null))
    }

    @Test
    fun anEntryIsNeverBelievedWithoutTheGatewaysPid() {
        assertEquals(PlatformLive(PlatformState.PENDING_RESTART),
                     live(GatewaySeen(GatewayStatus.RUNNING, pid = null), entry("connected", null)))
    }

    @Test
    fun aGatewayNotRunningIsGatewayOffWhateverTheEntrySays() {
        val withPid = setOf(GatewayStatus.STARTING, GatewayStatus.RESTARTING, GatewayStatus.STOPPING)
        for (status in GatewayStatus.entries - GatewayStatus.RUNNING) {
            val seen = GatewaySeen(status, pid = if (status in withPid) 42 else null)
            assertEquals("status $status", PlatformLive(PlatformState.GATEWAY_OFF), live(seen, entry("connected", 42)))
        }
    }

    @Test
    fun disabledBeatsConnected() {
        assertEquals(PlatformLive(PlatformState.DISABLED), live(running, entry("connected"), enabled = false))
        assertEquals(PlatformLive(PlatformState.DISABLED), live(running, entry("connected"), enabled = false, tokenHeld = false))
    }

    @Test
    fun withoutATokenTheBotNeedsSetup() {
        assertEquals(PlatformLive(PlatformState.NOT_CONFIGURED), live(running, entry("connected"), enabled = true, tokenHeld = false))
    }
}
