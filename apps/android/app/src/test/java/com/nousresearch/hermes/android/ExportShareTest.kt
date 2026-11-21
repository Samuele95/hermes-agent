package com.nousresearch.hermes.android

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** One try at the share sheet (N15): no target or a path outside the root fails for good, Android's refusal defers it. */
class ExportShareTest {
    private var told = 0
    private val failed: () -> Unit = { told++ }

    @Test
    fun aStartedShareIsTrueAndTellsNothing() {
        assertTrue(tryShare({}, failed))
        assertEquals(0, told)
    }

    @Test
    fun noAppToTakeItFailsForGoodAndTellsOnce() {
        assertFalse(tryShare({ throw ActivityNotFoundException("No Activity found to handle Intent") }, failed))
        assertEquals(1, told)
    }

    @Test
    fun aPathOutsideTheProvidersRootFailsForGoodAndTellsOnce() {
        assertFalse(tryShare({ throw IllegalArgumentException("Failed to find configured root") }, failed))
        assertEquals(1, told)
    }

    @Test
    fun androidsRefusalOnlyDefersItAndTellsNothing() {
        assertFalse(tryShare({ throw SecurityException("background activity start blocked") }, failed))
        assertEquals(0, told)
    }

    @Test
    fun anyOtherFailureIsNotCaughtHere() {
        assertThrows(IllegalStateException::class.java) { tryShare({ throw IllegalStateException("bug") }, failed) }
        assertEquals(0, told)
    }
}
