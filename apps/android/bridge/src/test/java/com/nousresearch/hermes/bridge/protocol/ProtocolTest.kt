package com.nousresearch.hermes.bridge.protocol

import com.nousresearch.hermes.bridge.wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated types speak the core's wire format: snake_case names, defaults left to the core. */
class ProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun aRequestCarriesOnlyTheFieldsTheAppSets() {
        val wire = json.encodeToString(Methods.PROMPT_SUBMIT.params, PromptSubmitParams(sessionId = "s1", text = JsonPrimitive("héllo 😀")))

        assertEquals("""{"session_id":"s1","text":"héllo 😀"}""", wire)
    }

    @Test
    fun aResultDecodesFromTheWireIgnoringFieldsTheAppDoesNotModel() {
        assertEquals(PingResult(pong = true), json.decodeFromString(Methods.PING.result, """{"pong":true,"latency_ms":3}"""))
    }

    @Test
    fun paramsWithoutFieldsAreAnEmptyObject() {
        assertEquals("{}", json.encodeToString(Methods.PING.params, PingParams))
    }

    // The tests below use the app's production wire config: the core reads a present `title`, even null, as a set.

    @Test
    fun aTitleIsReadWithoutATitleAndSetWithOne() {
        assertEquals("""{"session_id":"r1"}""", wire.encodeToString(Methods.SESSION_TITLE.params, SessionTitleParams(sessionId = "r1")))
        assertEquals(
            """{"session_id":"r1","title":"Trip to Rome"}""",
            wire.encodeToString(Methods.SESSION_TITLE.params, SessionTitleParams(sessionId = "r1", title = "Trip to Rome")),
        )

        val titled = wire.decodeFromString(
            Methods.SESSION_TITLE.result,
            """{"title":"Trip to Rome","session_key":"20261103_101500_ab12cd","pending":false}""",
        )

        assertEquals("20261103_101500_ab12cd", titled.sessionKey)
        assertEquals(false, titled.pending)
    }

    @Test
    fun aDeleteNamesTheStoredSessionAndAnswersIt() {
        assertEquals(
            """{"session_id":"20261103_101500_ab12cd"}""",
            wire.encodeToString(Methods.SESSION_DELETE.params, SessionDeleteParams(sessionId = "20261103_101500_ab12cd")),
        )
        assertEquals(
            "20261103_101500_ab12cd",
            wire.decodeFromString(Methods.SESSION_DELETE.result, """{"deleted":"20261103_101500_ab12cd"}""").deleted,
        )
    }

    @Test
    fun aCloseAnswersWhetherItClosed() {
        val closed = wire.decodeFromString(Methods.SESSION_CLOSE.result, """{"closed":true,"messages":["Session ended."]}""")

        assertTrue(closed.closed)
        assertEquals(listOf("Session ended."), closed.messages)
    }

    @Test
    fun aCompressionDecodesItsSummaryUsageAndNewTranscript() {
        val compressed = wire.decodeFromString(
            Methods.SESSION_COMPRESS.result,
            """{"status":"compressed","removed":12,"before_messages":20,"after_messages":8,"before_tokens":9000,""" +
                """"after_tokens":2100,"summary":{"headline":"Compressed 12 messages","token_line":"9,000 → 2,100 tokens"},""" +
                """"usage":{"context_used":2100,"context_max":8192,"context_percent":26},""" +
                """"messages":[{"role":"user","text":"hi","row_id":4}]}""",
        )

        assertEquals("Compressed 12 messages", compressed.summary!!.headline)
        assertEquals(false, compressed.summary.noop)
        assertEquals(8192L, compressed.usage!!.contextMax)
        assertEquals(4L, compressed.messages!!.single().rowId)
    }

    @Test
    fun aCompressionRefusedByAHeldLockSaysSo() {
        val refused = wire.decodeFromString(
            Methods.SESSION_COMPRESS.result,
            """{"compressed":false,"lock_held":true,"message":"another compression is running"}""",
        )

        assertEquals(false, refused.compressed)
        assertEquals(true, refused.lockHeld)
        assertNull(refused.summary)
    }

    @Test
    fun aContextBreakdownDecodesItsCategories() {
        val breakdown = wire.decodeFromString(
            Methods.SESSION_CONTEXT_BREAKDOWN.result,
            """{"categories":[{"color":"#7aa2f7","id":"system","label":"System prompt","tokens":1200}],"context_max":8192,""" +
                """"context_percent":15,"context_used":1200,"estimated_total":1200,"context_estimated":true,""" +
                """"context_source":"estimate","model":"gemma4"}""",
        )

        assertEquals(1200L, breakdown.categories.single().tokens)
        assertTrue(breakdown.contextFiles.isEmpty())
        assertTrue(breakdown.contextEstimated)
    }

    @Test
    fun aTodoSnapshotKeepsItsItemsAsTheCoreSentThem() {
        val snapshot = wire.decodeFromString(
            Events.TODO_UPDATED.payload,
            """{"todos":[{"id":"1","content":"Read the file","status":"in_progress","extra":1}],"revision":3}""",
        )

        assertEquals(3L, snapshot.revision)
        assertEquals("in_progress", snapshot.todos.single().jsonObject["status"]!!.jsonPrimitive.content)
    }
}
