package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.chat.ToolSection.Command
import com.nousresearch.hermes.android.chat.ToolSection.Diff
import com.nousresearch.hermes.android.chat.ToolSection.Output
import com.nousresearch.hermes.android.chat.ToolSection.Payload
import com.nousresearch.hermes.android.chat.ToolSection.Search
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.TranscriptMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** A tool call's detail, as the row draws it: typed sections read from what the core sent (chat-08, bet 6). */
class ToolDetailTest {
    private fun event(type: String, payload: String): CoreEvent = CoreEvent(type, "s1", Json.parseToJsonElement(payload))

    private fun event(type: String, payload: JsonElement): CoreEvent = CoreEvent(type, "s1", payload)

    /** The one tool row a chat holds after [events] (live frames, reduced as the session does). */
    private fun live(vararg events: CoreEvent): Entry.Tool =
        events.fold(Chat()) { chat, e -> chat.reduce(e) }.entries.filterIsInstance<Entry.Tool>().single()

    /** A finished call: its start without detail, then its completion [payload] (which names `t1`). */
    private fun completed(name: String, payload: String): Entry.Tool =
        live(event("tool.start", """{"tool_id":"t1","name":"$name"}"""), event("tool.complete", payload))

    /** The core's stored rows as `session.resume` answers them, made the chat's transcript. */
    private fun stored(json: String): List<Entry> =
        transcriptOf(Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(TranscriptMessage.serializer()), json), 0)

    /** The core's real `inline_diff` for an edit: ANSI colour on every line under its translated review header (D4). */
    private val coreDiff = "  ┊ review diff\n\u001b[38;2;180;160;255ma/a.txt → b/a.txt\u001b[0m\n" +
        "\u001b[38;2;120;120;140m@@ -1,2 +1,2 @@\u001b[0m\n\u001b[38;2;255;255;255;48;2;120;20;20m-old\u001b[0m\n" +
        "\u001b[38;2;255;255;255;48;2;20;90;20m+new\u001b[0m\n\u001b[38;2;150;150;150m same\u001b[0m"

    @Test
    fun aTerminalCallIsItsCommandItsExitCodeItsOutputAndItsPayload() {
        val row = live(event("tool.start", """{"tool_id":"t1","name":"terminal","context":"ls -la","args":{"command":"ls -la"}}"""),
                       event("tool.complete", """{"tool_id":"t1","name":"terminal","args":{"command":"ls -la"},"duration_s":0.42,
                                                 "result":{"output":"a.txt\nb.txt","exit_code":0,"error":null}}"""))

        val sections = ToolDetail.of(row)

        val result = (sections.last() as Payload).result!!
        assertEquals(listOf(Command("ls -la", 0), Output("a.txt\nb.txt"), Payload(args = "{\n    \"command\": \"ls -la\"\n}", result = result)),
                     sections)
        assertEquals(Json.parseToJsonElement("""{"output":"a.txt\nb.txt","exit_code":0,"error":null}"""), Json.parseToJsonElement(result))
        assertTrue("the result is pretty-printed", result.contains('\n'))
    }

    @Test
    fun aFailedCommandKeepsItsExitCodeAndTheCoresWords() {
        val failed = completed("terminal", """{"tool_id":"t1","name":"terminal","args":{"command":"ls x"},
                                              "result":{"output":"","exit_code":2,"error":"ls: x: No such file"}}""")

        val sections = ToolDetail.of(failed)
        assertEquals(listOf(Command("ls x", 2), Output("ls: x: No such file")), sections.dropLast(1))
        assertTrue(failed.failed)

        // A true is not an exit code (the core's own rule, as failedOf reads it).
        val odd = completed("terminal", """{"tool_id":"t1","name":"terminal","args":{"command":"ls"},"result":{"output":"ok","exit_code":true}}""")
        assertEquals(Command("ls", exit = null), ToolDetail.of(odd).first())
    }

    @Test
    fun aFileEditIsItsDiffWithoutTheTerminalsColoursOrHeader() {
        val edit = live(event("tool.start", """{"tool_id":"t1","name":"patch"}"""),
                        event("tool.complete", buildJsonObject {
                            put("tool_id", "t1"); put("name", "patch")
                            put("args", buildJsonObject { put("path", "a.txt") })
                            put("result", buildJsonObject { put("success", true) })
                            put("inline_diff", coreDiff)
                        }))

        // No Output: {"success":true} has no words, and the diff already shows what the edit did.
        assertEquals(listOf(Diff("a/a.txt → b/a.txt\n@@ -1,2 +1,2 @@\n-old\n+new\n same", added = 1, removed = 1),
                            Payload("{\n    \"path\": \"a.txt\"\n}", "{\n    \"success\": true\n}")),
                     ToolDetail.of(edit))

        // The core translates its header: any first line opening on ┊ goes.
        assertEquals(Diff("-a\n+b\n+c", 2, 1), Diff.of("  ┊ Review-Diff\n-a\n+b\n+c"))
        // File header lines are not counted as changes.
        val headed = Diff.of("--- a/x\n+++ b/x\n-1\n+2")
        assertEquals(1 to 1, headed.added to headed.removed)
    }

    @Test
    fun aSearchIsItsQueryAndTheCoresHits() {
        val more = (2..7).joinToString(",") { """{"title":"Hit $it","url":"https://example.com/$it","description":"snippet $it","position":$it}""" }
        val search = completed("web_search", """{"tool_id":"t1","name":"web_search","args":{"query":"kotlin flow"},
            "result":{"success":true,"data":{"web":[{"title":"Flow","url":"https://kotlinlang.org/flow","description":"Cold streams","position":1},
                                                     {"description":"no title or url"},$more]}}}""")

        val sections = ToolDetail.of(search)

        val first = sections.first() as Search
        assertEquals("kotlin flow", first.query)
        assertEquals(6, first.hits.size)  // the untitled one left out, then cut at Desktop's six (D11)
        assertEquals(Search.Hit("Flow", "https://kotlinlang.org/flow", "Cold streams"), first.hits.first())
        assertEquals((2..6).map { "Hit $it" }, first.hits.drop(1).map { it.title })
        assertFalse("the hits already show the result", sections.any { it is Output })
        assertTrue(first.copy.startsWith("Flow\nhttps://kotlinlang.org/flow\nCold streams\n\n"))
        assertEquals("q", Search("q", emptyList()).copy)  // no hits: Copy gives the query
    }

    @Test
    fun anOldChatShowsWhatTheHistoryKeptAndNoOutputItDidNot() {
        val (terminal, patch, write) = stored("""[
            {"role":"tool","name":"terminal","tool_call_id":"c1","context":"ls","args":{"command":"ls"}},
            {"role":"tool","name":"patch","tool_call_id":"c2","context":"a.txt","args":{"path":"a.txt"},"content":"{\"success\":true}",
             "display_metadata":{"tool_result_metadata":{"inline_diff":"-old\n+new"}}},
            {"role":"tool","name":"write_file","tool_call_id":"c3","context":"b.txt","content":"{\"success\":false,\"error\":\"disk full\"}"}
        ]""").map { it as Entry.Tool }

        // The history kept the command, not what it printed: no Output, and never the summary in its place.
        assertEquals(listOf(Command("ls", null), Payload("{\n    \"command\": \"ls\"\n}", null)), ToolDetail.of(terminal))
        assertTrue(terminal.done)
        assertFalse(terminal.resultKept)

        assertEquals(listOf(Diff("-old\n+new", 1, 1), Payload("{\n    \"path\": \"a.txt\"\n}", "{\n    \"success\": true\n}")),
                     ToolDetail.of(patch))

        assertEquals(listOf(Output("disk full"), Payload(null, "{\n    \"success\": false,\n    \"error\": \"disk full\"\n}")),
                     ToolDetail.of(write))
    }

    @Test
    fun anUnknownToolShowsItsOutputAndPayloadEvenWhenItsJsonWasTrimmed() {
        val rows = completed("mcp_notes_search", """{"tool_id":"t1","name":"mcp_notes_search","result":{"rows":3}}""")
        assertEquals(listOf(Output("{\n    \"rows\": 3\n}"), Payload(null, "{\n    \"rows\": 3\n}")), ToolDetail.of(rows))

        val words = completed("mcp_notes_search", """{"tool_id":"t1","name":"mcp_notes_search","result":"plain words"}""")
        assertEquals(Output("plain words"), ToolDetail.of(words).first())

        // Over the cap the JSON is cut and no longer parses: shown as the text it is, marker and all (invariant 6).
        val big = live(event("tool.start", """{"tool_id":"t1","name":"mcp_notes_search"}"""),
                       event("tool.complete", buildJsonObject {
                           put("tool_id", "t1"); put("name", "mcp_notes_search")
                           put("result", buildJsonObject { put("output", "a".repeat(40_000)) })
                       }))
        val sections = ToolDetail.of(big)
        assertTrue(big.result!!.endsWith(Entry.Tool.TRIMMED))
        assertEquals(Output(big.result!!), sections.first())
        assertEquals(Payload(null, big.result), sections.last())
    }

    @Test
    fun aRunningToolShowsWhatItRunsAndNoOutputYet() {
        val running = live(event("tool.start", """{"tool_id":"t9","name":"terminal","args":{"command":"sleep 5"}}"""))

        assertEquals(listOf(Command("sleep 5", null), Payload("{\n    \"command\": \"sleep 5\"\n}", null)), ToolDetail.of(running))
        assertFalse(running.done)
    }

    @Test
    fun escapeCodesLeaveAnOutputAndACommand() {
        val coloured = completed("terminal", """{"tool_id":"t1","name":"terminal","args":{"command":"\u001b[1mls\u001b[0m"},
                                                "result":{"output":"\u001b[31mred\u001b[0m text","exit_code":0}}""")
        val sections = ToolDetail.of(coloured)
        assertEquals(Command("ls", 0), sections.first())
        assertEquals(listOf(Output("red text")), sections.filterIsInstance<Output>())

        val code = live(event("tool.start", """{"tool_id":"t1","name":"execute_code","args":{"code":"print(1)"}}"""))
        assertEquals(Command("print(1)", null), ToolDetail.of(code).first())
    }

    @Test
    fun aDurationReadsAsDesktopWritesIt() {
        mapOf(0.0004 to "1ms", 0.42 to "420ms", 3.4 to "3.4s", 12.6 to "13s", 64.0 to "1m 4s", 120.0 to "2m", 3725.0 to "1h 2m",
              -1.0 to "", Double.NaN to "").forEach { (seconds, words) -> assertEquals("$seconds", words, ToolDetail.duration(seconds)) }

        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            assertEquals("3.4s", ToolDetail.duration(3.4))  // English words, English digits (D12)
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun eachSectionCopiesWhatItShows() {
        assertEquals("ls", Command("ls", 0).copy)
        assertEquals("x", Output("x").copy)
        assertEquals("-a\n+b", Diff.of("-a\n+b").copy)
        assertEquals("Arguments:\n{}", Payload("{}", null).copy)
        assertEquals("Arguments:\n{}\n\nResult:\nr", Payload("{}", "r").copy)
    }
}
