package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How full the session's context window is, read from the core's usage. */
class ContextMeterTest {
    @Test
    fun aUsageWithAContextWindowIsAMeter() {
        assertEquals(ContextMeter(53_800, 128_000, 42, true),
                     ContextMeter.of(Usage(contextUsed = 53_800, contextMax = 128_000, contextPercent = 42, contextEstimated = true)))
    }

    @Test
    fun aUsageWithoutAContextWindowShowsNoMeter() {
        assertNull(ContextMeter.of(Usage()))
        assertNull(ContextMeter.of(Usage(contextMax = 0, contextUsed = 5)))
        assertNull(ContextMeter.of(Usage(total = 9_000)))
    }

    @Test
    fun theMeterStaysInsideItsWindow() {
        assertEquals(ContextMeter(8_000, 8_000, 100, false), ContextMeter.of(Usage(contextUsed = 9_000, contextMax = 8_000)))
        assertEquals(13, ContextMeter.of(Usage(contextUsed = 1_000, contextMax = 8_000))!!.percent)
        assertEquals(100, ContextMeter.of(Usage(contextUsed = 1_000, contextMax = 8_000, contextPercent = 140))!!.percent)

        assertTrue(ContextMeter(80, 100, 80, false).warns)
        assertFalse(ContextMeter(79, 100, 79, false).warns)
    }

    @Test
    fun tokensAreCountedLikeTheDesktop() {
        val counted = listOf(0L, 999L, 1_000L, 12_345L, 128_000L, 999_949L, 999_950L, 1_500_000L).map(::compactTokens)

        assertEquals(listOf("0", "999", "1k", "12.3k", "128k", "999.9k", "1M", "1.5M"), counted)
    }
}
