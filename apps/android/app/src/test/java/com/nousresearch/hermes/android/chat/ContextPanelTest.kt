package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.protocol.CompressionSummary
import com.nousresearch.hermes.bridge.protocol.ContextCategory
import com.nousresearch.hermes.bridge.protocol.SessionCompressResult
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The context sheet: the meter at once, the core's breakdown when it comes, and Compress in the core's words. */
class ContextPanelTest {
    private val meterNow = ContextMeter(6_000, 32_000, 19, false)
    private var meter: ContextMeter? = meterNow
    private val first = SessionContextBreakdownResult(
        listOf(ContextCategory("var(--context-usage-system)", "system_prompt", "System prompt", 4_000),
               ContextCategory("var(--context-usage-conversation)", "conversation", "Conversation", 2_000)),
        contextMax = 32_000, contextPercent = 19, contextUsed = 6_000, estimatedTotal = 6_000, contextEstimated = false,
        contextSource = "provider_usage", model = "gemma4")
    private val later = first.copy(
        categories = listOf(first.categories[0], ContextCategory("var(--context-usage-conversation)", "conversation", "Conversation", 500)),
        contextUsed = 4_500, contextPercent = 14)
    private val reads = mutableListOf(first, later)
    private var breakdown: suspend () -> SessionContextBreakdownResult = { reads.removeAt(0) }
    private var compress: suspend (String?) -> String = { "Compressed: 6 → 2 messages" }
    /** The chat's session is being compressed: the aggregate's flag (invariant 6), set and cleared by its compress. */
    private var compressingNow = false
    private val panel = ContextPanel({ meter }, { breakdown() }, { compress(it) }, { compressingNow })

    @Test
    fun openingShowsTheMeterAtOnceAndThenTheBreakdown() = runTest {
        var seen: ContextUi? = null
        breakdown = { seen = panel.state.value; first }

        panel.open()

        assertEquals(ContextUi(meterNow), seen)  // loading: no breakdown, no error
        assertEquals(ContextUi(meterNow, first), panel.state.value)
    }

    @Test
    fun aBreakdownTheCoreCannotGiveIsSaidInItsWords() = runTest {
        breakdown = { throw CoreError(5000, "Could not compute context breakdown: boom") }

        panel.open()

        assertEquals(ContextUi(meterNow, error = "Could not compute context breakdown: boom"), panel.state.value)
    }

    @Test
    fun compressShowsTheOutcomeTheNewFillAndANewBreakdownAndKeepsTheSheet() = runTest {
        panel.open()
        var focus: String? = null
        var during: ContextUi? = null
        var stayed = false
        compress = {
            focus = it
            during = panel.state.value
            compressingNow = true  // as the aggregate admits it
            panel.close()  // refused while it runs (D3)
            stayed = panel.state.value != null
            meter = ContextMeter(4_500, 32_000, 14, false)
            compressingNow = false  // as the aggregate's finally clears it
            "Compressed: 6 → 2 messages"
        }

        panel.compress("here 1")

        assertEquals("here 1", focus)
        assertTrue(stayed)
        assertNull(during!!.outcome)
        assertEquals(ContextUi(ContextMeter(4_500, 32_000, 14, false), later, outcome = "Compressed: 6 → 2 messages"),
                     panel.state.value)
    }

    @Test
    fun aRefusedCompressionKeepsTheSheetWithTheCoresWords() = runTest {
        panel.open()
        val busy = "session busy — a /compress is running; run /compress when it finishes."
        compress = { throw CoreError(4009, busy) }

        panel.compress(null)

        assertEquals(ContextUi(meterNow, first, error = busy), panel.state.value)
    }

    @Test
    fun theSheetStaysWhileTheSessionCompresses() = runTest {
        panel.open()
        compressingNow = true

        panel.close()
        assertEquals(ContextUi(meterNow, first), panel.state.value)

        compressingNow = false
        panel.close()
        assertNull(panel.state.value)
    }

    @Test
    fun anAppRefusalIsShownAndASuccessClearsIt() = runTest {
        panel.open()
        compress = { throw SetupRefusal(ChatSession.NOTHING_TO_COMPRESS) }

        panel.compress(null)
        assertEquals(ContextUi(meterNow, first, error = "Nothing to compress yet."), panel.state.value)

        compress = { throw SetupRefusal(ChatSession.STILL_COMPRESSING) }
        panel.compress(null)
        assertEquals(ContextUi(meterNow, first, error = ChatSession.STILL_COMPRESSING), panel.state.value)

        compress = { "Compressed: 6 → 2 messages" }
        panel.compress(null)
        assertEquals(null, panel.state.value?.error)
        assertEquals("Compressed: 6 → 2 messages", panel.state.value?.outcome)
    }

    @Test
    fun closingDropsTheBreakdownAndOpeningReadsItAgain() = runTest {
        panel.open()
        panel.close()
        assertNull(panel.state.value)

        panel.open()

        assertEquals(later, panel.state.value?.breakdown)
    }

    @Test
    fun theOutcomeIsTheCoresSummaryElseItsMessageElseACount() {
        val headline = "Compressed: 12 → 4 messages"
        val tokens = "Approx request size: ~9,000 → ~2,100 tokens"
        assertEquals("$headline\n$tokens",
                     compressionOutcome(SessionCompressResult(summary = CompressionSummary(headline = headline, tokenLine = tokens))))
        assertEquals("$headline\n$tokens\nkept the last exchange",
                     compressionOutcome(SessionCompressResult(summary = CompressionSummary(headline = headline, tokenLine = tokens,
                                                                                            note = "kept the last exchange"))))
        assertEquals(headline, compressionOutcome(SessionCompressResult(summary = CompressionSummary(headline = headline, tokenLine = " "))))
        assertEquals("held elsewhere",
                     compressionOutcome(SessionCompressResult(summary = CompressionSummary(), message = "held elsewhere")))
        assertEquals("Another process is compressing this session.",
                     compressionOutcome(SessionCompressResult(compressed = false, lockHeld = true,
                                                              message = "Another process is compressing this session.")))
        assertEquals("Compressed 3 messages.", compressionOutcome(SessionCompressResult(removed = 3)))
        assertEquals("Nothing to compress.", compressionOutcome(SessionCompressResult()))
    }
}
