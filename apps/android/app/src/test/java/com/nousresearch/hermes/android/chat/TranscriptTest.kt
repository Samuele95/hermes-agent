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

        assertEquals(listOf(Entry.User("what's on my calendar?"), Entry.Tool("c1", "calendar", "today", done = true),
                            Entry.Reply("Two meetings.", TurnStatus.COMPLETE, reasoning = "Read the calendar.")),
                     transcriptOf(messages))
    }

    @Test
    fun systemRowsAreLeftOutAndAToolWithoutAnIdStillShows() {
        val messages = listOf(TranscriptMessage(role = "system", text = "model switched"),
                              TranscriptMessage(role = "tool", name = "terminal"))

        assertEquals(listOf(Entry.Tool("history-1", "terminal", null, done = true)), transcriptOf(messages))
    }
}
