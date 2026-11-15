package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.CorrectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

// The core's busy mode and its answers, mapped once.
class OutboxTest {
    @Test
    fun theCoresThreeWordsAreItsModesAndAnythingElseInterrupts() {
        assertEquals(listOf(BusyMode.INTERRUPT, BusyMode.STEER, BusyMode.QUEUE), listOf("interrupt", "steer", "queue").map(BusyMode::of))

        // The core's own fallback (server.py:382-384): a word it does not know, or none, interrupts.
        assertEquals(listOf(BusyMode.INTERRUPT, BusyMode.INTERRUPT, BusyMode.INTERRUPT), listOf(null, "", "hard").map(BusyMode::of))
    }

    @Test
    fun aQueuedAnswerMeansInjectedFromASteerAndQueuedFromARedirect() {
        assertEquals(Correction.STEERED, Correction.of(BusyMode.STEER, CorrectionStatus.QUEUED))
        assertEquals(Correction.QUEUED_BY_CORE, Correction.of(BusyMode.INTERRUPT, CorrectionStatus.QUEUED))
    }

    @Test
    fun everyModeMapsEveryStatus() {
        val table = BusyMode.entries.associateWith { mode -> CorrectionStatus.entries.associateWith { Correction.of(mode, it) } }

        assertEquals(mapOf(
            BusyMode.INTERRUPT to mapOf(CorrectionStatus.QUEUED to Correction.QUEUED_BY_CORE, CorrectionStatus.REDIRECTED to Correction.REDIRECTED,
                                        CorrectionStatus.REJECTED to Correction.REFUSED),
            BusyMode.STEER to mapOf(CorrectionStatus.QUEUED to Correction.STEERED, CorrectionStatus.REDIRECTED to Correction.REDIRECTED,
                                    CorrectionStatus.REJECTED to Correction.REFUSED),
            BusyMode.QUEUE to mapOf(CorrectionStatus.QUEUED to Correction.QUEUED_BY_CORE, CorrectionStatus.REDIRECTED to Correction.REDIRECTED,
                                    CorrectionStatus.REJECTED to Correction.REFUSED),
        ), table)
    }
}
