package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.TranscriptMessage
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun aUserRowAndAReplyRowKeepTheirRowIds() {
        val messages = listOf(TranscriptMessage(role = "user", text = "hi", rowId = 5),
                              TranscriptMessage(role = "assistant", text = "Hello", rowId = 6))

        assertEquals(listOf(Entry.User("hi", 5, 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1, rowId = 6)),
                     transcriptOf(messages, from = 0))
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

    /** The core's rows as `session.resume` / `session.history` answer them: the wire shape, not hand-built objects. */
    private fun rows(json: String): List<TranscriptMessage> =
        Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(TranscriptMessage.serializer()), json)

    @Test
    fun aStoredRowKeepsItsTimeInMillisecondsAndItsRowId() {
        val messages = rows("""[{"role":"user","text":"hi","row_id":5,"timestamp":1763300000.25},
                                {"role":"assistant","text":"Hello","row_id":6,"timestamp":1763300042.5},
                                {"role":"user","text":"again"}]""")

        // A row with no timestamp has no time: today's would be wrong (D9).
        assertEquals(listOf(Entry.User("hi", 5, 0, at = 1_763_300_000_250),
                            Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1, rowId = 6, at = 1_763_300_042_500),
                            Entry.User("again", stableId = 2)),
                     transcriptOf(messages, from = 0))
    }

    @Test
    fun aStoredToolRowKeepsItsArgumentsAndDiffAndOnlyTheOutputTheHistoryKept() {
        val messages = rows("""[
            {"role":"tool","name":"terminal","tool_call_id":"c1","context":"ls -la","args":{"command":"ls -la"},"timestamp":1763300001.0},
            {"role":"tool","name":"patch","tool_call_id":"c2","context":"a.txt","args":{"path":"a.txt"},"content":"{\"success\":true}",
             "display_metadata":{"tool_result_metadata":{"inline_diff":"--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new"}}},
            {"role":"tool","name":"write_file","tool_call_id":"c3","context":"b.txt","content":"{\"success\":false,\"error\":\"disk full\"}"}]""")

        val (c1, c2, c3) = transcriptOf(messages, from = 0).map { it as Entry.Tool }

        assertEquals("ls -la", c1.args!!["command"]!!.jsonPrimitive.content)
        assertNull("the history keeps no terminal output", c1.result)
        assertFalse(c1.resultKept)
        assertNull(c1.diff)
        assertFalse(c1.failed)
        assertNull("the history keeps no duration", c1.durationS)
        assertTrue(c1.done)

        assertEquals("{\"success\":true}", c2.result)
        assertTrue(c2.resultKept)
        assertEquals("--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new", c2.diff)
        assertFalse(c2.failed)

        assertTrue(c3.failed)
        assertTrue(c3.resultKept)
    }

    @Test
    fun aStoredReplyKeepsTheUsersOwnReactionOnly() {
        val messages = rows("""[
            {"role":"assistant","text":"Done.","row_id":9,"display_metadata":{"reactions":[{"emoji":"👍","author":"agent","at":1.0},
             {"emoji":"❤️","author":"user","at":2.0,"seen":true},{"emoji":"😂","author":"bot"},{"author":"user"},"junk"]}},
            {"role":"assistant","text":"Plain."},
            {"role":"assistant","text":"Odd.","display_metadata":{"reactions":"not a list"}}]""")

        assertEquals(listOf(listOf(Reaction("❤️", Author.USER)), emptyList(), emptyList()),
                     transcriptOf(messages, from = 0).map { (it as Entry.Reply).reactions })
    }

    @Test
    fun aStoredOutputIsCappedAsALiveOneIs() {
        val messages = rows("""[{"role":"tool","name":"write_file","tool_call_id":"c1","content":"${"a".repeat(40_000)}"}]""")

        val result = (transcriptOf(messages, from = 0).single() as Entry.Tool).result!!

        assertEquals(Entry.Tool.RESULT_CAP, result.encodeToByteArray().size)
        assertTrue(result.endsWith(Entry.Tool.TRIMMED))
    }
}
