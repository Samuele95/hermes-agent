package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A session's transcript, rebuilt from the core's event frames. */
class ChatTest {
    private fun event(type: String, payload: String? = null): CoreEvent =
        CoreEvent(type, "s1", payload?.let { Json.parseToJsonElement(it) })

    private fun Chat.after(vararg events: CoreEvent): Chat = events.fold(this) { chat, e -> chat.reduce(e) }

    @Test
    fun aTurnStreamsIntoOneReplyThatEndsTheTurn() {
        val streaming = Chat().sent("hi").after(event("message.start"), event("message.delta", """{"text":"Hel"}"""),
                                               event("message.delta", """{"text":"lo"}"""))
        assertTrue(streaming.running)
        assertEquals("Hello", streaming.streaming)

        val done = streaming.reduce(event("message.complete", """{"text":"Hello","status":"complete"}"""))

        assertFalse(done.running)
        assertEquals(listOf(Entry.User("hi"), Entry.Reply("Hello", TurnStatus.COMPLETE)), done.entries)
    }

    @Test
    fun theCompletedTextIsAuthoritativeOverTheStream() {
        val chat = Chat().after(event("message.start"), event("message.delta", """{"text":"Draft"}"""),
                                event("message.complete", """{"text":"Final answer"}"""))

        assertEquals(Entry.Reply("Final answer", TurnStatus.COMPLETE), chat.entries.single())
    }

    @Test
    fun aStructuredCompletionKeepsTheStreamedText() {
        val chat = Chat().after(event("message.start"), event("message.delta", """{"text":"Streamed"}"""),
                                event("message.complete", """{"text":[{"type":"text","text":"Streamed"}]}"""))

        assertEquals(Entry.Reply("Streamed", TurnStatus.COMPLETE), chat.entries.single())
    }

    @Test
    fun aFailedTurnCarriesItsError() {
        val chat = Chat().after(event("message.start"),
                                event("message.complete", """{"text":"","status":"error","error":"provider unreachable"}"""))

        assertEquals(Entry.Reply("", TurnStatus.ERROR, error = "provider unreachable"), chat.entries.single())
    }

    @Test
    fun toolsShowWhileTheyRunAndSettleWithTheirSummary() {
        val running = Chat().after(event("message.start"), event("tool.start", """{"tool_id":"t1","name":"web_search"}"""))
        assertEquals(Entry.Tool("t1", "web_search"), running.entries.single())

        val done = running.reduce(event("tool.complete", """{"tool_id":"t1","name":"web_search","summary":"3 results"}"""))

        assertEquals(Entry.Tool("t1", "web_search", summary = "3 results", done = true), done.entries.single())
    }

    @Test
    fun statusUpdatesShowUntilTheTurnEnds() {
        val busy = Chat().after(event("message.start"), event("status.update", """{"kind":"thinking","text":"Thinking…"}"""))
        assertEquals("Thinking…", busy.activity)

        assertEquals(null, busy.reduce(event("message.complete", """{"text":"ok"}""")).activity)
    }

    @Test
    fun anErrorEventBecomesANotice() {
        assertEquals(Entry.Notice("session expired"), Chat().reduce(event("error", """{"message":"session expired"}""")).entries.single())
    }

    @Test
    fun eventsTheChatDoesNotShowLeaveItUnchanged() {
        val chat = Chat().sent("hi")

        assertEquals(chat, chat.reduce(event("session.usage", """{"input":1}""")))
    }

    @Test
    fun aPayloadTheAppCannotReadIsIgnoredRatherThanStoppingTheChat() {
        val chat = Chat().after(event("message.start"))

        assertEquals(chat, chat.reduce(event("message.delta", """{"rendered":"no text field"}""")))
    }
}
