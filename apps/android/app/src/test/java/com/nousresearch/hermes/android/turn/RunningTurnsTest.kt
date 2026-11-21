package com.nousresearch.hermes.android.turn

import org.junit.Assert.assertEquals
import org.junit.Test

/** The turn service runs from the first running turn to the end of the last one. */
class RunningTurnsTest {
    private val turns = RunningTurns()

    @Test
    fun theFirstTurnStartsTheServiceAndLaterOnesDoNot() {
        assertEquals(listOf(true, false), listOf(turns.begin("a"), turns.begin("b")))
    }

    @Test
    fun theServiceStopsOnlyWhenTheLastTurnEnds() {
        turns.begin("a")
        turns.begin("b")

        assertEquals(listOf(false, true), listOf(turns.end("a"), turns.end("b")))
        assertEquals(true, turns.isIdle)
    }

    @Test
    fun endingATurnThatIsNotRunningChangesNothing() {
        turns.begin("a")

        assertEquals(false, turns.end("never-began"))
        assertEquals(false, turns.isIdle)
    }

    @Test
    fun aTurnBegunTwiceEndsOnce() {
        turns.begin("a")
        turns.begin("a")

        assertEquals(true, turns.end("a"))
    }
}
