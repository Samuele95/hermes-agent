package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.CorrectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // The chat's queue: one head at a time, nothing lost.

    private val a = Queued(0, "a")
    private val b = Queued(1, "b")
    private val c = Queued(2, "c")
    private val abc = QueueUi().added("a", 0).added("b", 1).added("c", 2)

    @Test
    fun aQueueKeepsItsOrderAndHandsOutOneHeadAtATime() {
        assertEquals(listOf(a, b, c), abc.items)

        val (first, rest) = abc.next()!!
        assertEquals(a to listOf(b, c), first to rest.items)
        val (second, rest2) = rest.next()!!
        assertEquals(b, second)
        val (third, rest3) = rest2.next()!!
        assertEquals(c, third)
        assertEquals(emptyList<Queued>(), rest3.items)
        assertNull(rest3.next())
    }

    @Test
    fun aPausedQueueHandsOutNothingAndAnEmptyOneIsNeverPaused() {
        assertNull(abc.copy(paused = true).next())

        assertFalse(QueueUi(listOf(a), paused = true).removed(a.id).paused)  // D5
        assertFalse(QueueUi().added("x", 0).paused)
        val (_, empty) = QueueUi(listOf(a)).next()!!
        assertEquals(QueueUi(), empty)
    }

    @Test
    fun theItemBeingEditedIsSkippedNotLost() {
        val (head, rest) = abc.copy(editing = a.id).next()!!

        assertEquals(b, head)
        assertEquals(listOf(a, c), rest.items)
        assertEquals(a.id, rest.editing)  // still being edited
        assertNull(QueueUi(listOf(a), editing = a.id).next())
    }

    @Test
    fun movedEditedRemovedActOnlyOnItemsStillListed() {
        val editingB = abc.copy(editing = b.id)
        assertEquals(editingB, editingB.moved(9))
        assertEquals(editingB, editingB.edited(9, "x"))
        assertEquals(editingB, editingB.removed(9))

        assertEquals(listOf(c, a, b), abc.moved(c.id).items)
        assertEquals(QueueUi(listOf(a, Queued(1, "B"), c)), editingB.edited(b.id, "B"))
        assertEquals(QueueUi(listOf(a, c)), editingB.removed(b.id))
        assertEquals(abc.copy(editing = null), abc.copy(editing = c.id).edited(c.id, "c"))
    }

    @Test
    fun aQueueReopenedOnAnIdleSessionIsPaused() {
        assertEquals(QueueUi(listOf(a, b), paused = true), QueueUi(listOf(a, b), editing = a.id).resumed(inTurn = false))  // D6
        assertEquals(QueueUi(listOf(a, b)), QueueUi(listOf(a, b), editing = a.id).resumed(inTurn = true))
        assertEquals(QueueUi(listOf(a), paused = true), QueueUi(listOf(a), paused = true).resumed(inTurn = true))
        assertEquals(QueueUi(), QueueUi().resumed(inTurn = false))
    }
}
