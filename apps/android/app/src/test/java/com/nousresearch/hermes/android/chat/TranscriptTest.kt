package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.TranscriptMessage
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** A stored conversation, as session.resume returns it, becomes the chat's transcript again. */
class TranscriptTest {
    @Test
    fun usersRepliesAndToolsComeBackInOrder() {
        val messages = listOf(
            TranscriptMessage(role = "user", text = "what's on my calendar?"),
            TranscriptMessage(role = "tool", name = "calendar", context = "today", toolCallId = "c1"),
            TranscriptMessage(role = "assistant", text = "Two meetings.", reasoning = "Read the calendar."),
        )

        assertEquals(listOf(Entry.User("what's on my calendar?", stableId = 0), Entry.Tool("c1", "calendar", "today", done = true, stableId = 1),
                            Entry.Reply("Two meetings.", TurnStatus.COMPLETE, reasoning = "Read the calendar.", stableId = 2)),
                     transcriptOf(messages, from = 0))
    }

    @Test
    fun systemRowsAreLeftOutAndAToolWithoutAnIdStillShows() {
        val messages = listOf(TranscriptMessage(role = "system", text = "model switched"),
                              TranscriptMessage(role = "tool", name = "terminal"))

        assertEquals(listOf(Entry.Tool("history-1", "terminal", null, done = true, stableId = 0)), transcriptOf(messages, from = 0))
    }

    @Test
    fun aUserRowKeepsItsRowId() {
        val messages = listOf(TranscriptMessage(role = "user", text = "hi", rowId = 5),
                              TranscriptMessage(role = "assistant", text = "Hello", rowId = 6))

        assertEquals(listOf(Entry.User("hi", 5, 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1)), transcriptOf(messages, from = 0))
    }

    @Test
    fun aTranscriptIsKeyedOnFromTheIdItIsGivenOneIdPerRowItKeeps() {
        val messages = listOf(TranscriptMessage(role = "user", text = "hi", rowId = 5),
                              TranscriptMessage(role = "system", text = "model switched"),
                              TranscriptMessage(role = "tool", name = "terminal"),
                              TranscriptMessage(role = "assistant", text = "Hello"))

        val entries = transcriptOf(messages, from = 10)

        assertEquals(listOf(10L, 11L, 12L), entries.map { it.stableId })  // the system row takes none: no gap
        assertEquals("history-2", (entries[1] as Entry.Tool).id)          // the tool's own id is the core's place, unchanged
    }
}
