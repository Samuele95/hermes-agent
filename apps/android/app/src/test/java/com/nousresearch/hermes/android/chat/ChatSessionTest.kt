package com.nousresearch.hermes.android.chat

import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.protocol.BackendContract
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.nousresearch.hermes.android.SetupRefusal
import org.junit.Test

/** One chat: a core session, the prompts the user sends to it, and the turns it runs. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: events are reduced as they are emitted
class ChatSessionTest {
    private val turns = mutableListOf<String>()
    private val host = object : TurnHost {
        override fun begin(turn: String) { turns += "begin:$turn" }
        override fun end(turn: String) { turns += "end:$turn" }
    }
    private var submit: () -> String = { """{"status":"streaming"}""" }
    private var sessions = 0
    private var close: (JsonObject) -> String = { """{"closed":true}""" }
    private var delete: (String) -> String = { id -> """{"deleted":"$id"}""" }
    private var contract = """"desktop_contract":${BackendContract.BACKEND_CONTRACT}"""
    private var config: () -> String = { """{"config":{"display":{}}}""" }
    private var mostRecent: () -> String = { """{"session_id":null}""" }
    private var history: () -> String = { """{"count":0,"messages":[]}""" }
    // The core continues a compressed conversation at its tip and answers that key (no top-level stored id).
    private var resume: () -> String = { """{"session_id":"s-resumed","message_count":2,"session_key":"db-old-tip",
                                   "messages":[{"role":"user","text":"what's on my calendar?"},{"role":"assistant","text":"Two meetings."}],
                                   "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"kimi-k2"}}""" }
    private val core = FakeCoreGateway { method, params ->
        when (method) {
            "session.create" -> """{"session_id":"s${++sessions}","stored_session_id":"db$sessions","message_count":0,"messages":[],"info":{$contract}}"""
            "prompt.submit" -> submit()
            "session.close" -> close(params)
            "session.delete" -> delete(params.getValue("session_id").jsonPrimitive.content)
            "session.interrupt" -> """{"status":"interrupted"}"""
            "session.list" -> """{"sessions":[{"id":"db-old","title":"Calendar","message_count":3,"started_at":1.0},
                                                 {"id":"db-empty","message_count":0,"started_at":2.0}]}"""
            "session.resume" -> resume()
            "session.most_recent" -> mostRecent()
            "config.get" -> config()
            "session.history" -> history()
            else -> error("unexpected $method")
        }
    }

    private val kept = mutableMapOf<String, String>()
    private var keepSecret: (String, ByteArray) -> Unit = { name, value -> kept[name] = String(value) }

    private val notices = mutableListOf<String>()
    private val notifier = object : Notifier {
        override fun promptPending(prompt: Prompt) { notices += "pending:${prompt.id}" }
        override fun promptsSettled() { notices += "settled" }
        override fun turnFinished(reply: Entry.Reply?) { notices += "finished:${reply?.text}" }
    }

    /** The runtime ids the chat asked the core to close, in order. */
    private fun closed() = core.calls.filter { it.first == "session.close" }.map { it.second.getValue("session_id").jsonPrimitive.content }

    /** The calls after the first [skip], each as its method and the id it named. */
    private fun callsAfter(skip: Int) = core.calls.drop(skip).map { it.first to it.second["session_id"]?.jsonPrimitive?.content }

    /** The params of the last prompt.submit; a field the app left unset is absent. */
    private fun lastSubmit() = core.calls.last { it.first == "prompt.submit" }.second

    private fun TestScope.openChat() =
        ChatSession(core, host, backgroundScope, keepSecret = { name, value -> keepSecret(name, value) }, notifier = notifier)

    @Test
    fun aChatOpensACoreSessionAsTheAndroidApp() = runTest(UnconfinedTestDispatcher()) {
        openChat().open()

        assertEquals("session.create" to "android", core.calls.single().let { it.first to it.second.getValue("source").jsonPrimitive.content })
    }

    @Test
    fun aSentPromptGoesToTheChatsSessionAndIntoTheTranscript() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        chat.send("hello")

        val (method, params) = core.calls.last()
        assertEquals(Triple("prompt.submit", "s1", "hello"),
                     Triple(method, params.getValue("session_id").jsonPrimitive.content, params.getValue("text").jsonPrimitive.content))
        assertEquals(listOf(Entry.User("hello")), chat.state.value.entries)
    }

    @Test
    fun aChatKnowsTheModelItsSessionRuns() = runTest(UnconfinedTestDispatcher()) {
        contract = """"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"deepseek-chat","provider":"deepseek""""
        val chat = openChat().apply { open() }
        assertEquals("deepseek-chat", chat.state.value.model)

        chat.resume("db-old")  // a stored session runs the model it was resumed with

        assertEquals("kimi-k2", chat.state.value.model)
    }

    @Test
    fun aNewSessionShowsTheContextItsCoreReports() = runTest(UnconfinedTestDispatcher()) {
        contract = """"desktop_contract":${BackendContract.BACKEND_CONTRACT},"usage":{"context_used":3000,"context_max":32000,"context_percent":9,"context_estimated":true}"""
        val chat = openChat().apply { open() }

        assertEquals(ContextMeter(3000, 32000, 9, true), chat.state.value.meter)
        assertEquals(null, chat.state.value.todos)
    }

    @Test
    fun aResumedSessionBringsBackItsPlanAndItsMeter() = runTest(UnconfinedTestDispatcher()) {
        resume = { """{"session_id":"s-resumed","message_count":2,"session_key":"db-old-tip",
                      "messages":[{"role":"user","text":"what's on my calendar?"},{"role":"assistant","text":"Two meetings."}],
                      "todo_state":{"todos":[{"id":"1","content":"Read the calendar","status":"completed"},
                                             {"id":"2","content":"Answer","status":"in_progress"}],"revision":4},
                      "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"kimi-k2",
                              "usage":{"context_used":6000,"context_max":32000,"context_percent":19}}}""" }
        val chat = openChat().apply { open() }

        chat.resume("db-old")

        assertEquals(TodoList(listOf(Todo("1", "Read the calendar", TodoStatus.COMPLETED), Todo("2", "Answer", TodoStatus.IN_PROGRESS)), 4),
                     chat.state.value.todos)
        assertEquals(ContextMeter(6000, 32000, 19, false), chat.state.value.meter)
    }

    @Test
    fun aNewChatLeavesTheOldSessionsPlanAndMeterBehind() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("message.start", "s1")
        core.emit("todo.updated", "s1", """{"todos":[{"id":"1","content":"Boil","status":"pending"}],"revision":1}""")
        core.emit("session.usage", "s1", """{"usage":{"context_used":1000,"context_max":8000,"context_percent":13}}""")
        val plan = TodoList(listOf(Todo("1", "Boil", TodoStatus.PENDING)), 1)
        assertEquals(plan, chat.state.value.todos)
        assertEquals(ContextMeter(1000, 8000, 13, false), chat.state.value.meter)

        core.emit("todo.updated", "s9", """{"todos":[{"id":"9","content":"Other","status":"pending"}],"revision":7}""")  // another session's
        assertEquals(plan, chat.state.value.todos)

        chat.open()

        assertEquals(null, chat.state.value.todos)
        assertEquals(null, chat.state.value.meter)
    }

    @Test
    fun aCoreOnTheContractTheAppWasBuiltForOpensQuietly() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        assertEquals(emptyList<Entry>(), chat.state.value.entries)
    }

    @Test
    fun aCoreOnAnotherContractIsSaidOnOpen() = runTest(UnconfinedTestDispatcher()) {
        for (reported in listOf(""""desktop_contract":${BackendContract.BACKEND_CONTRACT + 1}""", "")) {  // newer, or none
            contract = reported
            val chat = openChat().apply { open() }

            assertTrue("a notice for $reported", chat.state.value.entries.single() is Entry.Notice)
        }
    }

    @Test
    fun aTurnKeepsTheAppAliveFromItsStartToItsCompletion() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hello")

        core.emit("message.start", "s1")
        assertEquals(listOf("begin:s1"), turns)
        core.emit("message.complete", "s1", """{"text":"hi there"}""")

        assertEquals(listOf("begin:s1", "end:s1"), turns)
        assertEquals("hi there", (chat.state.value.entries.last() as Entry.Reply).text)
    }

    @Test
    fun commentaryKeepsTheTurnAliveAndIsNotTheAnswer() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("what day is it?")

        core.emit("message.start", "s1")
        core.emit("message.delta", "s1", """{"text":"Let me check."}""")
        core.emit("message.interim", "s1", """{"text":"Let me check.","already_streamed":true}""")
        assertEquals(listOf("begin:s1"), turns)
        assertTrue(notices.none { it.startsWith("finished:") })

        core.emit("message.complete", "s1", """{"text":"It is Monday."}""")

        assertEquals(listOf("begin:s1", "end:s1"), turns)
        assertEquals("finished:It is Monday.", notices.last())
    }

    @Test
    fun aTurnTheCoreEndsWithAnErrorReleasesTheApp() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hello")

        core.emit("message.start", "s1")
        core.emit("error", "s1", """{"message":"Context injection refused."}""")

        assertEquals(listOf("begin:s1", "end:s1"), turns)
        assertFalse(chat.state.value.running)
    }

    @Test
    fun stoppingInterruptsTheChatsRunningTurnAndItEndsAsInterrupted() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("write a long essay")
        core.emit("message.start", "s1")

        chat.cancel()
        core.emit("message.complete", "s1", """{"text":"","status":"interrupted"}""")

        val (method, params) = core.calls.last()
        assertEquals("session.interrupt" to "s1", method to params.getValue("session_id").jsonPrimitive.content)
        assertFalse(chat.state.value.running)
        assertEquals(listOf("begin:s1", "end:s1"), turns)
    }

    @Test
    fun stoppingWithNoTurnRunningAsksTheCoreNothing() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        chat.cancel()

        assertTrue(core.calls.none { it.first == "session.interrupt" })
    }

    @Test
    fun pastSessionsAreTheOnesWithMessages() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        assertEquals(listOf("db-old"), chat.pastSessions().map { it.id })
    }

    @Test
    fun resumingASessionBringsItsTranscriptBackAndSendsToIt() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        chat.resume("db-old")
        chat.send("and tomorrow?")

        assertEquals(listOf(Entry.User("what's on my calendar?"), Entry.Reply("Two meetings.", TurnStatus.COMPLETE),
                            Entry.User("and tomorrow?")), chat.state.value.entries)
        assertEquals("s-resumed", core.calls.last().second.getValue("session_id").jsonPrimitive.content)
        val resumed = core.calls.single { it.first == "session.resume" }.second
        assertEquals("db-old", resumed.getValue("session_id").jsonPrimitive.content)
    }

    @Test
    fun resumingMidTurnReleasesTheRunningTurn() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hello")
        core.emit("message.start", "s1")

        chat.resume("db-old")

        assertEquals(listOf("begin:s1", "end:s1"), turns)
        assertFalse(chat.state.value.running)
    }

    @Test
    fun anErrorOutsideATurnIsOnlyANotice() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        core.emit("error", "s1", """{"message":"compression failed: busy"}""")

        assertEquals(emptyList<String>(), turns)
        assertEquals(Entry.Notice("compression failed: busy"), chat.state.value.entries.single())
    }

    @Test
    fun reopeningTheChatMidTurnReleasesTheOldTurn() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hello")
        core.emit("message.start", "s1")

        chat.open()  // e.g. a new model chosen while a reply was streaming
        core.emit("message.complete", "s1", """{"text":"late"}""")  // the old session's end no longer reaches the chat

        assertEquals(listOf("begin:s1", "end:s1"), turns)
        assertFalse(chat.state.value.running)
    }

    @Test
    fun eventsOfOtherSessionsDoNotReachTheChat() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        core.emit("message.start", "s2")
        core.emit("message.delta", "s2", """{"text":"elsewhere"}""")

        assertEquals(Chat(), chat.state.value)
        assertEquals(emptyList<String>(), turns)
    }

    @Test
    fun aPromptTheCoreRefusesBecomesANotice() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { throw CoreError(4009, "session busy") }

        chat.send("hello")

        assertEquals(listOf(Entry.User("hello"), Entry.Notice("session busy")), chat.state.value.entries)
    }

    @Test
    fun reopeningStartsANewSessionWithAnEmptyTranscript() = runTest(UnconfinedTestDispatcher()) {
        var created = 0
        val fresh = FakeCoreGateway { method, _ ->
            when (method) {
                "session.create" -> """{"session_id":"s${++created}","stored_session_id":"db","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                else -> submit()
            }
        }
        val chat = ChatSession(fresh, host, backgroundScope, keepSecret = { _, _ -> }).apply { open() }
        chat.send("hello")

        chat.open()

        assertEquals("s2", chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)
    }

    @Test
    fun anApprovalForThisChatWaitsForTheUsersChoiceAndIsAnsweredWithIt() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        core.ask("srq-1", "approval", """{"session_id":"s1","request_id":"r1","command":"rm -rf build","choices":["once","session","deny"]}""")
        val asked = chat.state.value.prompts.single() as Prompt.Approval
        chat.approve(asked, ApprovalChoice.SESSION)

        assertEquals(listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.DENY), asked.choices)
        assertEquals(listOf("srq-1" to """{"choice":"session"}"""), core.settled)
        assertEquals(emptyList<Prompt>(), chat.state.value.prompts)
    }

    @Test
    fun clarifyIsAnsweredPerQuestionWithMultiSelectAsAJsonList() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.ask("srq-2", "clarify", """{"session_id":"s1","questions":[{"qid":"q1","question":"Name?"},
            {"qid":"q2","question":"Pick","choices":["a","b","c"],"multi_select":true}]}""")

        chat.clarify(chat.state.value.prompts.single() as Prompt.Clarify, mapOf("q1" to "Delta", "q2" to multiSelect(listOf("a", "c"))))

        val (id, sent) = core.settled.single()
        val answers = Json.parseToJsonElement(sent).jsonObject.getValue("answers").jsonObject
        assertEquals(listOf("srq-2", "Delta", """["a","c"]"""),
                     listOf(id, answers.getValue("q1").jsonPrimitive.content, answers.getValue("q2").jsonPrimitive.content))
    }

    @Test
    fun aSecretGoesToTheKeystoreAndNeverBackToTheCore() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.ask("srq-3", "secret", """{"session_id":"s1","env_var":"TAVILY_API_KEY","prompt":"Tavily key"}""")
        val value = "tvly-123".toByteArray()

        chat.secret(chat.state.value.prompts.single() as Prompt.Secret, value)

        assertEquals(mapOf("TAVILY_API_KEY" to "tvly-123"), kept)
        // An answered value would be written to .env by the core; the keystore source supplies it instead.
        assertEquals(listOf("srq-3" to """{"value":""}"""), core.settled)
        assertArrayEquals(ByteArray(8), value)
    }

    @Test
    fun aSecretTheKeystoreRefusesIsSaidAndTheRequestDeclined() = runTest(UnconfinedTestDispatcher()) {
        keepSecret = { _, _ -> throw IllegalArgumentException("not an environment variable name: bad-name") }
        val chat = openChat().apply { open() }
        core.ask("srq-4", "secret", """{"session_id":"s1","env_var":"bad-name","prompt":"key"}""")

        chat.secret(chat.state.value.prompts.single() as Prompt.Secret, "x".toByteArray())

        assertEquals("srq-4", core.settled.single().first)
        assertTrue(core.settled.single().second.startsWith("declined"))
        assertTrue(chat.state.value.entries.last() is Entry.Notice)
    }

    @Test
    fun aKeystoreFailureOnASecretIsSaidByItsKindAndTheRequestDeclined() = runTest(UnconfinedTestDispatcher()) {
        keepSecret = { _, _ -> throw IllegalStateException("Keystore operation failed: sk-not-for-the-screen") }
        val chat = openChat().apply { open() }
        core.ask("srq-5", "secret", """{"session_id":"s1","env_var":"GOOD_NAME","prompt":"key"}""")

        chat.secret(chat.state.value.prompts.single() as Prompt.Secret, "x".toByteArray())

        assertTrue(core.settled.single().second.startsWith("declined"))
        assertEquals(Entry.Notice("Something went wrong (IllegalStateException)."), chat.state.value.entries.last())
    }

    @Test
    fun requestsTheAppCannotShowAreDeclinedAtOnce() = runTest(UnconfinedTestDispatcher()) {
        openChat().open()

        core.ask("srq-5", "sudo", """{"session_id":"s1","command":"apt install"}""")
        core.ask("srq-6", "approval", """{"session_id":"other","request_id":"r9","command":"ls"}""")

        assertEquals(listOf("srq-5", "srq-6"), core.settled.map { it.first })
        assertTrue(core.settled.all { it.second.startsWith("declined") })
    }

    @Test
    fun aQuestionTheCoreWithdrewGetsNoAnswerAndReopeningDeclinesTheRest() = runTest(UnconfinedTestDispatcher()) {
        var created = 0
        val fresh = FakeCoreGateway { method, _ ->
            if (method == "session.create") """{"session_id":"s${++created}","stored_session_id":"db","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
            else submit()
        }
        val chat = ChatSession(fresh, host, backgroundScope, keepSecret = { _, _ -> }).apply { open() }
        fresh.ask("srq-1", "approval", """{"session_id":"s1","request_id":"r1","command":"ls"}""")
        fresh.ask("srq-2", "clarify", """{"session_id":"s1","questions":[{"qid":"q1","question":"?"}]}""")
        val withdrawn = chat.state.value.prompts.first() as Prompt.Approval

        fresh.emit("request.cancel", "s1", """{"id":"srq-1","method":"approval","reason":"timeout"}""")
        chat.approve(withdrawn, ApprovalChoice.ONCE)
        chat.open()

        assertEquals(listOf("srq-2"), fresh.settled.map { it.first })
        assertTrue(fresh.settled.single().second.startsWith("declined"))
    }

    @Test
    fun aSendThatFailsOtherwiseThanByARefusalIsSaid() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { "not a reply the app can read" }

        chat.send("hello")

        assertTrue(chat.state.value.entries.last().let { it is Entry.Notice && it.text.startsWith("Something went wrong (") })
    }

    @Test
    fun underConcurrentEventsEveryShownQuestionCanStillBeAnswered() = runBlocking {
        // Questions and events arrive on different threads; a question shown on screen must keep its request.
        repeat(20) { round ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val fresh = FakeCoreGateway { method, _ ->
                if (method == "session.create") """{"session_id":"s1","stored_session_id":"db","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                else submit()
            }
            val chat = ChatSession(fresh, host, scope, keepSecret = { _, _ -> }).apply { open() }
            val asks = (1..50).map { n -> scope.launch { fresh.ask("srq-$round-$n", "clarify", """{"session_id":"s1","questions":[{"qid":"q","question":"$n?"}]}""") } }
            val events = (1..50).map { n -> scope.launch { fresh.emit("status.update", "s1", """{"kind":"status","text":"step $n"}""") } }
            (asks + events).joinAll()
            withTimeout(10_000) { while (chat.state.value.prompts.size < 50) delay(5) }

            chat.state.value.prompts.forEach { chat.clarify(it as Prompt.Clarify, mapOf("q" to "a")) }

            assertEquals("round $round", 50, fresh.settled.size)
            scope.cancel()
        }
    }

    @Test
    fun aSessionResumedInTheMiddleOfATurnComesBackRunningWithItsQuestions() = runTest(UnconfinedTestDispatcher()) {
        val fresh = FakeCoreGateway { method, _ ->
            when (method) {
                "session.create" -> """{"session_id":"s1","stored_session_id":"db1","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                "session.resume" -> """{"session_id":"s-live","message_count":1,"messages":[{"role":"user","text":"plan my week"}],
                    "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}},"running":true,
                    "inflight":{"assistant":"Monday: ","streaming":true,"user":"plan my week"},
                    "open_requests":[{"id":"srq-9","method":"clarify","params":{"session_id":"s-live","questions":[{"qid":"q1","question":"Gym days?"}]}}]}"""
                else -> error("unexpected $method")
            }
        }
        val chat = ChatSession(fresh, host, backgroundScope, keepSecret = { _, _ -> }, notifier = notifier).apply { open() }

        chat.resume("db-live")

        val shown = chat.state.value
        assertEquals("Monday: ", shown.streaming)  // running: Stop is offered, the reply goes on
        assertEquals(listOf("begin:s-live"), turns)
        assertEquals(listOf("pending:srq-9"), notices)
        chat.clarify(shown.prompts.single() as Prompt.Clarify, mapOf("q1" to "Tue, Thu"))
        assertEquals("srq-9", fresh.settled.single().first)
        fresh.emit("message.complete", "s-live", """{"text":"Monday: rest."}""")
        assertEquals(listOf("begin:s-live", "end:s-live"), turns)
    }

    @Test
    fun resumingTheChatsOwnSessionKeepsItsQuestions() = runTest(UnconfinedTestDispatcher()) {
        val fresh = FakeCoreGateway { method, _ ->
            when (method) {
                "session.create" -> """{"session_id":"s1","stored_session_id":"db1","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                "session.resume" -> """{"session_id":"s1","message_count":1,"messages":[{"role":"user","text":"plan"}],
                    "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}},"running":true,"inflight":{"assistant":""},
                    "open_requests":[{"id":"srq-1","method":"clarify","params":{"session_id":"s1","questions":[{"qid":"q1","question":"Which day?"}]}}]}"""
                else -> error("unexpected $method")
            }
        }
        val chat = ChatSession(fresh, host, backgroundScope, keepSecret = { _, _ -> }).apply { open() }
        fresh.ask("srq-1", "clarify", """{"session_id":"s1","questions":[{"qid":"q1","question":"Which day?"}]}""")

        chat.resume("db1")  // History → the conversation already on screen

        assertEquals(emptyList<Pair<String, String>>(), fresh.settled)  // nothing declined behind the user's back
        chat.clarify(chat.state.value.prompts.single() as Prompt.Clarify, mapOf("q1" to "Tue"))
        assertEquals(listOf("srq-1"), fresh.settled.map { it.first })
    }

    @Test
    fun aQuestionNewerThanTheResumeSnapshotOfTheSameSessionIsKept() = runTest(UnconfinedTestDispatcher()) {
        val fresh = FakeCoreGateway { method, _ ->
            when (method) {
                "session.create" -> """{"session_id":"s1","stored_session_id":"db1","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                // The core's snapshot predates the approval the turn asked meanwhile: no open requests in it.
                "session.resume" -> """{"session_id":"s1","message_count":1,"messages":[{"role":"user","text":"clean up"}],
                    "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}},"running":true,"inflight":{"assistant":""},"open_requests":[]}"""
                else -> error("unexpected $method")
            }
        }
        val chat = ChatSession(fresh, host, backgroundScope, keepSecret = { _, _ -> }).apply { open() }
        fresh.ask("srq-7", "approval", """{"session_id":"s1","request_id":"r7","command":"rm -rf build"}""")

        chat.resume("db1")

        assertEquals(emptyList<Pair<String, String>>(), fresh.settled)  // not declined (an approval declined = denied)
        chat.approve(chat.state.value.prompts.single() as Prompt.Approval, ApprovalChoice.ONCE)
        assertEquals(listOf("srq-7"), fresh.settled.map { it.first })
    }

    @Test
    fun aQuestionThatWaitsIsNotifiedUntilItIsAnswered() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        core.ask("srq-1", "approval", """{"session_id":"s1","request_id":"r1","command":"rm -rf build"}""")
        core.ask("srq-2", "clarify", """{"session_id":"s1","questions":[{"qid":"q1","question":"Which branch?"}]}""")
        chat.approve(chat.state.value.prompts.first() as Prompt.Approval, ApprovalChoice.ONCE)
        assertEquals(listOf("pending:srq-1", "pending:srq-2"), notices)  // one still waits

        chat.clarify(chat.state.value.prompts.single() as Prompt.Clarify, mapOf("q1" to "main"))

        assertEquals(listOf("pending:srq-1", "pending:srq-2", "settled"), notices)
    }

    @Test
    fun aQuestionTheCoreWithdrawsNoLongerWaits() = runTest(UnconfinedTestDispatcher()) {
        openChat().open()
        core.ask("srq-1", "approval", """{"session_id":"s1","request_id":"r1","command":"ls"}""")

        core.emit("request.cancel", "s1", """{"id":"srq-1","method":"approval","reason":"timeout"}""")

        assertEquals(listOf("pending:srq-1", "settled"), notices)
    }

    @Test
    fun anotherSessionsQuestionNotifiesNothing() = runTest(UnconfinedTestDispatcher()) {
        openChat().open()

        core.ask("srq-6", "approval", """{"session_id":"other","request_id":"r9","command":"ls"}""")

        assertEquals(emptyList<String>(), notices)
    }

    @Test
    fun aFinishedTurnIsNotifiedWithItsReply() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("summarize")

        core.emit("message.start", "s1")
        core.emit("message.delta", "s1", """{"text":"Three"}""")
        assertEquals(emptyList<String>(), notices)  // nothing while it runs
        core.emit("message.complete", "s1", """{"text":"Three meetings today."}""")

        assertEquals(listOf("finished:Three meetings today."), notices)
    }

    @Test
    fun movingToAnotherSessionSettlesItsQuestions() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.ask("srq-1", "approval", """{"session_id":"s1","request_id":"r1","command":"ls"}""")

        chat.open()

        assertEquals(listOf("pending:srq-1", "settled"), notices)
    }

    @Test
    fun aChatClosesTheIdleSessionItLeaves() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", "{}")  // its agent is built

        chat.resume("db-old")

        assertEquals(listOf("s1"), closed())
        assertEquals("s-resumed", chat.sessionId)
        assertEquals(listOf(Entry.User("what's on my calendar?"), Entry.Reply("Two meetings.", TurnStatus.COMPLETE)), chat.state.value.entries)
    }

    @Test
    fun aLeftSessionStillInATurnStaysOpen() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", "{}")
        chat.send("hello")
        core.emit("message.start", "s1")

        chat.open()

        assertEquals(emptyList<String>(), closed())  // the core keeps running it
        assertEquals(listOf("begin:s1", "end:s1"), turns)  // the app lets the turn go
        assertEquals("s2", chat.sessionId)
    }

    @Test
    fun resumingTheSessionTheChatIsInClosesNothing() = runTest(UnconfinedTestDispatcher()) {
        var created = 0
        val fresh = FakeCoreGateway { method, _ ->
            when (method) {
                "session.create" -> """{"session_id":"s${++created}","stored_session_id":"db$created","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                // The core hands back the live session it already has for this key.
                "session.resume" -> """{"session_id":"s1","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                "session.close" -> """{"closed":true}"""
                else -> submit()
            }
        }
        val chat = ChatSession(fresh, host, backgroundScope, keepSecret = { _, _ -> }).apply { open() }

        chat.resume("db1")  // before s1's build has settled: a close decided now would fire whenever it settles
        assertEquals("s1", chat.sessionId)
        chat.send("hello")
        fresh.emit("message.start", "s1")
        chat.open()  // s1 left while its turn runs: it runs on
        fresh.emit("session.info", "s1", "{}")

        assertEquals(emptyList<String>(), fresh.calls.filter { it.first == "session.close" }.map { it.second.toString() })
    }

    @Test
    fun aSessionIsClosedOnlyOnceItsBuildHasSettled() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }  // s1's agent is still being built

        chat.open()
        assertEquals(emptyList<String>(), closed())  // a close now would leave the build's frame no transport

        core.emit("error", "s1", """{"message":"Hermes could not start the assistant"}""")

        assertEquals(listOf("s1"), closed())
        assertEquals("s2", chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)  // s1's error, not the chat's
    }

    @Test
    fun aSessionTheChatCameBackToIsNotClosed() = runTest(UnconfinedTestDispatcher()) {
        var created = 0
        val fresh = FakeCoreGateway { method, _ ->
            when (method) {
                "session.create" -> """{"session_id":"s${++created}","stored_session_id":"db$created","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                "session.resume" -> """{"session_id":"s1","message_count":0,"messages":[],"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
                "session.close" -> """{"closed":true}"""
                else -> error("unexpected $method")
            }
        }
        val chat = ChatSession(fresh, host, backgroundScope, keepSecret = { _, _ -> }).apply { open() }
        chat.resume("db1")  // the session the chat is in: nothing to close
        chat.open()  // s1 left before its build settled: its close waits
        chat.resume("db1")  // back on s1, which the core still has live

        fresh.emit("session.info", "s1", "{}")

        assertEquals(emptyList<String>(), fresh.calls.filter { it.first == "session.close" }.map { it.first })
        assertEquals("s1", chat.sessionId)
    }

    @Test
    fun aFailedCloseLeavesTheChatAsItWas() = runTest(UnconfinedTestDispatcher()) {
        close = { throw CoreError(4001, "session not found") }
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", "{}")

        chat.open()  // the close fails in the background; runTest fails on an uncaught exception there

        assertEquals(listOf("s1"), closed())
        assertEquals("s2", chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)
    }

    @Test
    fun deletingTheChatsOwnSessionMovesOffItFirst() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", "{}")

        chat.delete("db1")

        // The core refuses to delete a live session: a new chat first, the old session closed, then its row deleted.
        assertEquals(listOf("session.create" to null, "session.close" to "s1", "session.delete" to "db1"), callsAfter(1))
        assertEquals("s2", chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)

        chat.resume("db-old")  // a resumed session goes by the stored key the core answered, its compression tip
        core.emit("session.info", "s-resumed", "{}")
        val mark = core.calls.size
        chat.delete("db-old-tip")

        assertEquals(listOf("session.create" to null, "session.close" to "s-resumed", "session.delete" to "db-old-tip"), callsAfter(mark))
    }

    @Test
    fun theChatsOwnSessionIsNotDeletedWhileItsTurnRuns() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val mark = core.calls.size

        val refused = runCatching { chat.delete("db1") }.exceptionOrNull()

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.STILL_WORKING, refused?.message)
        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))
        assertEquals("s1", chat.sessionId)
        assertTrue(chat.state.value.running)
    }

    @Test
    fun anotherSessionIsDeletedByItsKeyAndTheChatStays() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        chat.delete("db-old")

        assertEquals(listOf("session.delete" to "db-old"), callsAfter(1))
        assertEquals("s1", chat.sessionId)
    }

    @Test
    fun theStoredKeyFollowsTheCoreAfterACompression() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", """{"stored_session_id":"db1b"}""")  // a compression rotated the key

        chat.delete("db1b")

        assertEquals(listOf("session.create" to null, "session.close" to "s1", "session.delete" to "db1b"), callsAfter(1))

        core.emit("session.info", "s2", """{"stored_session_id":""}""")  // no key named: the one the chat has stays
        var mark = core.calls.size
        chat.delete("db2")
        assertEquals(listOf("session.create" to null, "session.close" to "s2", "session.delete" to "db2"), callsAfter(mark))

        core.emit("session.info", "s9", """{"stored_session_id":"db9"}""")  // another session's key is not the chat's
        mark = core.calls.size
        chat.delete("db9")
        assertEquals(listOf("session.delete" to "db9"), callsAfter(mark))
        assertEquals("s3", chat.sessionId)
    }

    @Test
    fun aRowAlreadyGoneCountsAsDeleted() = runTest(UnconfinedTestDispatcher()) {
        delete = { throw CoreError(4007, "session not found") }
        val chat = openChat().apply { open() }

        chat.delete("db-old")  // returns: the row is gone, as asked

        assertEquals(listOf("session.delete" to "db-old"), callsAfter(1))
    }

    @Test
    fun aLeftSessionStillRunningIsRefusedInTheAppsWords() = runTest(UnconfinedTestDispatcher()) {
        delete = { throw CoreError(4023, "cannot delete an active session") }
        val chat = openChat().apply { open() }

        val refused = runCatching { chat.delete("db-old") }.exceptionOrNull()
        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.STILL_WORKING, refused?.message)

        delete = { throw CoreError(5036, "delete failed: disk") }
        val failed = runCatching { chat.delete("db-old") }.exceptionOrNull()
        assertEquals(5036, (failed as? CoreError)?.code)
    }

    @Test
    fun aChatStartsInTheCoresMostRecentConversation() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        val chat = openChat()

        chat.start()

        assertEquals(listOf("config.get" to null, "session.most_recent" to null, "session.resume" to "db-old"), callsAfter(0))
        assertEquals("full", core.calls.single { it.first == "config.get" }.second.getValue("key").jsonPrimitive.content)
        assertEquals("android", core.calls.single { it.first == "session.resume" }.second.getValue("source").jsonPrimitive.content)
        assertEquals("s-resumed", chat.sessionId)
        assertEquals(listOf(Entry.User("what's on my calendar?"), Entry.Reply("Two meetings.", TurnStatus.COMPLETE)), chat.state.value.entries)
    }

    @Test
    fun aChatStartsNewWhenTheCoreHasNoRecentConversation() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat()

        chat.start()

        assertEquals("session.create", core.calls.last().first)
        assertFalse(core.calls.any { it.first == "session.resume" })
        assertEquals("s1", chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)
    }

    @Test
    fun aChatStartsNewWhenTheCoreSaysNotToReopen() = runTest(UnconfinedTestDispatcher()) {
        config = { """{"config":{"display":{"resume_last_session":false}}}""" }
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        val chat = openChat()

        chat.start()

        assertEquals(listOf("config.get", "session.create"), callsAfter(0).map { it.first })  // the core is not even asked
        assertEquals("s1", chat.sessionId)
    }

    @Test
    fun anUnsetOrUnreadableSettingReopens() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        val answers: List<() -> String> = listOf(
            { """{"config":{}}""" },                                             // no display section: the default
            { """{"config":{"display":{"resume_last_session":"false"}}}""" },    // a string, not false
            { throw CoreError(4002, "unknown config key: full") },               // unreadable (D1)
        )
        for ((i, answer) in answers.withIndex()) {
            config = answer
            val mark = core.calls.size
            val chat = openChat()

            chat.start()

            assertEquals("answer $i", listOf("config.get", "session.most_recent", "session.resume"), callsAfter(mark).map { it.first })
            assertEquals("answer $i", "s-resumed", chat.sessionId)
        }
    }

    @Test
    fun aLastConversationThatCannotBeResumedLeavesANewChat() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        resume = { throw CoreError(4007, "session not found") }
        val chat = openChat()

        chat.start()

        assertEquals(listOf("config.get", "session.most_recent", "session.resume", "session.create"), callsAfter(0).map { it.first })
        assertEquals("s1", chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)  // quietly: no notice
        assertEquals(emptyList<String>(), closed())
    }

    @Test
    fun aCoreWithoutMostRecentStartsNew() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { throw CoreError(-32601, "method not found: session.most_recent") }
        val chat = openChat()

        chat.start()

        assertEquals(listOf("config.get", "session.most_recent", "session.create"), callsAfter(0).map { it.first })
        assertEquals("s1", chat.sessionId)
    }

    @Test
    fun aSentPromptLearnsItsRowIdFromTheCore() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }

        chat.send("hello")

        assertEquals(listOf(Entry.User("hello", 41)), chat.state.value.entries)
    }

    @Test
    fun retryRunsTheLastPromptAgainCuttingBeforeItsRow() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("hello")
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"hi"}""")
        submit = { """{"status":"streaming","user_row_id":52}""" }
        val mark = core.calls.size

        chat.retry()

        assertEquals(listOf("prompt.submit"), callsAfter(mark).map { it.first })  // the id was known: no history call
        val sent = lastSubmit()
        assertEquals("s1", sent.getValue("session_id").jsonPrimitive.content)
        assertEquals("hello", sent.getValue("text").jsonPrimitive.content)
        assertEquals("41", sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_truncate").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_empty_truncate").jsonPrimitive.content)
        assertFalse("never a deep cut (D7)", "confirm_deep_truncate" in sent)
        assertEquals(listOf(Entry.User("hello", 52)), chat.state.value.entries)
    }

    @Test
    fun aResumedConversationIsRetriedByTheRowIdsItCameBackWith() = runTest(UnconfinedTestDispatcher()) {
        resume = { """{"session_id":"s-resumed","message_count":4,"session_key":"db-old-tip",
                     "messages":[{"role":"user","text":"what's on my calendar?","row_id":10},{"role":"assistant","text":"Two meetings.","row_id":11},
                                 {"role":"user","text":"and tomorrow?","row_id":12},{"role":"assistant","text":"One.","row_id":13}],
                     "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"kimi-k2"}}""" }
        val chat = openChat().apply { open() }
        chat.resume("db-old")
        submit = { """{"status":"streaming","user_row_id":61}""" }

        chat.retry()

        val sent = lastSubmit()
        assertEquals("s-resumed" to "12", sent.getValue("session_id").jsonPrimitive.content to sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals(listOf(Entry.User("what's on my calendar?", 10), Entry.Reply("Two meetings.", TurnStatus.COMPLETE),
                            Entry.User("and tomorrow?", 61)), chat.state.value.entries)
    }

    @Test
    fun aPromptWithoutARowIdIsFoundInTheCoresHistory() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hello")
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"hi"}""")
        history = { """{"count":2,"messages":[{"role":"user","text":"hello","row_id":5},{"role":"assistant","text":"hi","row_id":6}]}""" }
        val mark = core.calls.size

        chat.retry()

        assertEquals(listOf("session.history" to "s1", "prompt.submit" to "s1"), callsAfter(mark))
        assertEquals("5", lastSubmit().getValue("truncate_before_row_id").jsonPrimitive.content)
    }

    @Test
    fun aPromptTheHistoryCannotPinIsSentAgainWithoutACut() = runTest(UnconfinedTestDispatcher()) {
        val answers = listOf(
            """{"count":2,"messages":[{"role":"user","text":"ok","row_id":3},{"role":"user","text":"ok","row_id":7}]}""",  // two
            """{"count":1,"messages":[{"role":"assistant","text":"done","row_id":4}]}""",                                    // none
            """{"count":1,"messages":[{"role":"user","text":"ok","row_id":3,"display_kind":"skill_invocation"}]}""",         // a skill row
        )
        for ((i, answer) in answers.withIndex()) {
            val chat = openChat().apply { open() }
            val session = chat.sessionId
            chat.send("ok")
            core.emit("message.start", session)
            core.emit("message.complete", session, """{"text":"done"}""")
            history = { answer }

            chat.retry()

            val sent = lastSubmit()
            assertFalse("answer $i: no cut", "truncate_before_row_id" in sent)
            assertFalse("answer $i: no consent", "confirm_truncate" in sent)
            assertEquals("answer $i: appended (D1)", listOf(Entry.User("ok"), Entry.Reply("done", TurnStatus.COMPLETE), Entry.User("ok")),
                         chat.state.value.entries)
        }
    }

    @Test
    fun aRefusedRetryShowsTheCoresTranscriptAndWhy() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("hello")
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"hi"}""")
        submit = { throw CoreError(4018, "target user message is no longer in session history") }
        history = { """{"count":2,"messages":[{"role":"user","text":"hello","row_id":41},{"role":"assistant","text":"hi there","row_id":42}]}""" }
        val mark = core.calls.size

        chat.retry()

        assertEquals(listOf("prompt.submit", "session.history"), callsAfter(mark).map { it.first })
        // The core's transcript, re-read: not the predicted [User("hello")].
        assertEquals(listOf(Entry.User("hello", 41), Entry.Reply("hi there", TurnStatus.COMPLETE),
                            Entry.Notice("target user message is no longer in session history")), chat.state.value.entries)
    }

    @Test
    fun aRefusedRetryWhoseHistoryCannotBeReadPutsBackWhatWasShown() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("hello")
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"hi"}""")
        submit = { throw CoreError(4018, "target user message is no longer in session history") }
        history = { throw CoreError(5000, "history unavailable") }

        chat.retry()

        assertEquals(listOf(Entry.User("hello", 41), Entry.Reply("hi", TurnStatus.COMPLETE),
                            Entry.Notice("target user message is no longer in session history")), chat.state.value.entries)
    }

    @Test
    fun retryAsksTheCoreNothingWhileATurnRunsOrBeforeAnAnswer() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        suspend fun unchanged(step: String) {
            val (calls, entries) = core.calls.size to chat.state.value.entries
            chat.retry()
            assertEquals(step, calls, core.calls.size)
            assertEquals(step, entries, chat.state.value.entries)
        }

        unchanged("a fresh chat")
        chat.send("hello")
        unchanged("no answer yet")
        core.emit("message.start", "s1")
        unchanged("running")
    }
}
