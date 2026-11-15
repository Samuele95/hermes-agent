package com.nousresearch.hermes.android.chat

import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.protocol.BackendContract
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionResumeParams
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertNotEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
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
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.Method
import kotlinx.coroutines.CompletableDeferred
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
    /** Called as the core answers `session.create`, before its reply: a test steps in during a switch. */
    private var created: () -> Unit = {}
    private var close: (JsonObject) -> String = { """{"closed":true}""" }
    private var delete: (String) -> String = { id -> """{"deleted":"$id"}""" }
    private var contract = """"desktop_contract":${BackendContract.BACKEND_CONTRACT}"""
    private var config: () -> String = { """{"config":{"display":{}}}""" }
    private var mostRecent: () -> String = { """{"session_id":null}""" }
    private var history: () -> String = { """{"count":0,"messages":[]}""" }
    private var compressReply: (JsonObject) -> String = { """{"status":"compressed","removed":0,"messages":[]}""" }
    private var breakdownReply: () -> String = { BREAKDOWN }
    private var title: (JsonObject) -> String = { p -> """{"title":${p.getValue("title")},"pending":false}""" }
    // The core continues a compressed conversation at its tip and answers that key (no top-level stored id).
    private var resume: () -> String = { """{"session_id":"s-resumed","message_count":2,"session_key":"db-old-tip",
                                   "messages":[{"role":"user","text":"what's on my calendar?"},{"role":"assistant","text":"Two meetings."}],
                                   "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"kimi-k2"}}""" }
    /** The core's `display.busy_input_mode`, as `config.get busy` answers it; and its answers to the two corrections. */
    private var busyWord = "interrupt"
    private var busy: () -> String = { """{"value":"$busyWord"}""" }
    private var steer: (JsonObject) -> String = { p -> """{"status":"queued","text":${p.getValue("text")}}""" }
    private var redirect: (JsonObject) -> String = { p -> """{"status":"redirected","text":${p.getValue("text")}}""" }
    private val core = FakeCoreGateway { method, params ->
        when (method) {
            "session.create" -> { created(); """{"session_id":"s${++sessions}","stored_session_id":"db$sessions","message_count":0,"messages":[],"info":{$contract}}""" }
            "prompt.submit" -> submit()
            "session.close" -> close(params)
            "session.delete" -> delete(params.getValue("session_id").jsonPrimitive.content)
            "session.interrupt" -> """{"status":"interrupted"}"""
            "session.list" -> """{"sessions":[{"id":"db-old","title":"Calendar","message_count":3,"started_at":1.0},
                                                 {"id":"db-empty","message_count":0,"started_at":2.0}]}"""
            "session.resume" -> resume()
            "session.most_recent" -> mostRecent()
            "config.get" -> if (params["key"]?.jsonPrimitive?.content == "busy") busy() else config()
            "session.steer" -> steer(params)
            "session.redirect" -> redirect(params)
            "session.history" -> history()
            "session.compress" -> compressReply(params)
            "session.context_breakdown" -> breakdownReply()
            "session.title" -> title(params)
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

    /** `_resume_lazy`'s reply: an agent-less read-only mount of a stored row, no transcript. */
    private val sideResume = { """{"session_id":"s-side","message_count":0,"messages":[],"session_key":"db-old",
                                   "running":false,"info":{"lazy":true}}""" }

    /** The params of the last call of [method]. */
    private fun lastOf(method: String) = core.calls.last { it.first == method }.second

    private fun runtime(id: String) = RuntimeSessionId(id)
    private fun stored(id: String) = StoredSessionId(id)

    /** The app's observation of the always-on gateway, and how often the chat took it. */
    private var seen: () -> GatewaySeen = { GatewaySeen(GatewayStatus.RUNNING) }
    private var observed = 0

    /** Whether the gateway's scheduler still owns a run, as the app's `cron_run_owned` answers; and the ids asked. */
    private var owned: suspend (StoredSessionId) -> Boolean? = { null }
    private val ownedAsked = mutableListOf<String>()

    /** The chat's queues as its store kept them, by stored key (the file adapter's stand-in). */
    private val savedQueues = mutableMapOf<StoredSessionId, QueueUi>()

    private fun TestScope.openChat() =
        ChatSession(core, host, backgroundScope, keepSecret = { name, value -> keepSecret(name, value) }, notifier = notifier,
                    gatewaySeen = { observed++; seen() }, runOwned = { ownedAsked += it.value; owned(it) },
                    queues = QueueStore.inMemory(savedQueues))

    /** The agent schedules a job in [session] ([action] of the job tool, answered `success`), the core's own warning included. */
    private suspend fun scheduled(session: String, id: String, action: String = "create") {
        core.emit("tool.start", session, """{"tool_id":"$id","name":"cronjob_manage"}""")
        core.emit("tool.complete", session, """{"tool_id":"$id","name":"cronjob_manage","summary":"$SCHEDULED",
            "args":{"action":"$action","schedule":"every 2m","prompt":"Say tick."},
            "result":{"success":true,"job_id":"ab12","gateway_running":false,
                      "warning":"The job will NOT fire until the gateway is started (hermes gateway install / hermes gateway start)."}}""")
    }

    private fun ChatSession.jobNotices() = state.value.entries.filterIsInstance<Entry.Notice>()

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
        assertEquals(listOf(Entry.User("hello", stableId = 0)), chat.state.value.entries)
    }

    @Test
    fun aChatKnowsTheModelItsSessionRuns() = runTest(UnconfinedTestDispatcher()) {
        contract = """"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"deepseek-chat","provider":"deepseek""""
        val chat = openChat().apply { open() }
        assertEquals("deepseek-chat", chat.state.value.model)

        chat.resume(stored("db-old"))  // a stored session runs the model it was resumed with

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

        chat.resume(stored("db-old"))

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

        chat.resume(stored("db-old"))
        chat.send("and tomorrow?")

        assertEquals(listOf(Entry.User("what's on my calendar?", stableId = 0), Entry.Reply("Two meetings.", TurnStatus.COMPLETE, stableId = 1),
                            Entry.User("and tomorrow?", stableId = 2)), chat.state.value.entries)
        assertEquals("s-resumed", core.calls.last().second.getValue("session_id").jsonPrimitive.content)
        val resumed = core.calls.single { it.first == "session.resume" }.second
        assertEquals("db-old", resumed.getValue("session_id").jsonPrimitive.content)
    }

    @Test
    fun aResumedOrNewTranscriptTakesNoIdTheChatShowedBefore() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hello")                                         // 0
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"hi"}""")   // 1

        chat.resume(stored("db-old"))                              // the fixture's two stored rows

        assertEquals(listOf(2L, 3L), chat.state.value.entries.map { it.stableId })
        chat.open()                                                // a new, empty chat: the count goes on (V5)
        chat.send("again")
        assertEquals(listOf(4L), chat.state.value.entries.map { it.stableId })
    }

    @Test
    fun resumingMidTurnReleasesTheRunningTurn() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hello")
        core.emit("message.start", "s1")

        chat.resume(stored("db-old"))

        assertEquals(listOf("begin:s1", "end:s1"), turns)
        assertFalse(chat.state.value.running)
    }

    @Test
    fun anErrorOutsideATurnIsOnlyANotice() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        core.emit("error", "s1", """{"message":"compression failed: busy"}""")

        assertEquals(emptyList<String>(), turns)
        assertEquals(Entry.Notice("compression failed: busy", stableId = 0), chat.state.value.entries.single())
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
    fun aSecondSendBeforeTheTurnStartsSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        fun submits() = core.calls.count { it.first == "prompt.submit" }

        chat.send("hello")
        chat.send("hello")  // a second tap before the turn started (V5)
        assertEquals(1, submits())
        assertEquals(listOf(Entry.User("hello", stableId = 0)), chat.state.value.entries)

        core.emit("message.start", "s1")
        chat.send("again")
        assertEquals(1, submits())

        core.emit("message.complete", "s1", """{"text":"hi"}""")
        chat.send("again")
        assertEquals(2, submits())
        assertEquals("again", lastSubmit().getValue("text").jsonPrimitive.content)
    }

    @Test
    fun stopBeforeTheTurnStartsCancelsIt() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("write an essay")

        chat.cancel()

        assertEquals("session.interrupt" to "s1", callsAfter(core.calls.size - 1).single())
        core.emit("error", "s1", """{"message":"Turn cancelled before the agent was ready"}""")
        assertFalse(chat.state.value.busy)
        assertEquals(Entry.Notice("Turn cancelled before the agent was ready", stableId = 1), chat.state.value.entries.last())
        assertEquals(emptyList<String>(), turns)  // no turn began

        // The chat moved on from the busy session: Stop is about the chat's own, idle one.
        val other = openChat().apply { open() }
        other.send("hi")
        other.open()
        val mark = core.calls.size
        other.cancel()
        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))
    }

    @Test
    fun aPromptTheCoreRefusesBecomesANotice() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { throw CoreError(4009, "session busy") }

        chat.send("hello")

        assertEquals(listOf(Entry.User("hello", stableId = 0), Entry.Notice("session busy", stableId = 1)), chat.state.value.entries)
        assertFalse(chat.state.value.busy)  // Send comes back
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

        assertEquals(runtime("s2"), chat.sessionId)
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
        assertEquals(Entry.Notice("Something went wrong (IllegalStateException).", stableId = 0), chat.state.value.entries.last())
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
        assertFalse(chat.state.value.busy)  // Send comes back
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

        chat.resume(stored("db-live"))

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

        chat.resume(stored("db1"))  // History → the conversation already on screen

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

        chat.resume(stored("db1"))

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

        chat.resume(stored("db-old"))

        assertEquals(listOf("s1"), closed())
        assertEquals(runtime("s-resumed"), chat.sessionId)
        assertEquals(listOf(Entry.User("what's on my calendar?", stableId = 0), Entry.Reply("Two meetings.", TurnStatus.COMPLETE, stableId = 1)),
                     chat.state.value.entries)
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
        assertEquals(runtime("s2"), chat.sessionId)
    }

    @Test
    fun aConversationLeftBeforeItsTurnStartsStaysOpen() = runTest(UnconfinedTestDispatcher()) {
        val waysOut: List<suspend (ChatSession) -> Unit> = listOf({ it.open() }, { it.resume(stored("db-old")) })
        for ((i, wayOut) in waysOut.withIndex()) {
            val chat = openChat().apply { open() }
            val left = chat.sessionId
            core.emit("session.info", left.value, "{}")  // its build settled: an idle session would be closed now
            chat.send("hello")  // prompt.submit answered, no message.start yet

            wayOut(chat)

            assertEquals("way out $i: the turn about to start runs on (V1)", emptyList<String>(), closed())
            core.emit("message.start", left.value)
            core.emit("message.complete", left.value, """{"text":"hi"}""")
            assertEquals("way out $i: its turn ran in the core", emptyList<String>(), closed())
            assertTrue("way out $i", chat.sessionId != left)
        }
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

        chat.resume(stored("db1"))  // before s1's build has settled: a close decided now would fire whenever it settles
        assertEquals(runtime("s1"), chat.sessionId)
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
        assertEquals(runtime("s2"), chat.sessionId)
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
        chat.resume(stored("db1"))  // the session the chat is in: nothing to close
        chat.open()  // s1 left before its build settled: its close waits
        chat.resume(stored("db1"))  // back on s1, which the core still has live

        fresh.emit("session.info", "s1", "{}")

        assertEquals(emptyList<String>(), fresh.calls.filter { it.first == "session.close" }.map { it.first })
        assertEquals(runtime("s1"), chat.sessionId)
    }

    @Test
    fun aFailedCloseLeavesTheChatAsItWas() = runTest(UnconfinedTestDispatcher()) {
        close = { throw CoreError(4001, "session not found") }
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", "{}")

        chat.open()  // the close fails in the background; runTest fails on an uncaught exception there

        assertEquals(listOf("s1"), closed())
        assertEquals(runtime("s2"), chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)
    }

    @Test
    fun deletingTheChatsOwnSessionMovesOffItFirst() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", "{}")

        chat.delete(stored("db1"))

        // The core refuses to delete a live session: a new chat first, the old session closed, then its row deleted.
        assertEquals(listOf("session.create" to null, "session.close" to "s1", "session.delete" to "db1"), callsAfter(1))
        assertEquals(runtime("s2"), chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)

        chat.resume(stored("db-old"))  // a resumed session goes by the stored key the core answered, its compression tip
        core.emit("session.info", "s-resumed", "{}")
        val mark = core.calls.size
        chat.delete(stored("db-old-tip"))

        assertEquals(listOf("session.create" to null, "session.close" to "s-resumed", "session.delete" to "db-old-tip"), callsAfter(mark))
    }

    @Test
    fun theChatsOwnSessionIsNotDeletedWhileItsTurnRuns() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val mark = core.calls.size

        val refused = runCatching { chat.delete(stored("db1")) }.exceptionOrNull()

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.STILL_WORKING, refused?.message)
        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))
        assertEquals(runtime("s1"), chat.sessionId)
        assertTrue(chat.state.value.running)
    }

    @Test
    fun compressAndDeleteWaitForAPromptOnItsWay() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")  // no message.start yet
        val mark = core.calls.size

        val compressing = runCatching { chat.compress(null) }.exceptionOrNull()
        assertTrue("$compressing", compressing is SetupRefusal)
        assertEquals(ChatSession.STILL_REPLYING, compressing?.message)

        val deleting = runCatching { chat.delete(stored("db1")) }.exceptionOrNull()
        assertTrue("$deleting", deleting is SetupRefusal)
        assertEquals(ChatSession.STILL_WORKING, deleting?.message)

        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))  // neither session.compress nor session.create
        assertEquals(runtime("s1"), chat.sessionId)
    }

    @Test
    fun anotherSessionIsDeletedByItsKeyAndTheChatStays() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        chat.delete(stored("db-old"))

        assertEquals(listOf("session.delete" to "db-old"), callsAfter(1))
        assertEquals(runtime("s1"), chat.sessionId)
    }

    @Test
    fun theStoredKeyFollowsTheCoreAfterACompression() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", """{"stored_session_id":"db1b"}""")  // a compression rotated the key

        chat.delete(stored("db1b"))

        assertEquals(listOf("session.create" to null, "session.close" to "s1", "session.delete" to "db1b"), callsAfter(1))

        core.emit("session.info", "s2", """{"stored_session_id":""}""")  // no key named: the one the chat has stays
        var mark = core.calls.size
        chat.delete(stored("db2"))
        assertEquals(listOf("session.create" to null, "session.close" to "s2", "session.delete" to "db2"), callsAfter(mark))

        core.emit("session.info", "s9", """{"stored_session_id":"db9"}""")  // another session's key is not the chat's
        mark = core.calls.size
        chat.delete(stored("db9"))
        assertEquals(listOf("session.delete" to "db9"), callsAfter(mark))
        assertEquals(runtime("s3"), chat.sessionId)
    }

    @Test
    fun aRowAlreadyGoneCountsAsDeleted() = runTest(UnconfinedTestDispatcher()) {
        delete = { throw CoreError(4007, "session not found") }
        val chat = openChat().apply { open() }

        chat.delete(stored("db-old"))  // returns: the row is gone, as asked

        assertEquals(listOf("session.delete" to "db-old"), callsAfter(1))
    }

    @Test
    fun aLeftSessionStillRunningIsRefusedInTheAppsWords() = runTest(UnconfinedTestDispatcher()) {
        delete = { throw CoreError(4023, "cannot delete an active session") }
        val chat = openChat().apply { open() }

        val refused = runCatching { chat.delete(stored("db-old")) }.exceptionOrNull()
        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.STILL_WORKING, refused?.message)

        delete = { throw CoreError(5036, "delete failed: disk") }
        val failed = runCatching { chat.delete(stored("db-old")) }.exceptionOrNull()
        assertEquals(5036, (failed as? CoreError)?.code)
    }

    /** The core's reuse-live answer to `session.resume("db1")`: the live s1 back, no build, no new `session.info`. */
    private val backToS1 = { """{"session_id":"s1","message_count":0,"messages":[],"session_key":"db1","info":{$contract}}""" }

    @Test
    fun aConversationLeftComeBackToAndDeletedBeforeItSettledIsClosedOnce() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }  // s1, its build not yet announced
        chat.open()  // s2: s1 left, its close waits for the build
        resume = backToS1
        chat.resume(stored("db1"))  // back on s1 before it settled

        val deleting = launch { withTimeout(5.minutes) { chat.delete(stored("db1")) } }  // own: s3 opens, s1 left again
        core.emit("session.info", "s1", "{}")
        deleting.join()

        assertEquals(1, closed().count { it == "s1" })
        val closedAt = core.calls.indexOfFirst { it.first == "session.close" && it.second["session_id"]?.jsonPrimitive?.content == "s1" }
        val deletedAt = core.calls.indexOfFirst { it.first == "session.delete" && it.second["session_id"]?.jsonPrimitive?.content == "db1" }
        assertTrue("close at $closedAt, delete at $deletedAt", closedAt in 0 until deletedAt)
        assertEquals(runtime("s3"), chat.sessionId)
    }

    @Test
    fun aBuildThatNeverSettlesFailsTheDeleteInTheAppsWordsAndKeepsTheChatsPlace() = runTest(UnconfinedTestDispatcher()) {
        resume = backToS1
        val chat = openChat().apply { open() }  // s1, whose build never announces itself

        val refused = runCatching { withTimeout(5.minutes) { chat.delete(stored("db1")) } }.exceptionOrNull()

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.NOT_CLOSED, refused?.message)
        assertFalse(core.calls.any { it.first == "session.delete" })
        assertFalse("s1" in closed())
        assertEquals(runtime("s1"), chat.sessionId)  // back on it, as the desktop goes back to a row it could not delete

        // The body was woken by the bounded wait, from the scheduler: what an event wakes now runs once the body suspends.
        core.emit("session.info", "s2", "{}")
        delay(1)
        assertEquals(listOf("s2"), closed())  // the empty chat the delete opened is let go
        core.emit("session.info", "s1", "{}")
        delay(1)
        assertEquals(listOf("s2"), closed())  // the chat is on s1: nothing closes it
    }

    @Test
    fun aFailedCloseOfTheOwnRowKeepsTheChatsPlaceAndSaysSo() = runTest(UnconfinedTestDispatcher()) {
        resume = backToS1
        close = { throw IllegalStateException("bridge gone") }
        delete = { throw CoreError(4023, "cannot delete an active session") }  // the core still holds s1
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", "{}")

        val refused = runCatching { withTimeout(5.minutes) { chat.delete(stored("db1")) } }.exceptionOrNull()

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.NOT_CLOSED, refused?.message)
        assertNotEquals(ChatSession.STILL_WORKING, refused?.message)
        assertEquals(listOf("s1"), closed())  // it was tried
        assertFalse(core.calls.any { it.first == "session.delete" })
        assertEquals(runtime("s1"), chat.sessionId)
    }

    @Test
    fun deletingARowWhoseLeftSessionIsStillClosingWaitsForTheClose() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }  // s1, unsettled
        chat.open()  // s2: s1 left, its close pending
        val mark = core.calls.size

        val deleting = launch { withTimeout(5.minutes) { chat.delete(stored("db1")) } }
        assertFalse(callsAfter(mark).any { it.first == "session.delete" })

        core.emit("session.info", "s1", "{}")
        deleting.join()

        assertEquals(listOf("session.close" to "s1", "session.delete" to "db1"), callsAfter(mark))
        assertEquals(runtime("s2"), chat.sessionId)
    }

    @Test
    fun aSessionLeftMidTurnAndComeBackToIsClosedWhenLeftIdle() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", "{}")
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.open()  // s2: s1 left running, not closed
        core.emit("message.complete", "s1", """{"text":"hi"}""")  // another session's now

        resume = backToS1
        chat.resume(stored("db1"))  // reuse-live: no build, no new session.info
        chat.open()  // s3: s1 left idle

        assertTrue("${closed()}", "s1" in closed())
    }

    @Test
    fun aChatStartsInTheCoresMostRecentConversation() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        val chat = openChat()

        chat.start()

        assertEquals(listOf("config.get" to null, "session.most_recent" to null, "session.resume" to "db-old"), callsAfter(0))
        assertEquals("full", core.calls.single { it.first == "config.get" }.second.getValue("key").jsonPrimitive.content)
        assertEquals("android", core.calls.single { it.first == "session.resume" }.second.getValue("source").jsonPrimitive.content)
        assertEquals(runtime("s-resumed"), chat.sessionId)
        assertEquals(listOf(Entry.User("what's on my calendar?", stableId = 0), Entry.Reply("Two meetings.", TurnStatus.COMPLETE, stableId = 1)),
                     chat.state.value.entries)
    }

    @Test
    fun aChatStartsNewWhenTheCoreHasNoRecentConversation() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat()

        chat.start()

        assertEquals("session.create", core.calls.last().first)
        assertFalse(core.calls.any { it.first == "session.resume" })
        assertEquals(runtime("s1"), chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)
    }

    @Test
    fun aChatStartsNewWhenTheCoreSaysNotToReopen() = runTest(UnconfinedTestDispatcher()) {
        config = { """{"config":{"display":{"resume_last_session":false}}}""" }
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        val chat = openChat()

        chat.start()

        assertEquals(listOf("config.get", "session.create"), callsAfter(0).map { it.first })  // the core is not even asked
        assertEquals(runtime("s1"), chat.sessionId)
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
            assertEquals("answer $i", runtime("s-resumed"), chat.sessionId)
        }
    }

    @Test
    fun aLastConversationThatCannotBeResumedLeavesANewChat() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        resume = { throw CoreError(4007, "session not found") }
        val chat = openChat()

        chat.start()

        assertEquals(listOf("config.get", "session.most_recent", "session.resume", "session.create"), callsAfter(0).map { it.first })
        assertEquals(runtime("s1"), chat.sessionId)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)  // quietly: no notice
        assertEquals(emptyList<String>(), closed())
    }

    @Test
    fun aCoreWithoutMostRecentStartsNew() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { throw CoreError(-32601, "method not found: session.most_recent") }
        val chat = openChat()

        chat.start()

        assertEquals(listOf("config.get", "session.most_recent", "session.create"), callsAfter(0).map { it.first })
        assertEquals(runtime("s1"), chat.sessionId)
    }

    @Test
    fun aLastConversationTheAppCannotReadOrReachLeavesANewChat() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        // A stored row without its required role: the reply decodes nowhere, and the core said nothing wrong.
        val undecodable = { """{"session_id":"s-resumed","message_count":1,"messages":[{"kind":"user","text":"what's on my calendar?"}],"info":{}}""" }
        resume = undecodable
        assertTrue("fails as a decode, not as the core's refusal",
                   runCatching { core.call(Methods.SESSION_RESUME, SessionResumeParams(sessionId = "db-old", source = "android")) }
                       .exceptionOrNull() is SerializationException)
        val answers: List<() -> String> = listOf(undecodable, { throw IllegalStateException("bridge gone") })
        for ((i, answer) in answers.withIndex()) {
            resume = answer
            val mark = core.calls.size
            val chat = openChat()

            chat.start()

            assertEquals("answer $i", listOf("config.get", "session.most_recent", "session.resume", "session.create"),
                         callsAfter(mark).map { it.first })
            assertEquals("answer $i: the new chat", runtime("s$sessions"), chat.sessionId)
            assertEquals("answer $i: quietly", emptyList<Entry>(), chat.state.value.entries)
            assertFalse("answer $i: nothing to close", callsAfter(mark).any { it.first == "session.close" })
        }
    }

    @Test
    fun startStepsTheAppCannotReadTakeTheirFallback() = runTest(UnconfinedTestDispatcher()) {
        val reopened = listOf("config.get", "session.most_recent", "session.resume")
        val started = listOf("config.get", "session.most_recent", "session.create")
        val cases: List<Triple<String, () -> Unit, List<String>>> = listOf(
            Triple("config undecodable", { config = { """{"config":"display: {}"}""" } }, reopened),  // reopen-on (D1)
            Triple("config bridge broken", { config = { throw IllegalStateException("bridge gone") } }, reopened),
            Triple("recent undecodable", { mostRecent = { """{"session_id":["db-old"]}""" } }, started),  // nothing to reopen (D2)
            Triple("recent bridge broken", { mostRecent = { throw IllegalStateException("bridge gone") } }, started),
        )
        for ((name, breakIt, expected) in cases) {
            config = { """{"config":{"display":{}}}""" }
            mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
            breakIt()
            val mark = core.calls.size
            val chat = openChat()

            chat.start()

            assertEquals(name, expected, callsAfter(mark).map { it.first })
            assertEquals(name, runtime(if (expected == reopened) "s-resumed" else "s$sessions"), chat.sessionId)
        }
    }

    @Test
    fun aCancelledStartIsNotANewChat() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { """{"session_id":"db-old","title":"Calendar","source":"cli"}""" }
        resume = { throw CancellationException("the app's scope ended") }
        val chat = openChat()

        val failed = runCatching { chat.start() }.exceptionOrNull()

        assertTrue("cancellation is not a failure to fall back from: $failed", failed is CancellationException)
        assertFalse(core.calls.any { it.first == "session.create" })
    }

    @Test
    fun aSentPromptLearnsItsRowIdFromTheCore() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }

        chat.send("hello")

        assertEquals(listOf(Entry.User("hello", 41, 0)), chat.state.value.entries)
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
        assertEquals(listOf(Entry.User("hello", 52, stableId = 2)), chat.state.value.entries)  // the prompt again, a new row (0, 1 were cut)
    }

    @Test
    fun aResumedConversationIsRetriedByTheRowIdsItCameBackWith() = runTest(UnconfinedTestDispatcher()) {
        resume = { """{"session_id":"s-resumed","message_count":4,"session_key":"db-old-tip",
                     "messages":[{"role":"user","text":"what's on my calendar?","row_id":10},{"role":"assistant","text":"Two meetings.","row_id":11},
                                 {"role":"user","text":"and tomorrow?","row_id":12},{"role":"assistant","text":"One.","row_id":13}],
                     "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"kimi-k2"}}""" }
        val chat = openChat().apply { open() }
        chat.resume(stored("db-old"))
        submit = { """{"status":"streaming","user_row_id":61}""" }

        chat.retry()

        val sent = lastSubmit()
        assertEquals("s-resumed" to "12", sent.getValue("session_id").jsonPrimitive.content to sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        // The stored rows took 0-3; the retried prompt takes the next id, not the cut row's 2.
        assertEquals(listOf(Entry.User("what's on my calendar?", 10, 0), Entry.Reply("Two meetings.", TurnStatus.COMPLETE, stableId = 1),
                            Entry.User("and tomorrow?", 61, 4)), chat.state.value.entries)
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
            val session = chat.sessionId.value
            chat.send("ok")
            core.emit("message.start", session)
            core.emit("message.complete", session, """{"text":"done"}""")
            history = { answer }

            chat.retry()

            val sent = lastSubmit()
            assertFalse("answer $i: no cut", "truncate_before_row_id" in sent)
            assertFalse("answer $i: no consent", "confirm_truncate" in sent)
            assertEquals("answer $i: appended (D1)", listOf(Entry.User("ok", stableId = 0), Entry.Reply("done", TurnStatus.COMPLETE, stableId = 1), Entry.User("ok", stableId = 2)),
                         chat.state.value.entries)
        }
    }

    @Test
    fun aRefusedRetryGivesSendBack() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("hello")
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"hi"}""")
        submit = { throw CoreError(4018, "target user message is no longer in session history") }
        history = { """{"count":2,"messages":[{"role":"user","text":"hello","row_id":41},{"role":"assistant","text":"hi there","row_id":42}]}""" }

        chat.retry()

        assertFalse(chat.state.value.busy)  // retry's own sent() is undone with the resync
    }

    @Test
    fun aRetryWhoseHistoryCannotBeReadOrReachedIsSentAgainWithoutACut() = runTest(UnconfinedTestDispatcher()) {
        val answers: List<() -> String> = listOf(
            { """{"count":1,"messages":{"0":"ok"}}""" },  // an object where the list belongs: undecodable
            { throw IllegalStateException("bridge gone") },
        )
        for ((i, answer) in answers.withIndex()) {
            val chat = openChat().apply { open() }
            val session = chat.sessionId.value
            chat.send("ok")
            core.emit("message.start", session)
            core.emit("message.complete", session, """{"text":"done"}""")
            history = answer

            chat.retry()

            val sent = lastSubmit()
            assertFalse("answer $i: no cut", "truncate_before_row_id" in sent)
            assertFalse("answer $i: no consent", "confirm_truncate" in sent)
            assertEquals("answer $i: appended (B5-D1), no notice",
                         listOf(Entry.User("ok", stableId = 0), Entry.Reply("done", TurnStatus.COMPLETE, stableId = 1), Entry.User("ok", stableId = 2)), chat.state.value.entries)
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
        // Keyed on after the retried prompt's id 2 (V4): the re-read rows are 3, 4, the notice 5.
        assertEquals(listOf(Entry.User("hello", 41, 3), Entry.Reply("hi there", TurnStatus.COMPLETE, stableId = 4),
                            Entry.Notice("target user message is no longer in session history", stableId = 5)), chat.state.value.entries)
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

        // The rows put back are the same rows (0, 1); the notice takes the id after the retried prompt's 2.
        assertEquals(listOf(Entry.User("hello", 41, 0), Entry.Reply("hi", TurnStatus.COMPLETE, stableId = 1),
                            Entry.Notice("target user message is no longer in session history", stableId = 3)), chat.state.value.entries)
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

    @Test
    fun theBreakdownIsAskedOfTheChatsSession() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        val breakdown = chat.breakdown()

        assertEquals(32_000L, breakdown.contextMax)
        assertEquals("session.context_breakdown" to "s1", callsAfter(core.calls.size - 1).single())
    }

    @Test
    fun compressShowsTheCoresTranscriptAndFillWhileTheTurnServiceHoldsIt() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat()
        chat.resume(stored("db-old"))
        compressReply = { COMPRESSED }

        val said = chat.compress(" here 1 ")

        assertEquals("Compressed: 4 → 2 messages\nApprox request size: ~9,000 → ~2,100 tokens", said)
        assertEquals(listOf(Entry.User("latest", 9, 2), Entry.Reply("ok", TurnStatus.COMPLETE, stableId = 3)), chat.state.value.entries)
        assertEquals(ContextMeter(2_100, 8_192, 26, false), chat.state.value.meter)
        assertEquals(listOf("begin:compress:s-resumed#1", "end:compress:s-resumed#1"), turns)
        assertFalse(chat.state.value.compressing)
        val params = core.calls.single { it.first == "session.compress" }.second
        assertEquals("s-resumed" to "here 1", params.getValue("session_id").jsonPrimitive.content to params.getValue("focus_topic").jsonPrimitive.content)
    }

    @Test
    fun aCompressionsRotatedKeyIsTheOneTheChatDeletesBy() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat()
        chat.resume(stored("db-old"))
        core.emit("session.info", "s-resumed", "{}")  // its build settled: the chat may close it when it leaves
        compressReply = { COMPRESSED }
        chat.compress(" here 1 ")
        val mark = core.calls.size

        chat.delete(stored("db-rotated"))

        // The chat knew the rotated row was its own (D4): it left it first (invariant 4), then deleted it.
        assertEquals(listOf("session.create" to null, "session.close" to "s-resumed", "session.delete" to "db-rotated"), callsAfter(mark))
    }

    @Test
    fun aRefusedCompressionLeavesTheTranscriptAndEndsTheHold() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"hello"}""")
        compressReply = { throw CoreError(4009, "session busy — a /compress is running; run /compress when it finishes.") }

        val refused = try {
            chat.compress("  ")
            null
        } catch (error: CoreError) {
            error
        }

        assertEquals(4009, refused?.code)
        assertEquals(listOf(Entry.User("hi", stableId = 0), Entry.Reply("hello", TurnStatus.COMPLETE, stableId = 1)), chat.state.value.entries)
        assertEquals(listOf("begin:compress:s1#1", "end:compress:s1#1"), turns.takeLast(2))
        assertFalse(chat.state.value.compressing)
        assertFalse("a blank focus is none", core.calls.single { it.first == "session.compress" }.second.containsKey("focus_topic"))
    }

    /** The answered chat: open, one prompt sent and answered, so Retry and Compress are both offered. */
    private suspend fun ChatSession.answered(): ChatSession = apply {
        open()
        send("hi")
        core.emit("message.start", sessionId.value)
        core.emit("message.complete", sessionId.value, """{"text":"hello"}""")
    }

    /** The calls made after the session.compress call, as method and id: what was asked of the core during the compression. */
    private fun callsDuringCompression() = callsAfter(core.calls.indexOfFirst { it.first == "session.compress" } + 1)

    @Test
    fun aPromptDuringACompressionIsRefusedAndTheResultApplies() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().answered()
        var during: Chat? = null
        var sendRefused: Throwable? = null
        // The user sends and taps Retry while the core compresses (the fake answers on the caller's thread).
        compressReply = {
            during = chat.state.value
            sendRefused = runCatching { runBlocking { chat.send("next") } }.exceptionOrNull()
            runBlocking { chat.retry() }
            COMPRESSED
        }

        chat.compress(null)

        assertTrue(during!!.compressing)
        assertEquals(null, during!!.retryTarget())
        assertTrue("$sendRefused", sendRefused is SetupRefusal)
        assertEquals(ChatSession.STILL_COMPRESSING, sendRefused?.message)
        assertEquals(emptyList<Pair<String, String?>>(),
                     callsDuringCompression().filter { it.first == "prompt.submit" || it.first == "session.history" })
        assertEquals(listOf(Entry.User("latest", 9, 2), Entry.Reply("ok", TurnStatus.COMPLETE, stableId = 3)), chat.state.value.entries)  // no stray prompt
        assertFalse(chat.state.value.compressing)

        chat.send("next")  // the session is the chat's again

        assertEquals("prompt.submit" to "s1", callsAfter(core.calls.size - 1).single())
    }

    @Test
    fun noSwitchOrOwnDeleteWhileTheSessionCompresses() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().answered()
        core.emit("session.info", "s1", "{}")  // its build settled: a left s1 would be closed
        var refused: List<String?> = emptyList()
        compressReply = {
            refused = listOf<suspend () -> Unit>({ chat.open() }, { chat.resume(stored("db-old")) }, { chat.delete(stored("db1")) })
                .map { runCatching { runBlocking { it() } }.exceptionOrNull()?.message }
            COMPRESSED
        }

        chat.compress(null)

        assertEquals(List(3) { ChatSession.STILL_COMPRESSING }, refused)
        val switches = setOf("session.create", "session.resume", "session.close", "session.delete")
        assertEquals(emptyList<Pair<String, String?>>(), callsDuringCompression().filter { it.first in switches })
        assertEquals(emptyList<String>(), closed())
        assertEquals(runtime("s1"), chat.sessionId)

        chat.open()  // once it has finished, a switch goes through

        assertEquals(runtime("s2"), chat.sessionId)
    }

    @Test
    fun aSecondCompressionIsRefusedAndTheFirstKeepsItsOwnHold() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().answered()
        val mark = turns.size
        var second: Throwable? = null
        var heldDuring: List<String> = emptyList()
        var asked = 0
        compressReply = {
            if (asked++ == 0) {  // only the first: a second one let through would ask again (and recurse)
                second = runCatching { runBlocking { chat.compress(null) } }.exceptionOrNull()
                heldDuring = turns.drop(mark)
            }
            COMPRESSED
        }

        chat.compress(null)

        assertTrue("$second", second is SetupRefusal)
        assertEquals(ChatSession.STILL_COMPRESSING, second?.message)
        assertEquals(1, core.calls.count { it.first == "session.compress" })
        assertEquals(listOf("begin:compress:s1#1"), heldDuring)
        assertEquals(listOf("begin:compress:s1#1", "end:compress:s1#1"), turns.drop(mark))
        assertFalse("a compression is not the session's turn", "begin:s1" in turns.drop(mark))
    }

    @Test
    fun eachCompressionHoldsAKeyOfItsOwn() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().answered()
        val mark = turns.size
        compressReply = { COMPRESSED }

        chat.compress(null)
        chat.compress(null)

        val holds = turns.drop(mark)
        val k1 = holds.first().removePrefix("begin:")
        val k2 = holds.getOrNull(2).orEmpty().removePrefix("begin:")
        assertEquals(listOf("begin:$k1", "end:$k1", "begin:$k2", "end:$k2"), holds)
        assertNotEquals(k1, k2)
        assertTrue("$k1 $k2", k1.startsWith("compress:s1") && k2.startsWith("compress:s1"))
        assertTrue("$k1 $k2", k1 != "s1" && k2 != "s1")
    }

    @Test
    fun aCompressionThatFailsOrIsCancelledGivesTheSessionBack() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().answered()
        compressReply = { throw CoreError(5005, "boom") }

        val failed = runCatching { chat.compress(null) }.exceptionOrNull()

        assertEquals(5005, (failed as? CoreError)?.code)
        assertFalse(chat.state.value.compressing)
        assertTrue(turns.last(), turns.last().startsWith("end:compress:"))
        chat.send("next")
        assertEquals("prompt.submit" to "s1", callsAfter(core.calls.size - 1).single())

        val fresh = openChat().answered()
        compressReply = { throw CancellationException("gone") }

        val cancelled = runCatching { fresh.compress(null) }.exceptionOrNull()

        assertTrue("$cancelled", cancelled is CancellationException)
        assertFalse(fresh.state.value.compressing)
        assertTrue(turns.last(), turns.last().startsWith("end:compress:${fresh.sessionId.value}"))
    }

    @Test
    fun compressWithNoSessionIsRefusedInWords() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat()

        val refused = runCatching { chat.compress(null) }.exceptionOrNull()

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.NOTHING_TO_COMPRESS, refused?.message)
        assertEquals(emptyList<Pair<String, JsonObject>>(), core.calls)
        assertEquals(emptyList<String>(), turns)
    }

    @Test
    fun aCompressAskedDuringASwitchActsOnTheSessionTheSwitchOpened() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        var later: Deferred<String>? = null
        // Compress is asked while New chat's session.create is being answered.
        created = { if (later == null) later = async { chat.compress(null) } }
        compressReply = { COMPRESSED }

        chat.open()
        later!!.await()

        assertEquals("s2", lastOf("session.compress").getValue("session_id").jsonPrimitive.content)
    }

    @Test
    fun aLockHeldElsewhereSaysSoAndChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.usage", "s1", """{"usage":{"context_used":3000,"context_max":32000,"context_percent":9}}""")
        val before = chat.state.value
        compressReply = { """{"compressed":false,"lock_held":true,"message":"Another process is compressing this session."}""" }

        assertEquals("Another process is compressing this session.", chat.compress(null))
        assertEquals(before.entries, chat.state.value.entries)
        assertEquals(ContextMeter(3000, 32000, 9, false), chat.state.value.meter)
    }

    @Test
    fun aRunningTurnIsNotCompressed() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("message.start", "s1")

        val refused = runCatching { chat.compress(null) }.exceptionOrNull()

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.STILL_REPLYING, refused?.message)
        assertFalse(core.calls.any { it.first == "session.compress" })
        assertEquals(listOf("begin:s1"), turns)
    }

    @Test
    fun renamingTheChatsOwnSessionTitlesItInPlace() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        chat.rename(stored("db1"), "Trip plans")

        assertEquals(listOf("session.title" to "s1"), callsAfter(1))
        assertEquals("Trip plans", lastOf("session.title").getValue("title").jsonPrimitive.content)
        assertEquals(emptyList<String>(), closed())
        assertEquals(runtime("s1"), chat.sessionId)
    }

    @Test
    fun renamingAnotherRowAttachesItAsideAndLetsItGo() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        resume = sideResume
        val shown = chat.state.value

        chat.rename(stored("db-old"), "Calendar 2")

        assertEquals(listOf("session.resume" to "db-old", "session.title" to "s-side", "session.close" to "s-side"), callsAfter(1))
        val resumed = lastOf("session.resume")
        assertEquals(Triple("true", "true", "android"),
                     Triple(resumed["lazy"]?.jsonPrimitive?.content, resumed["omit_messages"]?.jsonPrimitive?.content,
                            resumed["source"]?.jsonPrimitive?.content))
        assertEquals("Calendar 2", lastOf("session.title").getValue("title").jsonPrimitive.content)
        assertEquals(runtime("s1"), chat.sessionId)
        assertEquals(shown, chat.state.value)  // the row was never shown
    }

    @Test
    fun aRowStillWritingIsTitledButNotClosed() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        resume = { """{"session_id":"s-busy","message_count":0,"messages":[],"session_key":"db-old","running":true,"info":{}}""" }

        chat.rename(stored("db-old"), "Calendar 2")

        assertEquals(listOf("session.resume" to "db-old", "session.title" to "s-busy"), callsAfter(1))
        assertEquals(emptyList<String>(), closed())  // its turn runs on (invariant 3)
    }

    @Test
    fun aRowTheCoreAnswersWithTheChatsOwnSessionIsNotClosed() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        // Another key of the chat's lineage: the core follows it and hands back the live session it has.
        resume = { """{"session_id":"s1","message_count":0,"messages":[],"session_key":"db1","running":false,"info":{}}""" }

        chat.rename(stored("db1-root"), "Trip plans")

        assertEquals(listOf("session.resume" to "db1-root", "session.title" to "s1"), callsAfter(1))
        assertEquals(emptyList<String>(), closed())
        assertEquals(runtime("s1"), chat.sessionId)
    }

    @Test
    fun aRefusedTitleIsThrownInTheCoresWordsAndTheRowStillGoes() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        resume = sideResume
        title = { throw CoreError(4022, "Title 'Trip' is already in use by session db-x") }

        val refused = runCatching { chat.rename(stored("db-old"), "Trip") }.exceptionOrNull()

        assertTrue("$refused", refused is CoreError)
        assertEquals(4022 to "Title 'Trip' is already in use by session db-x", (refused as CoreError).code to refused.message)
        assertEquals(listOf("s-side"), closed())
    }

    @Test
    fun aRenameWaitsForTheChatsSwitch() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val asked = resume
        var renaming = false
        // The user renames the row the chat is switching to while its resume is still being answered.
        resume = {
            if (!renaming) {
                renaming = true
                backgroundScope.launch { chat.rename(stored("db-old-tip"), "Calendar 2") }
            }
            asked()
        }

        chat.resume(stored("db-old"))

        // After the switch the row is the chat's own: titled where it is, never attached aside and closed under the chat.
        assertEquals(listOf("session.resume" to "db-old", "session.title" to "s-resumed"), callsAfter(1))
        assertEquals(emptyList<String>(), closed())
        assertEquals(runtime("s-resumed"), chat.sessionId)
    }

    @Test
    fun aDeleteWaitsForTheChatsSwitch() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val asked = resume
        var deleting = false
        // The user deletes the row the chat is switching to while its resume is still being answered.
        resume = {
            if (!deleting) {
                deleting = true
                backgroundScope.launch { chat.delete(stored("db-old-tip")) }
            }
            asked()
        }

        chat.resume(stored("db-old"))

        // After the switch the row is the chat's own: the chat moves off it, and nothing is deleted yet.
        assertEquals(listOf("session.resume" to "db-old", "session.create" to null), callsAfter(1))

        core.emit("session.info", "s-resumed", "{}")
        assertEquals(listOf("session.close" to "s-resumed", "session.delete" to "db-old-tip"), callsAfter(core.calls.size - 2))
    }

    @Test
    fun noIdReachesTheCoreOrTheTurnHostByItsTypeName() = runTest(UnconfinedTestDispatcher()) {
        mostRecent = { """{"session_id":"db-old"}""" }
        val chat = openChat().apply { start() }  // most_recent → resume db-old → s-resumed
        chat.send("hi")
        core.emit("message.start", "s-resumed")
        core.emit("message.complete", "s-resumed", """{"text":"hello"}""")
        core.emit("session.info", "s-resumed", "{}")  // its build settled: the chat may close it when it leaves
        compressReply = { COMPRESSED }
        chat.compress(null)
        chat.breakdown()
        chat.retry()  // the compressed transcript's user row 9
        core.emit("message.start", "s-resumed")
        core.emit("message.complete", "s-resumed", """{"text":"again"}""")
        resume = sideResume
        chat.rename(stored("db-old"), "Trip")  // another row now (the key rotated to db-rotated): attached aside and closed
        chat.delete(stored("db-x"))

        chat.open()  // leaves s-resumed, settled and idle: closed

        assertFalse(core.calls.toString(), "SessionId(" in core.calls.toString())
        assertFalse(turns.toString(), "SessionId(" in turns.toString())
        assertEquals(listOf("s-side", "s-resumed"), closed())
        assertEquals("db-x", lastOf("session.delete").getValue("session_id").jsonPrimitive.content)
        assertEquals(listOf("db-old", "db-old"),
                     core.calls.filter { it.first == "session.resume" }.map { it.second.getValue("session_id").jsonPrimitive.content })
    }

    @Test
    fun aJobScheduledWhileTheGatewayIsOffIsSaidOnceInTheAppsWords() = runTest(UnconfinedTestDispatcher()) {
        seen = { GatewaySeen(GatewayStatus.STOPPED) }
        val chat = openChat().apply { open() }
        core.emit("message.start", "s1")

        scheduled("s1", "t1")

        assertEquals(listOf(Entry.Tool("t1", "cronjob_manage", SCHEDULED, done = true, stableId = 0),
                            Entry.Notice(ChatSession.JOBS_NEED_GATEWAY, NoticeShortcut.GATEWAY, 1)), chat.state.value.entries)

        scheduled("s1", "t2")  // a second job in the same conversation is not said again
        core.emit("message.complete", "s1", """{"text":"Done: two jobs."}""")

        assertEquals(1, chat.jobNotices().size)
        assertEquals(Entry.Reply("Done: two jobs.", TurnStatus.COMPLETE, stableId = 3), chat.state.value.entries.last())
        // The core's remedy (a CLI command) is never shown by the app.
        assertFalse(chat.state.value.entries.toString(), chat.state.value.entries.any { e ->
            listOfNotNull((e as? Entry.Notice)?.text, (e as? Entry.Tool)?.summary, (e as? Entry.Reply)?.text).any { "hermes gateway" in it }
        })
        assertEquals("finished:Done: two jobs.", notices.last())
    }

    @Test
    fun aNewOrResumedSessionCanBeToldAgain() = runTest(UnconfinedTestDispatcher()) {
        seen = { GatewaySeen(GatewayStatus.STOPPED) }
        val chat = openChat().apply { open() }
        scheduled("s1", "t1")
        assertEquals(1, chat.jobNotices().size)

        chat.open()  // s2
        scheduled("s2", "t3")

        // s1 showed 0 and 1: the new session's tool and notice go on from there (V5).
        assertEquals(listOf(Entry.Notice(ChatSession.JOBS_NEED_GATEWAY, NoticeShortcut.GATEWAY, 3)), chat.jobNotices())
        assertEquals(Entry.Tool("t3", "cronjob_manage", SCHEDULED, done = true, stableId = 2), chat.state.value.entries.first())

        chat.resume(stored("db-old"))  // s-resumed
        scheduled("s-resumed", "t4")

        // The stored rows 4, 5, the tool 6, the notice 7.
        assertEquals(listOf(Entry.Notice(ChatSession.JOBS_NEED_GATEWAY, NoticeShortcut.GATEWAY, 7)), chat.jobNotices())
    }

    @Test
    fun noNoticeWhileTheGatewayRunsOrIsOnItsWayUp() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        for ((i, status) in listOf(GatewayStatus.RUNNING, GatewayStatus.STARTING, GatewayStatus.RESTARTING).withIndex()) {
            seen = { GatewaySeen(status) }
            scheduled("s1", "t$i")
            assertEquals(status.name, emptyList<Entry.Notice>(), chat.jobNotices())
            assertEquals(status.name, i + 1, observed)
        }

        seen = { GatewaySeen(GatewayStatus.DIED, "killed", 41) }
        scheduled("s1", "t-died")

        assertEquals(1, chat.jobNotices().size)  // the gateway on its way up spent nothing (V2)
        for ((n, status) in listOf(GatewayStatus.FAILED, GatewayStatus.STOPPING).withIndex()) {
            seen = { GatewaySeen(status) }
            chat.open()
            scheduled("s${n + 2}", "t-$status")
            assertEquals(status.name, 1, chat.jobNotices().size)
        }
    }

    @Test
    fun onlyASchedulingCompletionIsObserved() = runTest(UnconfinedTestDispatcher()) {
        seen = { GatewaySeen(GatewayStatus.STOPPED) }
        val chat = openChat().apply { open() }
        core.emit("message.start", "s1")

        scheduled("s1", "t1", action = "list")
        scheduled("s1", "t2", action = "remove")
        core.emit("tool.complete", "s1", """{"tool_id":"t3","name":"terminal","args":{"action":"create"},"result":{"success":true}}""")
        core.emit("todo.updated", "s1", """{"todos":[{"id":"1","content":"Boil","status":"pending"}],"revision":1}""")
        core.emit("message.delta", "s1", """{"text":"Listed."}""")

        assertEquals(emptyList<Entry.Notice>(), chat.jobNotices())
        assertEquals(0, observed)
    }

    @Test
    fun anotherSessionsJobIsNotThisChats() = runTest(UnconfinedTestDispatcher()) {
        seen = { GatewaySeen(GatewayStatus.STOPPED) }
        val chat = openChat().apply { open() }

        scheduled("s9", "t1")

        assertEquals(emptyList<Entry>(), chat.state.value.entries)
        assertEquals(0, observed)
    }

    @Test
    fun anObservationThatFailsSaysNothing() = runTest(UnconfinedTestDispatcher()) {
        seen = { error("binder died") }
        val chat = openChat().apply { open() }
        core.emit("message.start", "s1")

        scheduled("s1", "t1")
        core.emit("message.complete", "s1", """{"text":"Scheduled."}""")

        assertEquals(emptyList<Entry.Notice>(), chat.jobNotices())
        assertEquals(Entry.Reply("Scheduled.", TurnStatus.COMPLETE, stableId = 1), chat.state.value.entries.last())  // the chat still reduces

        seen = { GatewaySeen(GatewayStatus.STOPPED) }  // "cannot tell" spent nothing
        scheduled("s1", "t2")

        assertEquals(1, chat.jobNotices().size)
    }

    /** The core's `session.resume` of the job run [stored] as runtime [session]: a prompt and its answer. */
    private fun runResume(session: String = "s-run", stored: String = RUN) = {
        """{"session_id":"$session","message_count":2,
            "messages":[{"role":"user","text":"Summarise the news"},{"role":"assistant","text":"Three stories."}],
            "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"kimi-k2","stored_session_id":"$stored"}}"""
    }

    private fun submits() = core.calls.filter { it.first == "prompt.submit" }

    @Test
    fun aJobsRunTheSchedulerStillOwnsIsReadNotWritten() = runTest(UnconfinedTestDispatcher()) {
        owned = { true }
        resume = runResume()
        val chat = openChat()

        chat.resume(stored(RUN))

        assertTrue(chat.state.value.runOwned)
        assertEquals(listOf(RUN), ownedAsked)
        assertEquals(listOf(Entry.User("Summarise the news", stableId = 0), Entry.Reply("Three stories.", TurnStatus.COMPLETE, stableId = 1)),
                     chat.state.value.entries)  // viewing stays allowed

        chat.send("hello")

        assertEquals(emptyList<Any>(), submits())
        assertEquals(Entry.Notice(ChatSession.RUN_STILL_OWNED, stableId = 2), chat.state.value.entries.last())
        assertTrue(chat.state.value.entries.none { it is Entry.User && it.text == "hello" })
        assertFalse(chat.state.value.busy)
        assertFalse(turns.toString(), "begin:s-run" in turns)
        assertEquals(listOf(RUN), ownedAsked)  // one read at open: the send asks nothing (V4)
    }

    @Test
    fun aRunTheSchedulerNoLongerOwnsTakesPromptsAsBefore() = runTest(UnconfinedTestDispatcher()) {
        owned = { false }
        resume = runResume()
        val chat = openChat()
        chat.resume(stored(RUN))

        chat.send("hello")

        assertFalse(chat.state.value.runOwned)
        assertEquals(listOf("s-run"), submits().map { it.second.getValue("session_id").jsonPrimitive.content })
    }

    @Test
    fun aCoreThatCannotSayKeepsTodaysRule() = runTest(UnconfinedTestDispatcher()) {
        resume = runResume()
        for (answer in listOf<suspend (StoredSessionId) -> Boolean?>({ null }, { throw java.io.IOException("x") })) {
            owned = answer
            core.calls.clear()
            val chat = openChat()
            chat.resume(stored(RUN))

            chat.send("hello")

            assertFalse(chat.state.value.runOwned)
            assertEquals(1, submits().size)  // an older core, or a failed read (V6): today's behaviour
        }
    }

    @Test
    fun anOrdinaryChatAsksNothing() = runTest(UnconfinedTestDispatcher()) {
        owned = { true }
        val chat = openChat()
        chat.open()
        chat.resume(stored("db-old"))

        chat.send("and tomorrow?")

        assertEquals(emptyList<String>(), ownedAsked)  // V3: no bridge call, no fastapi import
        assertEquals(1, submits().size)
    }

    @Test
    fun aRunThatEndsTakesPromptsAfterTheNextCronChange() = runTest(UnconfinedTestDispatcher()) {
        owned = { true }
        resume = runResume()
        val chat = openChat()
        chat.resume(stored(RUN))

        core.emit("cron.changed", null, "{}")  // global: no session id

        assertEquals(2, ownedAsked.size)
        assertTrue(chat.state.value.runOwned)
        chat.send("hello")
        assertEquals(emptyList<Any>(), submits())

        owned = { false }  // the run ended: its job's last_run_at moved
        core.emit("cron.changed", null, "{}")

        assertEquals(listOf(RUN, RUN, RUN), ownedAsked)
        assertFalse(chat.state.value.runOwned)
        chat.send("hello")
        assertEquals(listOf("s-run"), submits().map { it.second.getValue("session_id").jsonPrimitive.content })

        core.emit("cron.changed", null, "{}")  // the chat may write: nothing is asked again
        assertEquals(3, ownedAsked.size)
    }

    @Test
    fun cronChangedAsksNothingWhileTheChatMayWrite() = runTest(UnconfinedTestDispatcher()) {
        owned = { true }
        val chat = openChat()
        chat.open()

        repeat(3) { core.emit("cron.changed", null, "{}") }

        assertEquals(emptyList<String>(), ownedAsked)
        assertFalse(chat.state.value.runOwned)
    }

    @Test
    fun leavingAnOwnedRunLetsTheNextChatWrite() = runTest(UnconfinedTestDispatcher()) {
        owned = { true }
        resume = runResume()
        val chat = openChat()
        chat.resume(stored(RUN))
        val late = kotlinx.coroutines.CompletableDeferred<Boolean?>()
        owned = { late.await() }
        core.emit("cron.changed", null, "{}")  // asked on the run; its answer comes after the chat left

        chat.open()

        assertFalse(chat.state.value.runOwned)
        chat.send("hello")
        assertEquals(listOf("s1"), submits().map { it.second.getValue("session_id").jsonPrimitive.content })
        val onS1 = chat.state.value
        late.complete(false)
        assertEquals(onS1, chat.state.value)  // the change guard: only the session it was asked for

        // A late "no longer owned" for one run never opens another run the scheduler still owns.
        owned = { true }
        chat.resume(stored(RUN))
        val later = kotlinx.coroutines.CompletableDeferred<Boolean?>()
        owned = { later.await() }
        core.emit("cron.changed", null, "{}")
        owned = { true }
        resume = runResume(session = "s-run2", stored = RUN2)
        chat.resume(stored(RUN2))
        later.complete(false)

        assertTrue(chat.state.value.runOwned)
        assertEquals(listOf(RUN, RUN, RUN, RUN, RUN2), ownedAsked)
    }

    @Test
    fun noRetryInAnOwnedRun() = runTest(UnconfinedTestDispatcher()) {
        owned = { true }
        resume = runResume()
        val chat = openChat()
        chat.resume(stored(RUN))

        chat.retry()

        assertEquals(emptyList<Any>(), submits())
        assertEquals(emptyList<Any>(), core.calls.filter { it.first == "session.history" })
    }

    private companion object {
        /** A job's run, named as the core's scheduler names it. */
        const val RUN = "cron_ab12cd34ef56_20261113_100000"
        const val RUN2 = "cron_ab12cd34ef56_20261113_110000"
        /** The core's summary of a job-tool row. */
        const val SCHEDULED = "Scheduled the job"
        const val BREAKDOWN = """{"categories":[{"color":"var(--context-usage-system)","id":"system_prompt","label":"System prompt","tokens":4000}],
            "context_max":32000,"context_used":4000,"context_percent":13,"estimated_total":4000,"context_estimated":false,
            "context_source":"provider_usage","model":"m"}"""
        const val COMPRESSED = """{"status":"compressed","removed":2,"before_messages":4,"after_messages":2,
            "summary":{"headline":"Compressed: 4 → 2 messages","token_line":"Approx request size: ~9,000 → ~2,100 tokens"},
            "usage":{"context_used":2100,"context_max":8192,"context_percent":26},"info":{"stored_session_id":"db-rotated"},
            "messages":[{"role":"user","text":"latest","row_id":9},{"role":"assistant","text":"ok"}]}"""
    }

    // A send while a turn runs (chat-18, batch-3 F1): the core's busy mode says how it goes; only Stop interrupts.

    private fun busyReads() = core.calls.count { it.first == "config.get" && it.second["key"]?.jsonPrimitive?.content == "busy" }

    /** [core], with each `session.steer` held until [answered] completes: the answer arrives when the test says. */
    private fun gated(answered: CompletableDeferred<Unit>) = object : CoreGateway by core {
        override suspend fun <P, R> call(method: Method<P, R>, params: P): R {
            if (method.name == "session.steer") answered.await()
            return core.call(method, params)
        }
    }

    @Test
    fun theBusyModeIsReadOnceAsATurnBegins() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        assertEquals(0, busyReads())  // before the turn began
        assertEquals(null, chat.state.value.busyMode)

        core.emit("message.start", "s1")
        core.emit("message.delta", "s1", """{"text":"Hel"}""")
        assertEquals(1, busyReads())
        assertEquals(BusyMode.INTERRUPT, chat.state.value.busyMode)

        core.emit("message.complete", "s1", """{"text":"Hello","status":"complete"}""")
        busyWord = "steer"
        chat.send("again")
        core.emit("message.start", "s1")
        assertEquals(2, busyReads())  // each turn reads it again
        assertEquals(BusyMode.STEER, chat.state.value.busyMode)

        core.emit("message.complete", "s1", """{"text":"Hi","status":"complete"}""")
        busy = { throw CoreError(5000, "x") }
        chat.send("once more")
        core.emit("message.start", "s1")
        assertEquals(null, chat.state.value.busyMode)  // cannot tell is not a mode: Stop alone
    }

    @Test
    fun aBusySendRedirectsTheRunningTurnAndNeverInterruptsIt() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val mark = core.calls.size

        chat.send("use metric units")

        assertEquals(listOf("session.redirect"), callsAfter(mark).map { it.first })  // no session.interrupt, no prompt.submit
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"use metric units"}"""), core.calls.last().second)
        assertEquals(Entry.User("use metric units", stableId = 1, mode = BusyMode.INTERRUPT, correction = Correction.REDIRECTED),
                     chat.state.value.entries.last())
        assertTrue(chat.state.value.running)
        assertFalse(chat.state.value.submitted)
        assertEquals(listOf("begin:s1"), turns)
    }

    @Test
    fun aBusySendSteersWhenTheCoreSaysSteer() = runTest(UnconfinedTestDispatcher()) {
        busyWord = "steer"
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val mark = core.calls.size

        chat.send("use metric units")

        assertEquals(listOf("session.steer" to "s1"), callsAfter(mark))
        // The core's `queued` from a steer is the words injected, never "queued for later" (§5).
        assertEquals(Entry.User("use metric units", stableId = 1, mode = BusyMode.STEER, correction = Correction.STEERED),
                     chat.state.value.entries.last())
    }

    @Test
    fun aRedirectTheCoreQueuedRunsNext() = runTest(UnconfinedTestDispatcher()) {
        redirect = { p -> """{"status":"queued","text":${p.getValue("text")}}""" }  // the agent was compressing
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")

        chat.send("use metric units")

        assertEquals(Correction.QUEUED_BY_CORE, (chat.state.value.entries.last() as Entry.User).correction)
        assertEquals(emptyList<Entry.Notice>(), chat.state.value.entries.filterIsInstance<Entry.Notice>())  // not a problem (D5)
    }

    @Test
    fun queueModeAndAWaitingQuestionSendNothingWhileATurnRuns() = runTest(UnconfinedTestDispatcher()) {
        busyWord = "queue"
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val mark = core.calls.size
        val shown = chat.state.value.entries

        chat.send("later")  // the app's queue is M3's: today's behaviour (D3)

        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))
        assertEquals(shown, chat.state.value.entries)

        busyWord = "interrupt"
        chat.open()
        chat.send("hi")
        core.emit("message.start", "s2")
        core.ask("srq-1", "approval", """{"session_id":"s2","request_id":"r1","command":"rm -rf build"}""")
        val asked = core.calls.size
        val waiting = chat.state.value.entries

        chat.send("later")  // the turn is parked on the user: a correction cannot reach the model (D8)

        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(asked))
        assertEquals(waiting, chat.state.value.entries)
    }

    @Test
    fun aRejectedCorrectionGoesOutOnceAsAPromptAfterTheTurnEnds() = runTest(UnconfinedTestDispatcher()) {
        busyWord = "steer"
        steer = { p -> """{"status":"rejected","text":${p.getValue("text")}}""" }  // the turn ended in the core meanwhile
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")

        chat.send("use metric units")  // answered before the turn's end reached the app (D6)

        assertEquals(1, submits().size)
        assertEquals(Entry.User("use metric units", stableId = 1, mode = BusyMode.STEER), chat.state.value.entries.last())

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(2, submits().size)
        assertEquals("use metric units", lastSubmit().getValue("text").jsonPrimitive.content)
        val entries = chat.state.value.entries
        assertEquals(Entry.User("use metric units", stableId = 1), entries.single { it.stableId == 1L })
        assertTrue(chat.state.value.submitted)
        assertTrue(entries.indexOfFirst { it is Entry.Reply } < entries.indexOfFirst { it.stableId == 1L })  // the old turn's answer first
        assertTrue("finished:Done." in notices)  // the finished turn is still its reply's

        core.emit("message.complete", "s1", """{"text":"Metric.","status":"complete"}""")
        assertEquals(2, submits().size)  // once
    }

    @Test
    fun aRejectedCorrectionAfterTheTurnEndedGoesOutAtOnce() = runTest(UnconfinedTestDispatcher()) {
        busyWord = "steer"
        steer = { p -> """{"status":"rejected","text":${p.getValue("text")}}""" }
        val answered = CompletableDeferred<Unit>()
        val chat = ChatSession(gated(answered), host, backgroundScope, keepSecret = { _, _ -> }).apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")

        val correcting = launch { chat.send("use metric units") }
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")  // the turn's end first
        answered.complete(Unit)
        correcting.join()

        assertEquals(2, submits().size)
        assertEquals("use metric units", lastSubmit().getValue("text").jsonPrimitive.content)
        assertEquals(Entry.User("use metric units", stableId = 1), chat.state.value.entries.last())
        assertTrue(chat.state.value.submitted)
    }

    @Test
    fun aRejectedCorrectionAfterStopIsNotSent() = runTest(UnconfinedTestDispatcher()) {
        busyWord = "steer"
        steer = { p -> """{"status":"rejected","text":${p.getValue("text")}}""" }
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.send("use metric units")

        core.emit("message.complete", "s1", """{"text":"","status":"interrupted"}""")  // the user pressed Stop

        assertEquals(1, submits().size)
        val entries = chat.state.value.entries
        assertEquals(ChatSession.CORRECTION_NOT_SENT, (entries.last() as Entry.Notice).text)
        assertEquals(Entry.User("use metric units", stableId = 1, mode = BusyMode.STEER), entries.single { it.stableId == 1L })
        assertFalse(chat.state.value.busy)
    }

    @Test
    fun aCorrectionTheAgentCannotTakeWaitsInTheQueue() = runTest(UnconfinedTestDispatcher()) {
        redirect = { throw CoreError(4010, "agent does not support active-turn redirect") }
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("x")
        val mark = core.calls.size

        chat.send("use metric units")

        assertTrue(chat.state.value.entries.none { it is Entry.User && it.text == "use metric units" })  // its words in one place (D10)
        assertEquals(ChatSession.CORRECTION_QUEUED, (chat.state.value.entries.last() as Entry.Notice).text)
        assertEquals(listOf("use metric units", "x"), chat.items())
        assertTrue(chat.state.value.running)
        assertEquals(listOf("session.redirect"), callsAfter(mark).map { it.first })  // no session.interrupt, no prompt.submit

        chat.steerNow(chat.idOf("x"))

        assertEquals(listOf("x", "use metric units"), chat.items())  // back at the head
        assertTrue(chat.state.value.entries.none { it is Entry.User && it.text == "x" })
        assertEquals(listOf("session.redirect", "session.redirect"), callsAfter(mark).map { it.first })
        assertTrue(chat.state.value.running)
    }

    @Test
    fun aCorrectionAnsweredAfterTheChatMovedOnChangesNothingThere() = runTest(UnconfinedTestDispatcher()) {
        busyWord = "steer"
        steer = { p -> """{"status":"rejected","text":${p.getValue("text")}}""" }
        val answered = CompletableDeferred<Unit>()
        val chat = ChatSession(gated(answered), host, backgroundScope, keepSecret = { _, _ -> }).apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val correcting = launch { chat.send("use metric units") }

        chat.open()
        val moved = chat.state.value
        answered.complete(Unit)
        correcting.join()

        assertEquals(moved.entries, chat.state.value.entries)
        assertFalse(chat.state.value.submitted)
        assertEquals(1, submits().size)
    }

    // The chat's queue (chat-19, batch-3 F2): the app's, per stored session, persisted; one item per finished turn, with
    // `queued: true`; Stop or a failed turn pauses it.

    private fun ChatSession.items() = queueState.value.items.map { it.text }

    private fun ChatSession.idOf(text: String) = queueState.value.items.first { it.text == text }.id

    /** The texts the store keeps for [key]; null: no entry (an empty queue is none). */
    private fun saved(key: String) = savedQueues[stored(key)]?.items?.map { it.text }

    /** A turn of the chat's session [session] starts and ends with [status]. */
    private suspend fun turn(session: String = "s1", status: String = "complete") {
        core.emit("message.start", session)
        core.emit("message.complete", session, """{"text":"Done.","status":"$status"}""")
    }

    @Test
    fun aFinishedTurnSendsTheQueuesHeadWithTheQueuedFlag() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val mark = core.calls.size

        chat.queue("a")
        chat.queue("b")
        chat.queue("c")

        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))  // the queue is the app's: nothing goes out
        assertEquals(listOf("a", "b", "c"), saved("db1"))

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(listOf("prompt.submit" to "s1"), callsAfter(mark))  // exactly one item
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"a","queued":true}"""), lastSubmit())
        assertEquals(listOf("b", "c"), chat.items())
        assertEquals(Entry.User("a", stableId = 2), chat.state.value.entries.last())
        assertTrue(chat.state.value.submitted)
        assertEquals(listOf("b", "c"), saved("db1"))

        turn()
        assertEquals("b", lastSubmit().getValue("text").jsonPrimitive.content)
        assertEquals(listOf("c"), chat.items())
        assertEquals(listOf("c"), saved("db1"))

        turn()
        assertEquals("c", lastSubmit().getValue("text").jsonPrimitive.content)
        assertEquals(emptyList<String>(), chat.items())
        assertEquals(null, saved("db1"))

        turn()
        assertEquals(4, submits().size)  // hi, a, b, c: the fourth turn's end sends nothing more
        assertFalse(chat.state.value.busy)
    }

    @Test
    fun queueModeSendsGoIntoTheQueue() = runTest(UnconfinedTestDispatcher()) {
        busyWord = "queue"
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val mark = core.calls.size
        val shown = chat.state.value.entries

        chat.send("later")

        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))
        assertEquals(shown, chat.state.value.entries)
        assertEquals(listOf("later"), chat.items())

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"later","queued":true}"""), lastSubmit())
    }

    @Test
    fun stopPausesTheQueueEvenWhenTheTurnThenCompletes() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        val mark = core.calls.size

        chat.cancel()

        assertEquals(listOf("session.interrupt" to "s1"), callsAfter(mark))
        assertTrue(chat.queueState.value.paused)

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")  // the turn finished anyway

        assertEquals(1, submits().size)
        assertEquals(listOf("a"), chat.items())
        assertTrue(chat.queueState.value.paused)
        assertEquals(QueueUi(listOf(Queued(0, "a")), paused = true), savedQueues[stored("db1")])
    }

    @Test
    fun aStoppedOrFailedTurnPausesTheQueue() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat()
        val ends = listOf<suspend (String) -> Unit>(
            { s -> core.emit("message.complete", s, """{"text":"","status":"interrupted"}""") },
            { s -> core.emit("message.complete", s, """{"text":"","status":"error","error":"boom"}""") },
            { s -> core.emit("error", s, """{"message":"boom"}""") })
        for ((i, end) in ends.withIndex()) {
            chat.open()
            val session = "s${i + 1}"
            chat.send("hi")
            core.emit("message.start", session)
            chat.queue("a")
            val sent = submits().size

            end(session)

            assertEquals(sent, submits().size)
            assertEquals(listOf("a"), chat.items())
            assertTrue("$i", chat.queueState.value.paused)
        }

        chat.open()  // Stop before message.start: the core ends that prompt with an `error` alone (D1)
        chat.send("hi")
        chat.queue("a")
        val sent = submits().size
        chat.cancel()
        core.emit("error", "s4", """{"message":"interrupted"}""")

        assertEquals(sent, submits().size)
        assertEquals(listOf("a"), chat.items())
        assertTrue(chat.queueState.value.paused)
        assertFalse(chat.state.value.busy)
    }

    @Test
    fun resumeSendsTheHeadAtOnceWhenIdleAndAtTheTurnsEndWhenBusy() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        chat.queue("b")
        core.emit("message.complete", "s1", """{"text":"","status":"interrupted"}""")
        assertTrue(chat.queueState.value.paused)
        val mark = core.calls.size

        chat.resumeQueue()  // paused and idle: the head goes out at once

        assertEquals(listOf("prompt.submit" to "s1"), callsAfter(mark))
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"a","queued":true}"""), lastSubmit())
        assertEquals(QueueUi(listOf(Queued(1, "b"))), chat.queueState.value)

        turn(status = "interrupted")  // paused again
        chat.send("x")
        core.emit("message.start", "s1")
        assertTrue(chat.queueState.value.paused)
        val running = submits().size

        chat.resumeQueue()  // while a turn runs: not paused, nothing sent yet

        assertEquals(running, submits().size)
        assertFalse(chat.queueState.value.paused)

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(running + 1, submits().size)
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"b","queued":true}"""), lastSubmit())
    }

    @Test
    fun sendNextMovesAnItemToTheFrontWithoutInterrupting() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        listOf("a", "b", "c").forEach { chat.queue(it) }
        val mark = core.calls.size

        chat.sendNext(chat.idOf("c"))

        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))  // no session.interrupt: only Stop interrupts (inv. 1)
        assertEquals(listOf("c", "a", "b"), chat.items())
        assertFalse(chat.queueState.value.paused)

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")
        assertEquals("c", lastSubmit().getValue("text").jsonPrimitive.content)

        turn(status = "interrupted")
        assertTrue(chat.queueState.value.paused)
        val idle = submits().size

        chat.sendNext(chat.idOf("b"))  // idle: Send sends it now

        assertEquals(idle + 1, submits().size)
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"b","queued":true}"""), lastSubmit())
        assertEquals(listOf("a"), chat.items())
        assertTrue(core.calls.none { it.first == "session.interrupt" })
    }

    @Test
    fun steerNowCorrectsTheRunWithAnItemAndTakesItOffTheQueue() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        chat.queue("b")
        var mark = core.calls.size

        chat.steerNow(chat.idOf("a"))

        assertEquals(listOf("session.redirect" to "s1"), callsAfter(mark))
        assertEquals("a", lastOf("session.redirect").getValue("text").jsonPrimitive.content)
        assertEquals(listOf("b"), chat.items())
        assertEquals(Entry.User("a", stableId = 1, mode = BusyMode.INTERRUPT, correction = Correction.REDIRECTED),
                     chat.state.value.entries.last())

        busyWord = "steer"
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")  // b goes out
        core.emit("message.start", "s1")
        chat.queue("d")
        mark = core.calls.size

        chat.steerNow(chat.idOf("d"))

        assertEquals(listOf("session.steer" to "s1"), callsAfter(mark))
        assertEquals(emptyList<String>(), chat.items())

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")
        chat.send("e")  // on its way, not started: a correction cannot land yet
        chat.queue("f")
        mark = core.calls.size

        chat.steerNow(chat.idOf("f"))

        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))
        assertEquals(listOf("f"), chat.items())
    }

    @Test
    fun anActionOnAnItemAlreadySentChangesNothingAndAnEditedItemWaits() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        chat.queue("b")
        val aId = chat.idOf("a")
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")
        assertEquals("a", lastSubmit().getValue("text").jsonPrimitive.content)

        chat.deleteQueued(aId)
        chat.editQueued(aId, "x")
        chat.sendNext(aId)

        assertEquals(listOf("b"), chat.items())
        assertEquals(2, submits().size)

        val bId = chat.idOf("b")
        chat.beginEdit(bId)
        chat.queue("c")
        turn()  // a's turn ends: the edited b waits, c goes out

        assertEquals("c", lastSubmit().getValue("text").jsonPrimitive.content)
        assertEquals(listOf("b"), chat.items())
        assertEquals(bId, chat.queueState.value.editing)

        chat.editQueued(bId, "B2")

        assertEquals(listOf("B2"), chat.items())
        assertEquals(null, chat.queueState.value.editing)

        turn()
        assertEquals("B2", lastSubmit().getValue("text").jsonPrimitive.content)
        assertEquals(4, submits().size)
    }

    @Test
    fun eachSessionKeepsItsOwnQueue() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")

        chat.open()

        assertEquals(QueueUi(), chat.queueState.value)  // a new chat's queue is empty

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(1, submits().size)  // the chat is not on s1: its queue waits for the user's return (DC-M5)

        resume = { """{"session_id":"s-back","message_count":0,"messages":[],"session_key":"db1",
                      "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}""" }
        chat.resume(stored("db1"))

        assertEquals(listOf("a"), chat.items())
        assertTrue(chat.queueState.value.paused)  // its turn did not end in this chat (D6)
        assertEquals(1, submits().size)
    }

    @Test
    fun theQueueOutlivesTheProcess() = runTest(UnconfinedTestDispatcher()) {
        val before = openChat().apply { open() }
        before.send("hi")
        core.emit("message.start", "s1")
        before.queue("a")
        before.queue("b")

        mostRecent = { """{"session_id":"db1"}""" }
        resume = { """{"session_id":"s9","message_count":0,"messages":[],"session_key":"db1",
                      "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}""" }
        val after = ChatSession(core, host, backgroundScope, keepSecret = { _, _ -> }, queues = QueueStore.inMemory(savedQueues))
        after.start()

        assertEquals(listOf("a", "b"), after.items())
        assertTrue(after.queueState.value.paused)
        val mark = core.calls.size

        after.resumeQueue()

        assertEquals(listOf("prompt.submit" to "s9"), callsAfter(mark))
        assertEquals(Json.parseToJsonElement("""{"session_id":"s9","text":"a","queued":true}"""), lastSubmit())
        assertEquals(listOf("b"), after.items())
    }

    @Test
    fun theQueueFollowsItsSessionsKeyAndGoesWithItsRow() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        assertEquals(listOf("a"), saved("db1"))

        core.emit("session.info", "s1", """{"stored_session_id":"db1b"}""")  // a compression rotated the key (D11)

        assertEquals(null, saved("db1"))
        assertEquals(listOf("a"), saved("db1b"))

        core.emit("message.complete", "s1", """{"text":"","status":"interrupted"}""")
        assertEquals(QueueUi(listOf(Queued(0, "a")), paused = true), savedQueues[stored("db1b")])
        chat.open()

        chat.delete(stored("db1b"))

        assertEquals("db1b", lastOf("session.delete").getValue("session_id").jsonPrimitive.content)
        assertFalse(stored("db1b") in savedQueues)
    }

    @Test
    fun aDrainedMessageTheCoreRefusesPausesTheRest() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        chat.queue("b")
        submit = { throw CoreError(4009, "session busy") }

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(listOf(Entry.User("a", stableId = 2), Entry.Notice("session busy", stableId = 3)), chat.state.value.entries.takeLast(2))
        assertFalse(chat.state.value.busy)
        assertEquals(listOf("b"), chat.items())
        assertTrue(chat.queueState.value.paused)  // no retry (D8)
        assertEquals(2, submits().size)
    }

    // Fix round 1: no change leaves an idle chat with an unpaused queue that never drains (Desktop drains whenever idle
    // and not parked, `use-composer-queue.ts:580-594`): after a completed turn the head goes out, else the queue pauses.

    @Test
    fun anEditThatOutlivesItsTurnsEndSendsTheItemOnceTheEditEnds() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        chat.beginEdit(chat.idOf("a"))

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(1, submits().size)  // the edited item waits (`use-composer-queue.ts:379-386`)
        assertEquals(listOf("a"), chat.items())
        assertFalse(chat.state.value.busy)
        val mark = core.calls.size

        chat.editQueued(chat.idOf("a"), "A2")  // Save: the edit ends with the chat idle after a completed turn

        assertEquals(listOf("prompt.submit" to "s1"), callsAfter(mark))
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"A2","queued":true}"""), lastSubmit())
        assertEquals(emptyList<String>(), chat.items())
        assertEquals(Entry.User("A2", stableId = 2), chat.state.value.entries.last())
        assertTrue(chat.state.value.submitted)

        core.emit("message.start", "s1")
        chat.queue("b")
        chat.beginEdit(chat.idOf("b"))
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")
        assertEquals(2, submits().size)

        chat.beginEdit(null)  // Cancel: the same

        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"b","queued":true}"""), lastSubmit())
        assertEquals(emptyList<String>(), chat.items())
        assertEquals(3, submits().size)
    }

    /** [core], with the first `prompt.submit` held until [answered] completes: the chat stays submitted meanwhile. */
    private fun submitGated(answered: CompletableDeferred<Unit>) = object : CoreGateway by core {
        override suspend fun <P, R> call(method: Method<P, R>, params: P): R {
            if (method.name == "prompt.submit") answered.await()
            return core.call(method, params)
        }
    }

    @Test
    fun aQueueLeftIdleByARefusedPromptOrALateRefusalIsPaused() = runTest(UnconfinedTestDispatcher()) {
        val answered = CompletableDeferred<Unit>()
        val chat = ChatSession(submitGated(answered), host, backgroundScope, keepSecret = { _, _ -> },
                               queues = QueueStore.inMemory(savedQueues)).apply { open() }
        val sending = launch { chat.send("hi") }
        chat.queue("a")  // before message.start: the turn is about to begin
        submit = { throw CoreError(4009, "session busy") }

        answered.complete(Unit)
        sending.join()

        assertFalse(chat.state.value.busy)
        assertEquals(listOf("a"), chat.items())
        assertTrue(chat.queueState.value.paused)  // the prompt failed: Resume shows (DC-M6)
        assertEquals(1, submits().size)
    }

    @Test
    fun aCorrectionRefusedAfterItsTurnEndedWaitsPaused() = runTest(UnconfinedTestDispatcher()) {
        busyWord = "steer"
        steer = { throw CoreError(4010, "agent does not support active-turn steer") }
        val answered = CompletableDeferred<Unit>()
        val chat = ChatSession(gated(answered), host, backgroundScope, keepSecret = { _, _ -> },
                               queues = QueueStore.inMemory(savedQueues)).apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        val correcting = launch { chat.send("use metric units") }
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        answered.complete(Unit)
        correcting.join()

        assertFalse(chat.state.value.busy)
        assertEquals(listOf("use metric units"), chat.items())
        assertTrue(chat.queueState.value.paused)  // it reached an idle chat: it waits for Resume, never stranded
        assertEquals(1, submits().size)
    }
}
