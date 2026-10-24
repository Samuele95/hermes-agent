package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.ClarifyQuestion
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
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
    fun aTurnTheCoreEndsWithAnErrorIsOverAndSaysWhy() {
        // prompt_turn emits message.start, then only `error` (a refused @-reference, an ownership refusal, a crash);
        // the TUI ends the turn on `error` too (turnController.recordError).
        val failed = Chat().sent("@file:/etc/x").after(event("message.start"), event("message.delta", """{"text":"Hel"}"""),
                                                       event("status.update", """{"kind":"thinking","text":"thinking"}"""),
                                                       event("error", """{"message":"Context injection refused."}"""))

        assertFalse(failed.running)
        assertEquals(null, failed.activity)
        assertEquals(Entry.Notice("Context injection refused."), failed.entries.last())
    }

    @Test
    fun theReasoningStreamsBesideTheReplyAndStaysWithIt() {
        val reasoning = Chat().sent("why?").after(event("message.start"), event("reasoning.delta", """{"text":"Think"}"""),
                                                 event("reasoning.delta", """{"text":"ing."}"""))
        assertEquals("Thinking.", reasoning.reasoning)

        val done = reasoning.reduce(event("message.complete", """{"text":"Because.","status":"complete"}"""))

        assertEquals(null, done.reasoning)
        assertEquals(Entry.Reply("Because.", TurnStatus.COMPLETE, reasoning = "Thinking."), done.entries.last())
    }

    @Test
    fun theCompletedReasoningIsAuthoritativeAndAPreviewStandsInUntilThen() {
        val previewed = Chat().after(event("message.start"), event("reasoning.available", """{"text":"short preview"}"""))
        assertEquals("short preview", previewed.reasoning)

        val done = previewed.reduce(event("message.complete", """{"text":"ok","reasoning":"the full reasoning"}"""))

        assertEquals("the full reasoning", (done.entries.last() as Entry.Reply).reasoning)
    }

    @Test
    fun aReplyWithoutReasoningCarriesNone() {
        val done = Chat().after(event("message.start"), event("message.complete", """{"text":"ok"}"""))

        assertEquals(null, (done.entries.last() as Entry.Reply).reasoning)
    }

    @Test
    fun aFailedTurnDropsItsPartialReasoning() {
        val failed = Chat().after(event("message.start"), event("reasoning.delta", """{"text":"hm"}"""),
                                  event("error", """{"message":"refused"}"""))

        assertEquals(null, failed.reasoning)
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

    @Test
    fun aPromptWaitsInTheChatUntilTheCoreWithdrawsIt() {
        val approval = Prompt.Approval("srq-1", "r1", "rm -rf build", "", listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))
        val clarify = Prompt.Clarify("srq-2", listOf(ClarifyQuestion("q1", "Which colour?")))
        val asked = Chat().asked(approval).asked(clarify)

        val cancelled = asked.reduce(CoreEvent("request.cancel", "s1", Json.parseToJsonElement("""{"id":"srq-2","method":"clarify","reason":"timeout"}""")))
        val denied = cancelled.reduce(CoreEvent("approval.cancelled", "s1", Json.parseToJsonElement(
            """{"session_id":"s1","stored_session_id":"db","reason":"interrupted","cancelled_count":1,"request_ids":["r1"]}""")))

        assertEquals(listOf(approval, clarify), asked.prompts)
        assertEquals(listOf(approval), cancelled.prompts)
        assertEquals(emptyList<Prompt>(), denied.prompts)
        assertEquals(emptyList<Prompt>(), asked.settled("srq-1").settled("srq-2").prompts)
    }
}
