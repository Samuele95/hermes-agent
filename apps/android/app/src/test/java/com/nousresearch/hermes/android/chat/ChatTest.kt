package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.ClarifyQuestion
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.SessionCompressResult
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
    fun theCoresSessionInfoNamesTheModel() {
        val switched = Chat(model = "deepseek-chat").reduce(event("session.info", """{"model":"kimi-k2","provider":"kimi"}"""))
        assertEquals("kimi-k2", switched.model)

        // An info frame about something else (tools, cwd) leaves the model as it was.
        assertEquals("kimi-k2", switched.reduce(event("session.info", """{"cwd":"/tmp"}""")).model)
    }

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
    fun aSentPromptIsBusyUntilItsTurnStartsAndEnds() {
        val sent = Chat().sent("hi")
        assertTrue(sent.busy)
        assertFalse(sent.running)  // the turn host starts at message.start (V2)

        val started = sent.reduce(event("message.start"))
        assertTrue(started.busy && started.running)
        assertFalse(started.submitted)
        assertFalse(started.reduce(event("message.complete", """{"text":"Hello"}""")).busy)

        // The core's cancel before the agent was ready ends with an error alone (V6).
        val cancelled = sent.reduce(event("error", """{"message":"Turn cancelled before the agent was ready"}"""))
        assertFalse(cancelled.busy)
        assertEquals(Entry.Notice("Turn cancelled before the agent was ready"), cancelled.entries.last())
        // A failed agent build completes the turn with no start.
        assertFalse(sent.reduce(event("message.complete", """{"text":"x","status":"error","error":"agent init failed"}""")).busy)

        val unsent = sent.unsent()
        assertFalse(unsent.busy)
        assertEquals(listOf(Entry.User("hi")), unsent.entries)
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

        assertEquals(chat, chat.reduce(event("session.title", """{"session_id":"s1","title":"Plans"}""")))
    }

    @Test
    fun theAgentsTodoListFollowsTheCoresSnapshotsByRevision() {
        val working = Chat().sent("plan it").after(event("message.start"))
        val one = """[{"id":"1","content":"Boil","status":"pending"}]"""

        // An unversioned list is not taken (D3): on a chat with no list it would otherwise show.
        val unversioned = working.reduce(event("tool.complete", """{"tool_id":"t0","name":"todo_list","todos":$one}"""))
        assertEquals(null, unversioned.todos)

        val planned = unversioned.reduce(event("todo.updated", """{"todos":[{"id":"1","content":"Boil","status":"in_progress"},
                                                                          {"id":"2","content":"Pour","status":"pending"}],"revision":2}"""))
        val two = TodoList(listOf(Todo("1", "Boil", TodoStatus.IN_PROGRESS), Todo("2", "Pour", TodoStatus.PENDING)), 2)
        assertEquals(two, planned.todos)

        val older = planned.reduce(event("tool.complete", """{"tool_id":"t","name":"todo_list","todos":$one,"revision":1}"""))
        assertEquals(two, older.todos)

        val equal = older.reduce(event("todo.updated", """{"todos":[{"id":"1","content":"Boil","status":"completed"},
                                                                 {"id":"2","content":"Pour","status":"pending"}],"revision":2}"""))
        assertEquals(TodoList(listOf(Todo("1", "Boil", TodoStatus.COMPLETED), Todo("2", "Pour", TodoStatus.PENDING)), 2), equal.todos)

        val newer = equal.reduce(event("tool.complete", """{"tool_id":"t2","name":"todo_list","todos":[{"id":"1","content":"Boil","status":"completed"},
                                                                 {"id":"2","content":"Pour","status":"completed"}],"revision":3}"""))
        assertEquals(TodoList(listOf(Todo("1", "Boil", TodoStatus.COMPLETED), Todo("2", "Pour", TodoStatus.COMPLETED)), 3), newer.todos)

        // A mirror: the turn's end leaves the plan as the core last sent it (the screen hides it, D1).
        assertEquals(newer.todos, newer.reduce(event("message.complete", """{"text":"ok"}""")).todos)
    }

    @Test
    fun theContextMeterFollowsEveryUsageTheCoreReports() {
        val ticked = Chat().sent("hi").after(event("message.start"),
                                            event("session.usage", """{"usage":{"context_used":1000,"context_max":8000,"context_percent":13}}"""))
        assertEquals(ContextMeter(1000, 8000, 13, false), ticked.meter)

        val done = ticked.reduce(event("message.complete", """{"text":"ok","usage":{"context_used":2000,"context_max":8000,
                                                               "context_percent":25,"context_estimated":true}}"""))
        assertEquals(ContextMeter(2000, 8000, 25, true), done.meter)

        val elsewhere = done.reduce(event("session.info", """{"cwd":"/tmp"}"""))  // no usage: the meter stays (D5)
        assertEquals(ContextMeter(2000, 8000, 25, true), elsewhere.meter)

        val info = elsewhere.reduce(event("session.info", """{"model":"m","usage":{"context_used":4000,"context_max":8000,"context_percent":50}}"""))
        assertEquals(50, info.meter?.percent)
        assertEquals("m", info.model)

        assertEquals(null, info.reduce(event("session.info", """{"usage":{}}""")).meter)  // a reading of nothing: no meter
    }

    @Test
    fun aUsageTickFromANewerCoreLeavesTheMeter() {
        val metered = Chat().reduce(event("session.usage", """{"usage":{"context_used":1000,"context_max":8000,"context_percent":13}}"""))

        assertEquals(metered, metered.reduce(event("session.usage", """{"input":1,"context_max":9}""")))
    }

    @Test
    fun aPayloadTheAppCannotReadIsIgnoredRatherThanStoppingTheChat() {
        val chat = Chat().after(event("message.start"))

        assertEquals(chat, chat.reduce(event("message.delta", """{"rendered":"no text field"}""")))
    }

    private val dateTurn: Chat = Chat().sent("what day is it?").after(
        event("message.start"), event("message.delta", """{"text":"Let me "}"""), event("message.delta", """{"text":"check."}"""),
        event("message.interim", """{"text":"Let me check.","already_streamed":true}"""))

    @Test
    fun commentaryIsSealedAsItsOwnSegmentWhileTheTurnRuns() {
        assertEquals(listOf(Entry.User("what day is it?"), Entry.Interim("Let me check.")), dateTurn.entries)
        assertTrue(dateTurn.running)
        assertEquals("", dateTurn.streaming)
    }

    @Test
    fun theAnswerAfterCommentaryStreamsFreshAndBothStay() {
        val answering = dateTurn.after(event("tool.start", """{"tool_id":"t1","name":"terminal"}"""),
                                       event("tool.complete", """{"tool_id":"t1","name":"terminal","summary":"Mon 3 Nov"}"""),
                                       event("message.delta", """{"text":"It is Monday."}"""))
        assertEquals("It is Monday.", answering.streaming)

        val done = answering.reduce(event("message.complete", """{"text":"It is Monday.","status":"complete"}"""))

        assertEquals(listOf(Entry.User("what day is it?"), Entry.Interim("Let me check."),
                            Entry.Tool("t1", "terminal", "Mon 3 Nov", done = true), Entry.Reply("It is Monday.", TurnStatus.COMPLETE)),
                     done.entries)
        assertFalse(done.running)
    }

    @Test
    fun commentaryTheCoreDidNotStreamIsSealedWithTheCoresWords() {
        val sealed = Chat().sent("what day is it?").after(event("message.start"), event("message.delta", """{"text":"Let me ch"}"""),
                                                         event("message.interim", """{"text":"Let me check.","already_streamed":false}"""))

        assertEquals(Entry.Interim("Let me check."), sealed.entries.last())
        assertEquals("", sealed.streaming)
    }

    @Test
    fun commentaryAfterItsToolStaysBelowIt() {
        val chat = Chat().sent("what day is it?").after(event("message.start"), event("tool.start", """{"tool_id":"t1","name":"terminal"}"""),
                                                       event("message.interim", """{"text":"Done checking.","already_streamed":true}"""))

        assertEquals(listOf(Entry.User("what day is it?"), Entry.Tool("t1", "terminal"), Entry.Interim("Done checking.")), chat.entries)
        assertTrue(chat.running)
    }

    @Test
    fun commentaryThatIsBlankOrOutsideATurnLeavesTheChatAsItWas() {
        val idle = Chat().sent("hi")
        val late = idle.reduce(event("message.interim", """{"text":"Late.","already_streamed":true}"""))
        assertEquals(idle, late)
        assertFalse(late.running)

        val running = Chat().sent("hi").after(event("message.start"), event("message.delta", """{"text":"So"}"""))
        assertEquals(running, running.reduce(event("message.interim", """{"text":"  \n","already_streamed":true}""")))
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

    @Test
    fun theCoreNamesThePromptsRowWhenItsTurnCompletes() {
        val named = Chat().sent("hi").after(event("message.start"), event("message.complete",
            """{"text":"Hello","persisted_turn":{"row_ids":[7,8],"complete":true,"user_row_id":7}}"""))
        assertEquals(listOf(Entry.User("hi", 7), Entry.Reply("Hello", TurnStatus.COMPLETE)), named.entries)

        // A completion without the receipt leaves the prompt unproven.
        val unnamed = Chat().sent("hi").after(event("message.start"), event("message.complete", """{"text":"Hello"}"""))
        assertEquals(Entry.User("hi"), unnamed.entries.first())
    }

    @Test
    fun onlyTheNewestUnnamedPromptTakesARowId() {
        val reply = Entry.Reply("r", TurnStatus.COMPLETE)
        val newestNamed = Chat(listOf(Entry.User("a"), reply, Entry.User("b", 9), reply))
        assertEquals(newestNamed, newestNamed.boundUser(12))  // the older unnamed prompt is not the newest one

        val unnamed = Chat(listOf(Entry.User("a", 3), reply, Entry.User("b")))
        assertEquals(unnamed, unnamed.boundUser(3))  // an id already shown stays where it is
        assertEquals(listOf(Entry.User("a", 3), reply, Entry.User("b", 12)), unnamed.boundUser(12).entries)
    }

    @Test
    fun theRetryTargetIsTheLastAnsweredPromptWhileNothingRuns() {
        val reply = Entry.Reply("r", TurnStatus.COMPLETE)
        assertEquals(null, Chat().retryTarget())
        assertEquals(null, Chat(listOf(Entry.User("a"))).retryTarget())  // no answer yet (D3)
        assertEquals(null, Chat(listOf(Entry.User("a", 1), reply), streaming = "").retryTarget())  // running
        assertEquals(null, Chat(listOf(Entry.User("  "), Entry.Notice("x"))).retryTarget())  // blank

        assertEquals(Retry(2, "b", 2), Chat(listOf(Entry.User("a", 1), reply, Entry.User("b", 2), Entry.Notice("x"))).retryTarget())
    }

    @Test
    fun noRetryWhileAPromptIsOnItsWay() {
        val reply = Entry.Reply("r", TurnStatus.COMPLETE)
        val onItsWay = Chat(listOf(Entry.User("a", 1), reply)).sent("b").notice("x")

        assertEquals(null, onItsWay.retryTarget())
        assertEquals(Retry(2, "b", null), onItsWay.unsent().retryTarget())
    }

    @Test
    fun aCompressionHoldsRetryAndOnlyTheSessionEndsIt() {
        val compressing = Chat(listOf(Entry.User("a", 1), Entry.Reply("b", TurnStatus.COMPLETE)), compressing = true)

        assertEquals(null, compressing.retryTarget())
        assertEquals(Retry(0, "a", 1), compressing.copy(compressing = false).retryTarget())
        // No frame ends it: only ChatSession.compress sets and clears it (invariant 6).
        assertTrue(compressing.after(event("error", """{"message":"x"}"""), event("session.info", "{}")).compressing)
    }

    @Test
    fun rewindingKeepsWhatCameBeforeThePrompt() {
        val reply = Entry.Reply("r", TurnStatus.COMPLETE)
        val chat = Chat(listOf(Entry.User("a", 1), reply, Entry.User("b", 2), Entry.Notice("x")), model = "kimi-k2")

        assertEquals(Chat(listOf(Entry.User("a", 1), reply), model = "kimi-k2"), chat.rewound(Retry(2, "b", 2)))
    }

    private fun compression(json: String): SessionCompressResult =
        Json { ignoreUnknownKeys = true }.decodeFromString(SessionCompressResult.serializer(), json)

    @Test
    fun aCompressionShowsTheCoresTranscriptAndFill() {
        val long = Chat(listOf(Entry.User("a", 1), Entry.Reply("A", TurnStatus.COMPLETE), Entry.User("b", 3), Entry.Reply("B", TurnStatus.COMPLETE)),
                        meter = ContextMeter(7_000, 8_000, 88, false))

        val kept = long.compressed(compression("""{"status":"compressed","messages":[{"role":"user","text":"b","row_id":7},{"role":"assistant","text":"B"}],
                                                   "usage":{"context_used":2000,"context_max":8000,"context_percent":25}}"""))

        assertEquals(listOf(Entry.User("b", 7), Entry.Reply("B", TurnStatus.COMPLETE)), kept.entries)
        assertEquals(ContextMeter(2_000, 8_000, 25, false), kept.meter)
        // A lock held elsewhere answers no transcript and no usage: nothing changes.
        assertEquals(long, long.compressed(compression("""{"compressed":false,"lock_held":true,"message":"busy elsewhere"}""")))
        // An empty usage is no window: no meter; no transcript answered keeps the one shown.
        val unmeasured = long.compressed(compression("""{"status":"compressed","usage":{}}"""))
        assertEquals(null, unmeasured.meter)
        assertEquals(long.entries, unmeasured.entries)
    }

    @Test
    fun aStatusOutsideATurnIsNotTheAgentsActivity() {
        val idle = Chat(listOf(Entry.User("hi"), Entry.Reply("Hello", TurnStatus.COMPLETE)))

        // The core's compress pins "compressing" and ends with an idle "ready" (D5).
        assertEquals(idle, idle.after(event("status.update", """{"kind":"compressing","text":"⠋ compressing 6 messages (~1,200 tok)…"}"""),
                                      event("status.update", """{"kind":"status","text":"ready"}""")))
        // The rule is the turn, not the kind.
        assertEquals("Thinking…", idle.after(event("message.start"), event("status.update", """{"kind":"thinking","text":"Thinking…"}""")).activity)
    }

    @Test
    fun theReducerLeavesTheJobNoticeToTheSession() {
        val scheduled = Chat().after(event("message.start"), event("tool.start", """{"tool_id":"t1","name":"cronjob_manage"}"""),
                                     event("tool.complete", """{"tool_id":"t1","name":"cronjob_manage","summary":"created",
                                         "args":{"action":"create","schedule":"every 2m","prompt":"Say tick."},
                                         "result":{"success":true,"job_id":"ab12","gateway_running":false}}"""))

        // The reducer is a function of (Chat, event): the gateway's notice is ChatSession's to add (FP, D39 F11).
        assertEquals(listOf(Entry.Tool("t1", "cronjob_manage", summary = "created", done = true)), scheduled.entries)
        assertEquals(Chat(listOf(Entry.Notice("x", null))), Chat().notice("x"))
    }
}
