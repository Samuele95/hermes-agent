package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.entry.IncomingText
import com.nousresearch.hermes.bridge.protocol.ClarifyQuestion
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.SessionCompressResult
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A session's transcript, rebuilt from the core's event frames. */
class ChatTest {
    private fun event(type: String, payload: String? = null): CoreEvent =
        CoreEvent(type, "s1", payload?.let { Json.parseToJsonElement(it) })

    private fun Chat.after(vararg events: CoreEvent): Chat = events.fold(this) { chat, e -> chat.reduce(e) }

    private fun Chat.answered(text: String): Chat = after(event("message.start"), event("message.complete", """{"text":"$text","status":"complete"}"""))

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
        assertEquals(listOf(Entry.User("hi", stableId = 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1)), done.entries)
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
        assertEquals(Entry.Notice("Turn cancelled before the agent was ready", stableId = 1), cancelled.entries.last())
        // A failed agent build completes the turn with no start.
        assertFalse(sent.reduce(event("message.complete", """{"text":"x","status":"error","error":"agent init failed"}""")).busy)

        val unsent = sent.unsent()
        assertFalse(unsent.busy)
        assertEquals(listOf(Entry.User("hi", stableId = 0)), unsent.entries)
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
        assertEquals(Entry.Notice("Context injection refused.", stableId = 1), failed.entries.last())
    }

    @Test
    fun theReasoningStreamsBesideTheReplyAndStaysWithIt() {
        val reasoning = Chat().sent("why?").after(event("message.start"), event("reasoning.delta", """{"text":"Think"}"""),
                                                 event("reasoning.delta", """{"text":"ing."}"""))
        assertEquals("Thinking.", reasoning.reasoning)

        val done = reasoning.reduce(event("message.complete", """{"text":"Because.","status":"complete"}"""))

        assertEquals(null, done.reasoning)
        assertEquals(Entry.Reply("Because.", TurnStatus.COMPLETE, reasoning = "Thinking.", stableId = 1), done.entries.last())
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

        assertEquals(Entry.Reply("Final answer", TurnStatus.COMPLETE, stableId = 0), chat.entries.single())
    }

    @Test
    fun aStructuredCompletionKeepsTheStreamedText() {
        val chat = Chat().after(event("message.start"), event("message.delta", """{"text":"Streamed"}"""),
                                event("message.complete", """{"text":[{"type":"text","text":"Streamed"}]}"""))

        assertEquals(Entry.Reply("Streamed", TurnStatus.COMPLETE, stableId = 0), chat.entries.single())
    }

    @Test
    fun aFailedTurnCarriesItsError() {
        val chat = Chat().after(event("message.start"),
                                event("message.complete", """{"text":"","status":"error","error":"provider unreachable"}"""))

        assertEquals(Entry.Reply("", TurnStatus.ERROR, error = "provider unreachable", stableId = 0), chat.entries.single())
    }

    @Test
    fun toolsShowWhileTheyRunAndSettleWithTheirSummary() {
        val running = Chat().after(event("message.start"), event("tool.start", """{"tool_id":"t1","name":"web_search"}"""))
        assertEquals(Entry.Tool("t1", "web_search", stableId = 0), running.entries.single())

        val done = running.reduce(event("tool.complete", """{"tool_id":"t1","name":"web_search","summary":"3 results"}"""))

        assertEquals(Entry.Tool("t1", "web_search", summary = "3 results", done = true, stableId = 0), done.entries.single())
    }

    @Test
    fun statusUpdatesShowUntilTheTurnEnds() {
        val busy = Chat().after(event("message.start"), event("status.update", """{"kind":"thinking","text":"Thinking…"}"""))
        assertEquals("Thinking…", busy.activity)

        assertEquals(null, busy.reduce(event("message.complete", """{"text":"ok"}""")).activity)
    }

    @Test
    fun anErrorEventBecomesANotice() {
        assertEquals(Entry.Notice("session expired", stableId = 0), Chat().reduce(event("error", """{"message":"session expired"}""")).entries.single())
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
        assertEquals(listOf(Entry.User("what day is it?", stableId = 0), Entry.Interim("Let me check.", 1)), dateTurn.entries)
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

        assertEquals(listOf(Entry.User("what day is it?", stableId = 0), Entry.Interim("Let me check.", 1),
                            Entry.Tool("t1", "terminal", "Mon 3 Nov", done = true, stableId = 2),
                            Entry.Reply("It is Monday.", TurnStatus.COMPLETE, stableId = 3)),
                     done.entries)
        assertFalse(done.running)
    }

    @Test
    fun commentaryTheCoreDidNotStreamIsSealedWithTheCoresWords() {
        val sealed = Chat().sent("what day is it?").after(event("message.start"), event("message.delta", """{"text":"Let me ch"}"""),
                                                         event("message.interim", """{"text":"Let me check.","already_streamed":false}"""))

        assertEquals(Entry.Interim("Let me check.", 1), sealed.entries.last())
        assertEquals("", sealed.streaming)
    }

    @Test
    fun commentaryAfterItsToolStaysBelowIt() {
        val chat = Chat().sent("what day is it?").after(event("message.start"), event("tool.start", """{"tool_id":"t1","name":"terminal"}"""),
                                                       event("message.interim", """{"text":"Done checking.","already_streamed":true}"""))

        assertEquals(listOf(Entry.User("what day is it?", stableId = 0), Entry.Tool("t1", "terminal", stableId = 1), Entry.Interim("Done checking.", 2)),
                     chat.entries)
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
        assertEquals(listOf(Entry.User("hi", 7, 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1)), named.entries)

        // A completion without the receipt leaves the prompt unproven.
        val unnamed = Chat().sent("hi").after(event("message.start"), event("message.complete", """{"text":"Hello"}"""))
        assertEquals(Entry.User("hi", stableId = 0), unnamed.entries.first())
    }

    @Test
    fun onlyTheNewestUnnamedPromptTakesARowId() {
        val reply = Entry.Reply("r", TurnStatus.COMPLETE, stableId = 1)
        val newestNamed = Chat(listOf(Entry.User("a", stableId = 0), reply, Entry.User("b", 9, 2), reply))
        assertEquals(newestNamed, newestNamed.boundUser(12))  // the older unnamed prompt is not the newest one

        val unnamed = Chat(listOf(Entry.User("a", 3, 0), reply, Entry.User("b", stableId = 2)))
        assertEquals(unnamed, unnamed.boundUser(3))  // an id already shown stays where it is
        assertEquals(listOf(Entry.User("a", 3, 0), reply, Entry.User("b", 12, 2)), unnamed.boundUser(12).entries)
    }

    @Test
    fun theRetryTargetIsTheLastAnsweredPromptWhileNothingRuns() {
        val reply = Entry.Reply("r", TurnStatus.COMPLETE, stableId = 1)
        assertEquals(null, Chat().retryTarget())
        assertEquals(null, Chat(listOf(Entry.User("a", stableId = 0))).retryTarget())  // no answer yet (D3)
        assertEquals(null, Chat(listOf(Entry.User("a", 1, 0), reply), streaming = "").retryTarget())  // running
        assertEquals(null, Chat(listOf(Entry.User("  ", stableId = 0), Entry.Notice("x", stableId = 1))).retryTarget())  // blank

        assertEquals(Retry(2, "b", 2), Chat(listOf(Entry.User("a", 1, 0), reply, Entry.User("b", 2, 2), Entry.Notice("x", stableId = 3))).retryTarget())
    }

    @Test
    fun noRetryWhileAPromptIsOnItsWay() {
        val reply = Entry.Reply("r", TurnStatus.COMPLETE, stableId = 1)
        val onItsWay = Chat(listOf(Entry.User("a", 1, 0), reply)).sent("b").notice("x")

        assertEquals(null, onItsWay.retryTarget())
        assertEquals(Retry(2, "b", null), onItsWay.unsent().retryTarget())
    }

    @Test
    fun aCompressionHoldsRetryAndOnlyTheSessionEndsIt() {
        val compressing = Chat(listOf(Entry.User("a", 1, 0), Entry.Reply("b", TurnStatus.COMPLETE, stableId = 1)), compressing = true)

        assertEquals(null, compressing.retryTarget())
        assertEquals(Retry(0, "a", 1), compressing.copy(compressing = false).retryTarget())
        // No frame ends it: only ChatSession.compress sets and clears it (invariant 6).
        assertTrue(compressing.after(event("error", """{"message":"x"}"""), event("session.info", "{}")).compressing)
    }

    @Test
    fun anOwnedRunHoldsRetry() {
        val owned = Chat(listOf(Entry.User("a", 1, 0), Entry.Reply("b", TurnStatus.COMPLETE, stableId = 1)), runOwned = true)

        assertEquals(null, owned.retryTarget())  // a retry is a prompt.submit into the same session (V5)
        assertEquals(Retry(0, "a", 1), owned.copy(runOwned = false).retryTarget())
    }

    @Test
    fun rewindingKeepsWhatCameBeforeThePrompt() {
        val reply = Entry.Reply("r", TurnStatus.COMPLETE, stableId = 1)
        val chat = Chat(listOf(Entry.User("a", 1, 0), reply, Entry.User("b", 2, 2), Entry.Notice("x", stableId = 3)), model = "kimi-k2")

        // The counter stays: the next entry is not row 2 again.
        assertEquals(Chat(listOf(Entry.User("a", 1, stableId = 0), reply), model = "kimi-k2", nextId = 4), chat.rewound(Retry(2, "b", 2)))
    }

    private fun compression(json: String): SessionCompressResult =
        Json { ignoreUnknownKeys = true }.decodeFromString(SessionCompressResult.serializer(), json)

    @Test
    fun aCompressionShowsTheCoresTranscriptAndFill() {
        val long = Chat(listOf(Entry.User("a", 1, 0), Entry.Reply("A", TurnStatus.COMPLETE, stableId = 1), Entry.User("b", 3, 2),
                               Entry.Reply("B", TurnStatus.COMPLETE, stableId = 3)),
                        meter = ContextMeter(7_000, 8_000, 88, false))

        val kept = long.compressed(compression("""{"status":"compressed","messages":[{"role":"user","text":"b","row_id":7},{"role":"assistant","text":"B"}],
                                                   "usage":{"context_used":2000,"context_max":8000,"context_percent":25}}"""))

        // Keyed on after the rows shown before (V4): no kept row takes a cut row's id.
        assertEquals(listOf(Entry.User("b", 7, stableId = 4), Entry.Reply("B", TurnStatus.COMPLETE, stableId = 5)), kept.entries)
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
        val idle = Chat(listOf(Entry.User("hi", stableId = 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1)))

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
        assertEquals(listOf(Entry.Tool("t1", "cronjob_manage", summary = "created", done = true, stableId = 0)), scheduled.entries)
        assertEquals(Chat(listOf(Entry.Notice("x", null, 0))), Chat().notice("x"))
    }

    @Test
    fun everyEntryTheChatAddsTakesTheNextId() {
        val turn = Chat().sent("what day is it?").after(
            event("message.start"), event("message.interim", """{"text":"Let me check.","already_streamed":false}"""),
            event("tool.start", """{"tool_id":"t1","name":"terminal"}"""),
            event("tool.complete", """{"tool_id":"t1","name":"terminal","summary":"Mon 3 Nov"}"""),
            event("message.complete", """{"text":"It is Monday.","status":"complete"}"""),
            event("error", """{"message":"session expired"}"""))

        // The prompt, the commentary, the tool (its completion keeps its id), the answer, the notice: one id each, in order.
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), turn.entries.map { it.stableId })
        assertEquals(5L, turn.nextId)
        assertEquals(Entry.User("what day is it?", 7, stableId = 0), turn.boundUser(7).entries.first())  // named, same row
    }

    @Test
    fun aRewindNeverGivesAnIdAgain() {
        val asked = Chat().sent("a").answered("One").sent("b").answered("Two")           // 0, 1, 2, 3
        val again = asked.rewound(checkNotNull(asked.retryTarget())).sent("b").answered("Two again")

        assertEquals(listOf(0L, 1L, 4L, 5L), again.entries.map { it.stableId })         // the new answer is not row 3
        assertEquals(6L, again.nextId)
    }

    @Test
    fun aChatBuiltWithEntriesGoesOnAfterTheirHighestId() {
        assertEquals(0L, Chat().nextId)

        val built = Chat(listOf(Entry.User("a", stableId = 7), Entry.Reply("A", TurnStatus.COMPLETE, stableId = 3)))

        assertEquals(8L, built.nextId)
        assertEquals(8L, built.notice("x").entries.last().stableId)
    }

    @Test
    fun sendWhileATurnRunsFollowsTheModeOnlyWhileNothingWaits() {
        val sent = Chat(busyMode = BusyMode.INTERRUPT).sent("hi")
        assertEquals(null, sent.busySend)  // on its way, not started (D1): a second tap sends nothing

        val running = sent.reduce(event("message.start"))
        assertEquals(BusyMode.INTERRUPT, running.busySend)

        assertEquals(null, running.copy(busyMode = BusyMode.QUEUE).busySend)  // the app's queue is M3's (D3)
        assertEquals(null, running.copy(busyMode = null).busySend)            // not read, or the read failed
        assertEquals(null, running.asked(Prompt.Approval("srq-1", "r1", "ls", "", listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))).busySend)  // D8
        assertEquals(null, running.copy(runOwned = true).busySend)
        assertEquals(null, running.copy(compressing = true).busySend)
        assertEquals(null, running.reduce(event("message.complete", """{"text":"Hello","status":"complete"}""")).busySend)
    }

    @Test
    fun aCompletionBindsItsRowToThePromptNotTheCorrection() {
        val corrected = Chat().sent("hi").reduce(event("message.start")).correcting("use metric", BusyMode.INTERRUPT)
        val id = corrected.entries.last().stableId

        val done = corrected.reduce(event("message.complete",
            """{"text":"Hello","persisted_turn":{"row_ids":[7,8],"complete":true,"user_row_id":7}}"""))

        assertEquals(Entry.User("hi", rowId = 7, stableId = 0), done.entries.first())
        assertEquals(Entry.User("use metric", stableId = id, mode = BusyMode.INTERRUPT), done.entries.single { it.stableId == id })
        assertEquals("hi", done.retryTarget()!!.text)  // Retry runs the prompt again, never the correction

        val prompted = done.prompted(id)
        assertEquals(Entry.User("use metric", stableId = id), prompted.entries.last())  // a prompt after the turn it missed
        assertTrue(prompted.submitted)
        assertEquals(done.entries.size, prompted.entries.size)
    }

    @Test
    fun theQueueAndSteerNowAreOfferedOnlyWhereTheyCanLand() {
        val idle = Chat(busyMode = BusyMode.INTERRUPT)
        val sent = idle.sent("hi")
        val running = sent.reduce(event("message.start"))

        assertFalse(idle.queueable)
        assertTrue(sent.queueable)     // on its way: the next prompt can wait for it (D12)
        assertTrue(running.queueable)
        assertFalse(running.copy(runOwned = true).queueable)
        assertFalse(running.copy(compressing = true).queueable)

        assertEquals(null, sent.steerMode)  // before message.start a correction cannot land (D3)
        assertEquals(null, running.asked(Prompt.Approval("srq-1", "r1", "ls", "", listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))).steerMode)
        assertEquals(null, running.copy(busyMode = null).steerMode)
        assertEquals(null, running.copy(runOwned = true).steerMode)
        assertEquals(null, running.copy(compressing = true).steerMode)
        assertEquals(BusyMode.STEER, running.copy(busyMode = BusyMode.STEER).steerMode)
        assertEquals(BusyMode.INTERRUPT, running.steerMode)
        assertEquals(BusyMode.INTERRUPT, running.copy(busyMode = BusyMode.QUEUE).steerMode)  // queue mode redirects

        val corrected = running.correcting("use metric", BusyMode.INTERRUPT).notice("x")
        val id = corrected.entries[1].stableId
        val without = corrected.without(id)
        assertEquals(corrected.entries.filterNot { it.stableId == id }, without.entries)
        assertEquals(corrected.nextId, without.nextId)
        assertEquals(corrected, corrected.without(99))
    }

    @Test
    fun anOfferOutlivesEveryEventOfATurn() {
        val offer = IncomingText("from Chrome")
        val after = Chat(offer = offer).sent("hi").after(event("message.start"), event("message.delta", """{"text":"Hel"}"""),
                                                         event("message.complete", """{"text":"Hello","status":"complete"}"""),
                                                         event("error", """{"message":"boom"}"""))

        assertEquals(offer, after.offer)  // the reducer never touches it: only the composer takes it (inv. 6)
    }

    // The out-of-credits wall describes the chat's last turn: raised by a completion carrying billing, gone with the next
    // turn that completes (inv. 5, DC-M12, D5).

    private val deepseek = """{"provider":"deepseek","provider_label":"DeepSeek","model":"deepseek-v4-pro",
        "billing_url":"https://platform.deepseek.com/top_up","is_nous":false,
        "message":"DeepSeek reported that billing, credits, or account entitlement is exhausted for deepseek-v4-pro.\nAdd credits or update billing with that provider, then retry.\nYou can switch providers temporarily with /model <model> --provider <provider>."}"""

    /** A turn the provider refused for billing, as `prompt_turn.py` completes it, carrying [block]. */
    private fun billed(block: String = deepseek) = """{"text":"Billing or credits exhausted: 402","status":"error",
        "error":"402 Insufficient Balance","recoverable":true,"failure_reason":"billing",
        "error_surface":{"layer":"billing","code":"billing","retryable":false},"billing":$block}"""

    private val BILLED = billed()

    private val walled: Chat = Chat().sent("hi").after(event("message.start"), event("message.complete", BILLED))

    @Test
    fun aCompletionCarryingBillingRaisesTheWall() {
        assertEquals(BillingWall("deepseek", "DeepSeek", false, "https://platform.deepseek.com/top_up",
                                 "DeepSeek reported that billing, credits, or account entitlement is exhausted for deepseek-v4-pro."),
                     walled.wall)
        val reply = walled.entries.last() as Entry.Reply
        assertEquals(TurnStatus.ERROR, reply.status)
        assertEquals("402 Insufficient Balance", reply.error)
        assertFalse(walled.busy)
    }

    @Test
    fun theWallStaysUntilALaterTurnCompletes() {
        val wall = walled.wall!!

        val started = walled.reduce(event("message.start"))
        assertTrue(started.running)
        assertEquals("a turn starting keeps it", wall, started.wall)
        val streaming = started.reduce(event("message.delta", """{"text":"Hel"}"""))
        assertEquals("a delta keeps it", wall, streaming.wall)
        assertEquals("a stopped turn keeps it", wall, streaming.reduce(event("message.complete", """{"text":"","status":"interrupted"}""")).wall)
        assertEquals("the bare error keeps it", wall, walled.after(event("message.start"), event("error", """{"message":"boom"}""")).wall)
        assertEquals("another failure keeps it", wall,
                     walled.after(event("message.start"), event("message.complete", """{"text":"","status":"error","error":"timeout"}""")).wall)
        assertEquals("a compress or resync keeps it", wall, walled.restored(emptyList()).wall)

        assertEquals("a completed turn takes it away", null, walled.answered("Hello").wall)
        assertEquals("an absent status reads complete", null,
                     walled.after(event("message.start"), event("message.complete", """{"text":"ok"}""")).wall)
    }

    @Test
    fun aNewerWallReplacesTheOlderAndDismissTakesItAway() {
        val nous = walled.after(event("message.start"), event("message.complete", billed("""{"provider":"nous","provider_label":"Nous Portal",
            "model":"m","billing_url":"https://portal.nousresearch.com/billing","is_nous":true,"message":""}""")))
        assertTrue(nous.wall!!.nous)
        assertEquals("https://portal.nousresearch.com/billing", nous.wall!!.url)

        val dismissed = nous.withoutWall()
        assertEquals(null, dismissed.wall)
        assertEquals(nous.entries, dismissed.entries)
        val plain = Chat()
        assertSame(plain, plain.withoutWall())
    }
}
