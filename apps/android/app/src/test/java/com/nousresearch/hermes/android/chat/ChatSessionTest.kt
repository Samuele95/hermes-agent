package com.nousresearch.hermes.android.chat

import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import com.nousresearch.hermes.android.entry.IncomingText
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    /** The core's `/status` text, as `session.status` answers it (its Model line after a failed agent build, M1). */
    private var statusReply = """{"output":"Hermes TUI Status\n\nSession ID: s1\nModel: (unknown) (unknown)\nAgent Running: No"}"""
    /** The stored keys the chat handed to the app's `session_export`, in order. */
    private val exported = mutableListOf<StoredSessionId>()
    private val exportFile = ExportFile("/c/exports/session-db1.json", "session-db1.json", 42L)
    private var busyWord = "interrupt"
    private var busy: () -> String = { """{"value":"$busyWord"}""" }
    private var steer: (JsonObject) -> String = { p -> """{"status":"queued","text":${p.getValue("text")}}""" }
    private var redirect: (JsonObject) -> String = { p -> """{"status":"redirected","text":${p.getValue("text")}}""" }
    /** The core's `message.react`: by default it stores nothing and answers no reactions. */
    private var react: (JsonObject) -> String = { p -> """{"row_id":${p.getValue("row_id")},"reactions":[]}""" }
    /** The core's `commands.catalog`, `complete.slash` and `command.dispatch` (chat-20): by default [CATALOG], no completion, a skill. */
    private var catalog: () -> String = { CATALOG }
    private var completion: (JsonObject) -> String = { """{"items":[],"replace_from":1}""" }
    private var dispatch: (JsonObject) -> String = { p -> skillOf(p) }
    /** What the chat's `/model` asked its picker to search, in order (P6, D11). */
    private val searched = mutableListOf<String>()
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
            "session.status" -> statusReply
            "message.react" -> react(params)
            "commands.catalog" -> catalog()
            "complete.slash" -> completion(params)
            "command.dispatch" -> dispatch(params)
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
    /** How often the chat said a turn failed on its provider's side, so readiness is checked again (F6). */
    private var failures = 0

    /** Whether the gateway's scheduler still owns a run, as the app's `cron_run_owned` answers; and the ids asked. */
    private var owned: suspend (StoredSessionId) -> Boolean? = { null }
    private val ownedAsked = mutableListOf<String>()

    /** The chat's queues as its store kept them, by stored key (the file adapter's stand-in). */
    private val savedQueues = mutableMapOf<StoredSessionId, QueueUi>()

    /** What the chat noted for diagnosis only, in order (the platform's log). */
    private val logged = mutableListOf<String>()

    /** The chat's wall clock (epoch ms), fixed; a test moves it on. */
    private var clockMs = 1_763_300_000_000L

    private fun TestScope.openChat() =
        ChatSession(core, host, backgroundScope, keepSecret = { name, value -> keepSecret(name, value) }, notifier = notifier,
                    gatewaySeen = { observed++; seen() }, runOwned = { ownedAsked += it.value; owned(it) },
                    queues = QueueStore.inMemory(savedQueues), providerFailed = { failures++ },
                    exporter = { exported += it; exportFile }, log = { logged += it }, clock = { clockMs }, openModels = { searched += it })

    /** The agent schedules a job in [session] ([action] of the job tool, answered `success`), the core's own warning included. */
    private suspend fun scheduled(session: String, id: String, action: String = "create") {
        core.emit("tool.start", session, """{"tool_id":"$id","name":"cronjob_manage"}""")
        core.emit("tool.complete", session, """{"tool_id":"$id","name":"cronjob_manage","summary":"$SCHEDULED",
            "args":{"action":"$action","schedule":"every 2m","prompt":"Say tick."},
            "result":{"success":true,"job_id":"ab12","gateway_running":false,
                      "warning":"The job will NOT fire until the gateway is started (hermes gateway install / hermes gateway start)."}}""")
    }

    /** The row [scheduled] leaves for job [id] created: the call's args and result as the core sent them (P3). */
    private fun scheduledRow(id: String, stableId: Long) =
        Entry.Tool(id, "cronjob_manage", SCHEDULED, done = true, stableId = stableId,
                   args = Json.parseToJsonElement("""{"action":"create","schedule":"every 2m","prompt":"Say tick."}""").jsonObject,
                   result = Json.parseToJsonElement("""{"success":true,"job_id":"ab12","gateway_running":false,
                       "warning":"The job will NOT fire until the gateway is started (hermes gateway install / hermes gateway start)."}""").toString())

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
        assertEquals(listOf(Entry.User("hello", stableId = 0)), chat.state.value.entries.untimed())
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
                            Entry.User("and tomorrow?", stableId = 2)), chat.state.value.entries.untimed())
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
        assertEquals(listOf(Entry.User("hello", stableId = 0)), chat.state.value.entries.untimed())

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

        assertEquals(listOf(Entry.User("hello", stableId = 0), Entry.Notice("session busy", stableId = 1)), chat.state.value.entries.untimed())
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

        assertEquals(listOf(Entry.User("hello", 41, 0)), chat.state.value.entries.untimed())
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
        assertEquals(listOf(Entry.User("hello", 52, stableId = 2)), chat.state.value.entries.untimed())  // the prompt again, a new row (0, 1 were cut)
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
        assertEquals(listOf(Entry.User("what's on my calendar?", 10, 0), Entry.Reply("Two meetings.", TurnStatus.COMPLETE, stableId = 1, rowId = 11),
                            Entry.User("and tomorrow?", 61, 4)), chat.state.value.entries.untimed())
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
                         chat.state.value.entries.untimed())
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
                         listOf(Entry.User("ok", stableId = 0), Entry.Reply("done", TurnStatus.COMPLETE, stableId = 1), Entry.User("ok", stableId = 2)), chat.state.value.entries.untimed())
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
        assertEquals(listOf(Entry.User("hello", 41, 3), Entry.Reply("hi there", TurnStatus.COMPLETE, stableId = 4, rowId = 42),
                            Entry.Notice("target user message is no longer in session history", stableId = 5)), chat.state.value.entries.untimed())
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
                            Entry.Notice("target user message is no longer in session history", stableId = 3)), chat.state.value.entries.untimed())
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

    /** An edit of prompt [id] to [words], as the editor sends it ([deep]: the user confirmed "Restore & rerun"). */
    private fun ChatSession.edit(id: Long, words: String, deep: Boolean = false) =
        Rewind(checkNotNull(state.value.editTarget(id)), words, deep)

    /** [chat]'s prompt [text] sent with row id [rowId], and answered [reply]. */
    private suspend fun answeredTurn(chat: ChatSession, text: String, rowId: Long?, reply: String) {
        submit = { if (rowId == null) """{"status":"streaming"}""" else """{"status":"streaming","user_row_id":$rowId}""" }
        chat.send(text)
        core.emit("message.start", chat.sessionId.value)
        core.emit("message.complete", chat.sessionId.value, """{"text":"$reply"}""")
    }

    @Test
    fun anEditOfTheLastPromptCutsBeforeItsRowAndSendsTheNewWords() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        answeredTurn(chat, "helo", 41, "hi")
        submit = { """{"status":"streaming","user_row_id":52}""" }
        val mark = core.calls.size

        chat.resend(chat.edit(0, "hello"))

        assertEquals(listOf("prompt.submit"), callsAfter(mark).map { it.first })
        val sent = lastSubmit()
        assertEquals("hello", sent.getValue("text").jsonPrimitive.content)
        assertEquals("41", sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_truncate").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_empty_truncate").jsonPrimitive.content)
        assertFalse("not deep: nothing after it", "confirm_deep_truncate" in sent)
        assertEquals(listOf(Entry.User("hello", 52, stableId = 2)), chat.state.value.entries.untimed())
    }

    @Test
    fun anOlderPromptIsCutOnlyWithTheUsersConsent() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        answeredTurn(chat, "a", 41, "A")
        answeredTurn(chat, "b", 43, "B")
        assertEquals(1, chat.state.value.laterTurns(checkNotNull(chat.state.value.editTarget(0))))
        val (mark, shown) = core.calls.size to chat.state.value.entries

        chat.resend(chat.edit(0, "a2", deep = false))

        assertEquals("no consent: nothing asked of the core (inv. 1)", mark, core.calls.size)
        assertEquals(shown, chat.state.value.entries)

        submit = { """{"status":"streaming","user_row_id":60}""" }
        chat.resend(chat.edit(0, "a2", deep = true))

        val sent = lastSubmit()
        assertEquals("41", sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_truncate").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_empty_truncate").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_deep_truncate").jsonPrimitive.content)
        assertEquals(listOf(Entry.User("a2", 60, 4)), chat.state.value.entries.untimed())
    }

    @Test
    fun aBusyChatIsStoppedBeforeTheEditIsCut() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("hello")
        core.emit("message.start", "s1")
        core.emit("message.delta", "s1", """{"text":"Hel"}""")
        submit = { """{"status":"streaming","user_row_id":52}""" }
        val (mark, shown) = core.calls.size to chat.state.value.entries

        val job = launch { chat.resend(chat.edit(0, "hello there")) }

        assertEquals("stopped first, nothing cut yet (inv. 2, D3)", listOf("session.interrupt" to "s1"), callsAfter(mark))
        assertTrue(chat.state.value.running)
        assertEquals(shown, chat.state.value.entries)

        core.emit("message.complete", "s1", """{"text":"Hel","status":"interrupted"}""")
        job.join()

        assertEquals(listOf("session.interrupt", "prompt.submit"), callsAfter(mark).map { it.first })
        assertEquals("41", lastSubmit().getValue("truncate_before_row_id").jsonPrimitive.content)
        // The stopped reply (id 1) was cut with its prompt.
        assertEquals(listOf(Entry.User("hello there", 52, 2)), chat.state.value.entries.untimed())
    }

    @Test
    fun anEditWhoseStoppedTurnNeverEndsIsNotSent() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("hello")
        core.emit("message.start", "s1")
        val mark = core.calls.size

        chat.resend(chat.edit(0, "hello there"))  // the test scheduler skips STOP_WAIT

        assertEquals(listOf("session.interrupt"), callsAfter(mark).map { it.first })
        assertEquals(listOf(Entry.User("hello", 41, 0), Entry.Notice("Edit failed: session busy", stableId = 1)),
                     chat.state.value.entries.untimed())
        assertTrue("its turn still runs (D3)", chat.state.value.running)
    }

    @Test
    fun anEditTheCoreFindsBusyStopsTheTurnAgainAndIsSentAgain() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        answeredTurn(chat, "hello", 41, "hi")
        var sends = 0
        submit = { if (++sends <= 2) throw CoreError(4009, "session busy") else """{"status":"streaming","user_row_id":52}""" }
        val mark = core.calls.size

        chat.resend(chat.edit(0, "hello there"))

        assertEquals(listOf("prompt.submit", "session.interrupt", "prompt.submit", "prompt.submit"), callsAfter(mark).map { it.first })
        assertEquals(listOf(Entry.User("hello there", 52, 2)), chat.state.value.entries.untimed())

        // The give-up: a core that stays busy is asked BUSY_RESENDS more times, then its words are the answer (D4).
        val other = openChat().apply { open() }
        answeredTurn(other, "hello", 41, "hi")
        submit = { throw CoreError(4009, "session busy") }
        history = { """{"count":2,"messages":[{"role":"user","text":"hello","row_id":41},{"role":"assistant","text":"hi","row_id":42}]}""" }
        val again = core.calls.size

        other.resend(other.edit(0, "hello there"))

        val after = callsAfter(again).map { it.first }
        assertEquals(1 + ChatSession.BUSY_RESENDS, after.count { it == "prompt.submit" })
        assertEquals(1, after.count { it == "session.interrupt" })
        assertEquals(Entry.Notice("Edit failed: session busy", stableId = 5), other.state.value.entries.last())
    }

    @Test
    fun anUnboundPromptIsFoundOrSentAgainAsRetryDoes() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        answeredTurn(chat, "helo", null, "hi")
        history = { """{"count":1,"messages":[{"role":"user","text":"helo","row_id":5}]}""" }
        val mark = core.calls.size

        chat.resend(chat.edit(0, "hello"))

        assertEquals(listOf("session.history" to "s1", "prompt.submit" to "s1"), callsAfter(mark))
        assertEquals("5", lastSubmit().getValue("truncate_before_row_id").jsonPrimitive.content)

        val other = openChat().apply { open() }
        answeredTurn(other, "helo", null, "hi")
        history = { """{"count":1,"messages":[{"role":"assistant","text":"hi","row_id":6}]}""" }

        other.resend(other.edit(0, "hello"))

        val sent = lastSubmit()
        assertFalse("no cut", "truncate_before_row_id" in sent)
        assertFalse("no consent", "confirm_truncate" in sent)
        assertEquals("appended (D2, B5-D1)", listOf(Entry.User("helo", stableId = 0), Entry.Reply("hi", TurnStatus.COMPLETE, stableId = 1),
                                                     Entry.User("hello", stableId = 2)), other.state.value.entries.untimed())
    }

    @Test
    fun aRefusedEditShowsTheCoresTranscriptAndSaysEditFailed() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        answeredTurn(chat, "hello", 41, "hi")
        submit = { throw CoreError(4018, "target user message is no longer in session history") }
        history = { """{"count":2,"messages":[{"role":"user","text":"hello","row_id":41},{"role":"assistant","text":"hi there","row_id":42}]}""" }

        chat.resend(chat.edit(0, "hello there"))

        assertEquals(listOf(Entry.User("hello", 41, 3), Entry.Reply("hi there", TurnStatus.COMPLETE, stableId = 4, rowId = 42),
                            Entry.Notice("Edit failed: This turn is no longer in server history (it may have been compressed away).", stableId = 5)),
                     chat.state.value.entries.untimed())
    }

    @Test
    fun anEditWhoseStoppedTurnNeverEndsIsRefusedWithItsReason() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("hello")
        core.emit("message.start", "s1")
        val mark = core.calls.size

        // The test scheduler skips STOP_WAIT.
        assertEquals(Resend.Refused("Edit failed: session busy"), chat.resend(chat.edit(0, "hello there")))

        assertEquals(listOf("session.interrupt"), callsAfter(mark).map { it.first })
        assertEquals(listOf(Entry.User("hello", 41, 0), Entry.Notice("Edit failed: session busy", stableId = 1)),
                     chat.state.value.entries.untimed())
        assertTrue("its turn still runs (D3)", chat.state.value.running)
    }

    @Test
    fun anEditWhosePromptMovedIsRefusedNeverDropped() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        answeredTurn(chat, "helo", 41, "hi")
        val stale = chat.edit(0, "hello")
        submit = { """{"status":"streaming","user_row_id":52}""" }
        assertEquals(Resend.Taken, chat.resend(chat.edit(0, "hullo")))
        val (mark, shown) = core.calls.size to chat.state.value.entries

        assertEquals("the prompt moved before admission", Resend.Refused("Edit failed"), chat.resend(stale))

        assertEquals("nothing asked of the core", mark, core.calls.size)
        assertEquals("nothing cut, no notice", shown, chat.state.value.entries)

        // Declined by the change that would cut: an older prompt without the user's consent (inv. 1).
        val older = openChat().apply { open() }
        answeredTurn(older, "a", 41, "A")
        answeredTurn(older, "b", 43, "B")
        val (before, kept) = core.calls.size to older.state.value.entries

        assertEquals(Resend.Refused("Edit failed"), older.resend(older.edit(0, "a2", deep = false)))

        assertEquals(before, core.calls.size)
        assertEquals(kept, older.state.value.entries)
    }

    @Test
    fun anEditWhoseChatLeftItsSessionAnswersLeft() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("hello")
        core.emit("message.start", "s1")
        val job = async { chat.resend(chat.edit(0, "hello there")) }  // the stop waits for the turn's end
        val mark = core.calls.size

        chat.open()  // a new session, not busy: the wait ends on another chat

        assertEquals(Resend.Left, job.await())
        assertTrue(callsAfter(mark).none { it.first == "prompt.submit" || it.first == "command.dispatch" })
        assertTrue("nothing said in the new chat", chat.state.value.entries.none { it is Entry.Notice })
    }

    @Test
    fun anEditTheCoreTakesAnswersTakenAndARefusedCutAnswersWhy() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        answeredTurn(chat, "helo", 41, "hi")
        submit = { """{"status":"streaming","user_row_id":52}""" }

        assertEquals(Resend.Taken, chat.resend(chat.edit(0, "hello")))

        val stale = openChat().apply { open() }
        answeredTurn(stale, "hello", 41, "hi")
        submit = { throw CoreError(4018, "target user message is no longer in session history") }
        history = { """{"count":2,"messages":[{"role":"user","text":"hello","row_id":41},{"role":"assistant","text":"hi there","row_id":42}]}""" }

        assertEquals(Resend.Refused("Edit failed: This turn is no longer in server history (it may have been compressed away)."),
                     stale.resend(stale.edit(0, "hello there")))

        val busy = openChat().apply { open() }
        answeredTurn(busy, "hello", 41, "hi")
        submit = { throw CoreError(4009, "session busy") }

        assertEquals(Resend.Refused("Edit failed: session busy"), busy.resend(busy.edit(0, "hello there")))
    }

    @Test
    fun aRetryTheCoreFindsBusyIsRefusedAsBefore() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        answeredTurn(chat, "hello", 41, "hi")
        submit = { throw CoreError(4009, "session busy") }
        history = { """{"count":2,"messages":[{"role":"user","text":"hello","row_id":41},{"role":"assistant","text":"hi there","row_id":42}]}""" }
        val mark = core.calls.size

        chat.retry()

        assertEquals("no interrupt, one submit (D5)", listOf("prompt.submit", "session.history"), callsAfter(mark).map { it.first })
        assertEquals(Entry.Notice("session busy", stableId = 5), chat.state.value.entries.last())
    }

    @Test
    fun aRetryOrAnEditThatCutsDropsTheAbandonedPlanAndKeepsTheMeter() = runTest(UnconfinedTestDispatcher()) {
        val plan = TodoList(listOf(Todo("1", "Boil", TodoStatus.COMPLETED), Todo("2", "Pour", TodoStatus.PENDING)), 4)
        val ways = listOf<Pair<String, suspend (ChatSession) -> Unit>>("retry" to { it.retry() }, "edit" to { it.resend(it.edit(0, "plan the coffee")) })
        for ((way, cut) in ways) {
            val chat = openChat().apply { open() }
            val session = chat.sessionId.value
            submit = { """{"status":"streaming","user_row_id":41}""" }
            chat.send("plan the tea")
            core.emit("message.start", session)
            core.emit("todo.updated", session, """{"todos":[{"id":"1","content":"Boil","status":"completed"},
                {"id":"2","content":"Pour","status":"pending"}],"revision":4}""")
            core.emit("session.usage", session, """{"usage":{"context_used":1000,"context_max":8000,"context_percent":13}}""")
            core.emit("message.complete", session, """{"text":"Half done."}""")
            assertEquals("$way: the plan is held before the cut", plan, chat.state.value.todos)
            submit = { """{"status":"streaming","user_row_id":52}""" }

            cut(chat)

            assertEquals("$way: a real cut", "41", lastSubmit().getValue("truncate_before_row_id").jsonPrimitive.content)
            assertNull("$way: the abandoned plan goes with its turn", chat.state.value.todos)
            assertEquals("$way: the meter stays (Desktop)", ContextMeter(1000, 8000, 13, false), chat.state.value.meter)

            core.emit("message.start", session)
            core.emit("todo.updated", session, """{"todos":[{"id":"1","content":"Steep","status":"in_progress"}],"revision":1}""")
            assertEquals("$way: the rerun's first plan, whatever its revision", TodoList(listOf(Todo("1", "Steep", TodoStatus.IN_PROGRESS)), 1),
                         chat.state.value.todos)
        }
    }

    @Test
    fun aRetrySentAgainWithoutACutKeepsThePlanOfTheTurnStillShown() = runTest(UnconfinedTestDispatcher()) {
        val plan = TodoList(listOf(Todo("1", "Boil", TodoStatus.COMPLETED), Todo("2", "Pour", TodoStatus.PENDING)), 4)
        val chat = openChat().apply { open() }
        val session = chat.sessionId.value
        submit = { """{"status":"streaming"}""" }
        chat.send("ok")
        core.emit("message.start", session)
        core.emit("todo.updated", session, """{"todos":[{"id":"1","content":"Boil","status":"completed"},
            {"id":"2","content":"Pour","status":"pending"}],"revision":4}""")
        core.emit("message.complete", session, """{"text":"done"}""")
        history = { """{"count":2,"messages":[{"role":"user","text":"ok","row_id":3},{"role":"user","text":"ok","row_id":7}]}""" }

        chat.retry()

        assertFalse("no cut", "truncate_before_row_id" in lastSubmit())
        assertEquals("the old turn stays", Entry.Reply("done", TurnStatus.COMPLETE, stableId = 1), chat.state.value.entries.untimed()[1])
        assertEquals("so does its plan", plan, chat.state.value.todos)
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
        assertEquals(listOf(Entry.User("hi", stableId = 0), Entry.Reply("hello", TurnStatus.COMPLETE, stableId = 1)), chat.state.value.entries.untimed())
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

        assertEquals(listOf(scheduledRow("t1", stableId = 0),
                            Entry.Notice(ChatSession.JOBS_NEED_GATEWAY, NoticeShortcut.GATEWAY, 1)), chat.state.value.entries)

        scheduled("s1", "t2")  // a second job in the same conversation is not said again
        core.emit("message.complete", "s1", """{"text":"Done: two jobs."}""")

        assertEquals(1, chat.jobNotices().size)
        assertEquals(Entry.Reply("Done: two jobs.", TurnStatus.COMPLETE, stableId = 3), chat.state.value.entries.last().untimed())
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
        assertEquals(scheduledRow("t3", stableId = 2), chat.state.value.entries.first())

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
        assertEquals(Entry.Reply("Scheduled.", TurnStatus.COMPLETE, stableId = 1), chat.state.value.entries.last().untimed())  // the chat still reduces

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
        /** A skill's expanded body, as `command.dispatch` answers it: model-facing, never shown (chat-20). */
        const val BODY = "[Skill: github] Use the gh CLI."
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
                     chat.state.value.entries.last().untimed())
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
                     chat.state.value.entries.last().untimed())
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
        assertEquals(Entry.User("use metric units", stableId = 1, mode = BusyMode.STEER), chat.state.value.entries.last().untimed())

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(2, submits().size)
        assertEquals("use metric units", lastSubmit().getValue("text").jsonPrimitive.content)
        val entries = chat.state.value.entries
        assertEquals(Entry.User("use metric units", stableId = 1), entries.single { it.stableId == 1L }.untimed())
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
        assertEquals(Entry.User("use metric units", stableId = 1), chat.state.value.entries.last().untimed())
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
        assertEquals(Entry.User("use metric units", stableId = 1, mode = BusyMode.STEER), entries.single { it.stableId == 1L }.untimed())
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
        assertEquals(Entry.User("a", stableId = 2), chat.state.value.entries.last().untimed())
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

    /** The line the chat logs when the core holds a drained item in its own queue (/architect #5). */
    private val heldByTheCore = "prompt.submit queued:true in s1 answered queued: the core runs it after its own turn, no user_row_id"

    @Test
    fun aDrainedItemTheCoreQueuedIsLoggedAndLeftToTheCore() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        chat.queue("b")
        submit = { """{"status":"queued"}""" }  // the core's turn is still ending: it holds the item for its own next turn
        val mark = core.calls.size

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(listOf("prompt.submit" to "s1"), callsAfter(mark))  // sent once: no resend, no history read
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"a","queued":true}"""), lastSubmit())
        assertEquals(listOf(heldByTheCore), logged)
        assertEquals(Entry.User("a", stableId = 2), chat.state.value.entries.last().untimed())  // no row id yet
        assertTrue(chat.state.value.submitted)
        assertTrue(chat.state.value.entries.none { it is Entry.Notice })  // not a failure
        assertEquals(listOf("b"), chat.items())
        assertEquals(listOf("b"), saved("db1"))

        // The core runs it: its turn's receipt names the row.
        submit = { """{"status":"streaming","user_row_id":9}""" }
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete",
            "persisted_turn":{"row_ids":[7,8],"complete":true,"user_row_id":7}}""")

        assertTrue(chat.state.value.entries.toString(), Entry.User("a", 7, 2) in chat.state.value.entries.untimed())
        assertEquals("b", lastSubmit().getValue("text").jsonPrimitive.content)  // the queue moved on once
        assertEquals("true", lastSubmit().getValue("queued").jsonPrimitive.content)
        assertEquals(1, logged.size)
    }

    @Test
    fun aDrainedItemTheCoreRanIsNotLogged() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        chat.queue("b")
        submit = { """{"status":"streaming","user_row_id":41}""" }

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(emptyList<String>(), logged)
        assertEquals(Entry.User("a", 41, 2), chat.state.value.entries.last().untimed())
    }

    @Test
    fun aPlainPromptTheCoreAnswersQueuedIsNotLoggedAsADrain() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        submit = { """{"status":"queued"}""" }

        chat.send("hi")

        assertFalse("queued" in lastSubmit())
        assertEquals(emptyList<String>(), logged)  // V4: busy-mode routing, another race
    }

    @Test
    fun aDrainedItemTheCoreQueuedIsRetriedByItsWords() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("a")
        submit = { """{"status":"queued"}""" }
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")
        // The core's turn for it, with no receipt (a compaction, a preflight return).
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"Again.","status":"complete"}""")
        history = { """{"count":4,"messages":[{"role":"user","text":"hi","row_id":3},{"role":"assistant","text":"Done.","row_id":4},
                       {"role":"user","text":"a","row_id":5},{"role":"assistant","text":"Again.","row_id":6}]}""" }
        submit = { """{"status":"streaming","user_row_id":12}""" }
        val mark = core.calls.size

        chat.retry()

        assertEquals(listOf("session.history" to "s1", "prompt.submit" to "s1"), callsAfter(mark))
        assertEquals("5", lastSubmit().getValue("truncate_before_row_id").jsonPrimitive.content)
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
                     chat.state.value.entries.last().untimed())

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

        assertEquals(listOf(Entry.User("a", stableId = 2), Entry.Notice("session busy", stableId = 3)), chat.state.value.entries.takeLast(2).untimed())
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
        assertEquals(Entry.User("A2", stableId = 2), chat.state.value.entries.last().untimed())
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

    @Test
    fun anOfferIsADraftAndIsNeverSent() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val offer = IncomingText("Read this", "A page")

        chat.offer(offer)

        assertEquals(offer, chat.state.value.offer)
        assertEquals(emptyList<Entry>(), chat.state.value.entries)
        assertTrue(core.calls.none { it.first == "prompt.submit" })  // inv. 6: an offer is a draft, never a send

        // While a turn runs, a newer offer neither corrects the turn nor queues itself.
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.offer(IncomingText("more words"))

        assertEquals(IncomingText("more words"), chat.state.value.offer)
        assertEquals(1, core.calls.count { it.first == "prompt.submit" })  // the user's own "hi" alone
        assertTrue(core.calls.none { it.first == "session.steer" || it.first == "session.redirect" })
        assertEquals(emptyList<Queued>(), chat.queueState.value.items)
    }

    @Test
    fun anOfferTakenIsGoneButANewerOneStays() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val a = IncomingText("a")
        val b = IncomingText("b")

        chat.offer(a)
        chat.offerTaken(a)
        assertNull(chat.state.value.offer)

        // A second share landing between the take and the clear is not lost (D3).
        chat.offer(a)
        chat.offer(b)
        chat.offerTaken(a)
        assertEquals(b, chat.state.value.offer)
    }

    @Test
    fun anUntakenOfferFollowsTheChatIntoANewSession() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val offer = IncomingText("shared")

        chat.offer(offer)
        chat.open()

        assertEquals(runtime("s2"), chat.sessionId)
        assertEquals(offer, chat.state.value.offer)  // D7: New chat moves the words, they are not left behind
    }

    // A turn that failed on its provider's side asks for a readiness check (F6, bet 10); no other end does (D7).

    /** A turn in [session] that ends failed, its `error_surface` naming [layer]. */
    private suspend fun failedOn(layer: String, session: String = "s1") {
        core.emit("message.start", session)
        core.emit("message.complete", session, """{"text":"","status":"error","error":"boom",
            "error_surface":{"layer":"$layer","code":"auth","retryable":false}}""")
    }

    @Test
    fun aTurnThatFailsOnItsKeyTheProviderOrBillingAsksForACheck() = runTest(UnconfinedTestDispatcher()) {
        openChat().open()

        for ((i, layer) in listOf("auth", "provider", "billing").withIndex()) {
            failedOn(layer)
            assertEquals(layer, i + 1, failures)
        }
    }

    @Test
    fun otherEndsOfATurnAskForNoCheck() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"Hello","status":"complete"}""")
        assertEquals("a complete reply", 0, failures)
        for (layer in listOf("gateway", "endpoint", "streaming", "runtime", "disk")) {
            failedOn(layer)
            assertEquals(layer, 0, failures)
        }
        core.emit("message.start", "s1")
        core.emit("error", "s1", """{"message":"not connected to any AI provider"}""")
        assertEquals("the bare error event", 0, failures)
        failedOn("auth", session = "s9")
        assertEquals("another session's failure", 0, failures)
        assertFalse(chat.state.value.busy)
    }

    // The out-of-credits wall leaves on Dismiss and stays behind on a session switch (inv. 5, DC-M12).

    /** A turn in [session] the provider refused for billing, as `prompt_turn.py` completes it (ChatTest's BILLED shape). */
    private suspend fun outOfCredits(session: String = "s1") {
        core.emit("message.start", session)
        core.emit("message.complete", session, """{"text":"Billing or credits exhausted: 402","status":"error",
            "error":"402 Insufficient Balance","recoverable":true,"failure_reason":"billing",
            "error_surface":{"layer":"billing","code":"billing","retryable":false},
            "billing":{"provider":"deepseek","provider_label":"DeepSeek","model":"deepseek-v4-pro",
                       "billing_url":"https://platform.deepseek.com/top_up","is_nous":false,
                       "message":"DeepSeek reported that billing, credits, or account entitlement is exhausted for deepseek-v4-pro."}}""")
    }

    @Test
    fun dismissingTheWallClearsItAndSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        outOfCredits()
        assertNotNull(chat.state.value.wall)

        val calls = core.calls.size
        chat.dismissWall()

        assertNull(chat.state.value.wall)
        assertEquals(calls, core.calls.size)
        assertEquals(TurnStatus.ERROR, (chat.state.value.entries.last() as Entry.Reply).status)  // the failed reply stays
        val dismissed = chat.state.value
        chat.dismissWall()
        assertSame(dismissed, chat.state.value)
    }

    @Test
    fun aSessionSwitchLeavesTheWallBehind() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        outOfCredits()

        chat.open()  // New chat
        assertEquals(runtime("s2"), chat.sessionId)
        assertNull(chat.state.value.wall)

        outOfCredits("s2")
        assertNotNull(chat.state.value.wall)
        chat.resume(stored("db-old"))
        assertNull(chat.state.value.wall)

        outOfCredits("s9")  // a session the chat does not follow
        assertNull(chat.state.value.wall)
    }

    @Test
    fun theChatsOwnExportUsesItsStoredKeyAfterARotation() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        core.emit("session.info", "s1", """{"stored_session_id":"db1b"}""")  // a compression rotated the key

        val file = chat.export(null)

        assertEquals(listOf(stored("db1b")), exported)
        assertEquals(exportFile, file)
    }

    @Test
    fun aRowsExportUsesThatRowsKey() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        chat.export(stored("db-old"))

        assertEquals(listOf(stored("db-old")), exported)
        assertEquals(runtime("s1"), chat.sessionId)  // a row's export does not switch the chat
    }

    @Test
    fun withoutASessionThereIsNothingToExport() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat()

        val refused = runCatching { chat.export(null) }.exceptionOrNull()

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.NOTHING_TO_EXPORT, refused?.message)
        assertEquals(emptyList<StoredSessionId>(), exported)
    }

    @Test
    fun theChatsOwnExportWaitsForACompression() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().answered()
        var refused: Throwable? = null
        compressReply = {
            refused = runCatching { runBlocking { chat.export(null) } }.exceptionOrNull()
            COMPRESSED
        }

        chat.compress(null)

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.STILL_COMPRESSING, refused?.message)
        assertEquals(emptyList<StoredSessionId>(), exported)
        chat.export(null)  // once it has finished, the export goes through
        assertEquals(1, exported.size)
    }

    @Test
    fun theStatusIsTheCoresTextForTheChatsRuntimeSession() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }  // runtime s1, stored db1

        val text = chat.status()

        assertEquals("Hermes TUI Status\n\nSession ID: s1\nModel: (unknown) (unknown)\nAgent Running: No", text)
        assertEquals("session.status" to "s1", callsAfter(core.calls.size - 1).single())
    }

    @Test
    fun withoutASessionThereIsNoStatus() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat()

        val refused = runCatching { chat.status() }.exceptionOrNull()

        assertTrue("$refused", refused is SetupRefusal)
        assertEquals(ChatSession.NO_STATUS, refused?.message)
        assertEquals(emptyList<Pair<String, JsonObject>>(), core.calls)
    }

    @Test
    fun aSentPromptAQueuedOneAndAReplyTakeTheChatsClock() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        assertEquals(1_763_300_000_000, (chat.state.value.entries.single() as Entry.User).at)

        core.emit("message.start", "s1")
        chat.queue("a")
        clockMs += 9_000
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        val entries = chat.state.value.entries
        val reply = entries.filterIsInstance<Entry.Reply>().single()
        assertEquals(1_763_300_009_000, reply.at)
        assertEquals(9_000L, reply.tookMs)
        val drained = entries.last() as Entry.User
        assertEquals("a", drained.text)
        assertEquals("the queue's head takes the time it went out", 1_763_300_009_000, drained.at)
    }

    @Test
    fun aResumedSessionKeepsItsTimesRowIdsToolDetailAndTheUsersReactions() = runTest(UnconfinedTestDispatcher()) {
        val info = """"info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT},"model":"kimi-k2"}"""
        resume = { """{"session_id":"s-resumed","message_count":3,"session_key":"db-old-tip",
                     "messages":[{"role":"user","text":"list the files","row_id":20,"timestamp":1763300000.0},
                                 {"role":"tool","name":"terminal","tool_call_id":"c1","context":"ls","args":{"command":"ls"},"timestamp":1763300001.5},
                                 {"role":"assistant","text":"Two files.","row_id":22,"timestamp":1763300003.0,
                                  "display_metadata":{"reactions":[{"emoji":"👍","author":"user","at":1763300010.0}]}}],$info}""" }
        val chat = openChat().apply { open() }

        chat.resume(stored("db-old"))

        val id = chat.state.value.entries.first().stableId
        assertEquals(listOf(Entry.User("list the files", 20, id, at = 1_763_300_000_000),
                            Entry.Tool("c1", "terminal", "ls", done = true, stableId = id + 1, args = buildJsonObject { put("command", "ls") }),
                            Entry.Reply("Two files.", TurnStatus.COMPLETE, stableId = id + 2, rowId = 22, at = 1_763_300_003_000,
                                        reactions = listOf(Reaction("👍", Author.USER)))),
                     chat.state.value.entries)

        // A turn still running when resumed: the core's turn_started_at is not this process seeing it start (invariant 7).
        resume = { """{"session_id":"s-resumed","message_count":3,"session_key":"db-old-tip","messages":[],
                     "running":true,"turn_started_at":1763299990.0,"inflight":{"assistant":"Two"},$info}""" }
        chat.resume(stored("db-old"))
        core.emit("message.complete", "s-resumed", """{"text":"Two files."}""")

        val reply = chat.state.value.entries.last() as Entry.Reply
        assertNull(reply.tookMs)
        assertEquals(clockMs, reply.at)
    }

    /** A completed answer whose rows the core named (`persisted_turn`): the reply's row is 22. */
    private suspend fun ChatSession.storedAnswer(): Long {
        send("hi")
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete","persisted_turn":{"row_ids":[21,22],"complete":true,"user_row_id":21,"final_assistant_row_id":22}}""")
        return state.value.entries.last().stableId
    }

    private fun ChatSession.reactionsOn(id: Long) = (state.value.entries.single { it.stableId == id } as Entry.Reply).reactions

    @Test
    fun aReactionIsShownAtOnceSentAsTheUsersAndTheCoresAnswerReplacesIt() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val id = chat.storedAnswer()
        var shown: Chat? = null
        react = { shown = chat.state.value
                  """{"row_id":22,"reactions":[{"emoji":"❤️","author":"user","at":1.0},{"emoji":"👍","author":"agent","at":2.0}]}""" }

        chat.react(id, "❤️")

        assertEquals("painted before the core answered", listOf(Reaction("❤️", Author.USER)),
                     (shown!!.entries.single { it.stableId == id } as Entry.Reply).reactions)
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","row_id":22,"emoji":"❤️","author":"user"}"""), lastOf("message.react"))
        assertEquals("the core's list, the user's own only", listOf(Reaction("❤️", Author.USER)), chat.reactionsOn(id))
        assertTrue(chat.state.value.entries.none { it is Entry.Notice })
    }

    @Test
    fun theSameEmojiAgainOrAClearRetractsIt() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val id = chat.storedAnswer()
        react = { """{"row_id":22,"reactions":[{"emoji":"❤️","author":"user","at":1.0}]}""" }
        chat.react(id, "❤️")
        assertEquals(listOf(Reaction("❤️", Author.USER)), chat.reactionsOn(id))

        var during: List<Reaction>? = null
        react = { during = chat.reactionsOn(id); """{"row_id":22,"reactions":[]}""" }
        chat.react(id, "❤️")

        assertEquals("the same emoji again is painted as retracted", emptyList<Reaction>(), during)
        assertEquals("❤️", lastOf("message.react").getValue("emoji").jsonPrimitive.content)
        assertEquals(emptyList<Reaction>(), chat.reactionsOn(id))

        chat.react(id, null)

        assertFalse("a clear sends no emoji", "emoji" in lastOf("message.react"))
        assertEquals(emptyList<Reaction>(), chat.reactionsOn(id))
    }

    @Test
    fun aRefusedReactionRollsBackAndSaysWhy() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val id = chat.storedAnswer()
        react = { throw CoreError(4040, "message not found in this session") }

        chat.react(id, "👍")

        assertEquals(emptyList<Reaction>(), chat.reactionsOn(id))
        assertEquals("Could not react: message not found in this session", (chat.state.value.entries.last() as Entry.Notice).text)

        react = { throw IllegalStateException("boom") }
        chat.react(id, "👍")

        assertEquals(emptyList<Reaction>(), chat.reactionsOn(id))
        assertEquals("Could not react: Something went wrong (IllegalStateException).", (chat.state.value.entries.last() as Entry.Notice).text)
    }

    @Test
    fun anAnswerWithoutARowOrInAJobsRunSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")
        val id = chat.state.value.entries.last().stableId

        chat.react(id, "❤️")
        chat.react(99, "❤️")

        assertTrue(core.calls.none { it.first == "message.react" })
        assertEquals(emptyList<Reaction>(), chat.reactionsOn(id))
        assertTrue(chat.state.value.entries.none { it is Entry.Notice })
    }

    // The slash menu (chat-20, bet 9): a `/` line the catalog knows runs through command.dispatch or the app's own
    // controls, never reaching the model as typed; while a turn runs it waits in the queue as typed (DC-P12, DC-P13).

    /** The core's skill directive for a dispatch of [p]: the body for the model, the invocation for the bubble. */
    private fun skillOf(p: JsonObject): String {
        val arg = p["arg"]?.jsonPrimitive?.content.orEmpty()
        return buildJsonObject {
            put("type", "skill"); put("message", "$BODY $arg"); put("name", "github"); put("display", "/github $arg")
        }.toString()
    }

    /** Each `command.dispatch` the chat made: the name and the arg it sent (null: left out). */
    private fun dispatches() = core.calls.filter { it.first == "command.dispatch" }
        .map { it.second.getValue("name").jsonPrimitive.content to it.second["arg"]?.jsonPrimitive?.content }

    /** The `prompt.submit` texts that start with a slash: a line sent as typed (invariant 4). */
    private fun slashSubmits() = submits().map { it.second.getValue("text").jsonPrimitive.content }.filter { it.startsWith("/") }

    private fun ChatSession.lastNotice() = (state.value.entries.last() as Entry.Notice).text

    @Test
    fun aSkillLineIsDispatchedTheModelGetsItsBodyAndTheBubbleShowsTheInvocation() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val mark = core.calls.size

        chat.send("/github fix it")

        assertEquals(listOf("commands.catalog", "command.dispatch", "prompt.submit"), callsAfter(mark).map { it.first })
        assertEquals(Json.parseToJsonElement("""{"name":"github","arg":"fix it","session_id":"s1"}"""), lastOf("command.dispatch"))
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"$BODY fix it"}"""), lastSubmit())
        assertEquals(emptyList<String>(), slashSubmits())
        assertEquals(Entry.User("/github fix it", stableId = 0), chat.state.value.entries.last().untimed())
        assertTrue(chat.state.value.submitted)
    }

    @Test
    fun aPrefillFillsTheDraftWithTheCoresTranscriptAndSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        dispatch = { """{"type":"prefill","message":"earlier words","notice":"↶ Undid 1 turn (2 message(s))."}""" }
        history = { """{"count":2,"messages":[{"role":"user","text":"first"},{"role":"assistant","text":"one"}]}""" }
        val chat = openChat().apply { open() }

        chat.send("/undo")

        assertEquals(emptyList<Any>(), submits())
        assertEquals(1, core.calls.count { it.first == "session.history" })
        val entries = chat.state.value.entries
        assertEquals(3, entries.size)
        assertEquals("first", (entries[0] as Entry.User).text)
        assertEquals("one", (entries[1] as Entry.Reply).text)
        assertEquals("↶ Undid 1 turn (2 message(s)).", (entries[2] as Entry.Notice).text)
        assertEquals("earlier words", chat.state.value.prefill)
        assertFalse(chat.state.value.busy)

        chat.prefillTaken("earlier words")
        assertNull(chat.state.value.prefill)

        dispatch = { """{"type":"prefill","message":"newer words"}""" }
        chat.send("/undo")
        chat.prefillTaken("other")
        assertEquals("a newer prefill stays", "newer words", chat.state.value.prefill)
    }

    @Test
    fun outputIsANoticeAndARefusalSaysWhyInTheCoresWords() = runTest(UnconfinedTestDispatcher()) {
        dispatch = { """{"type":"exec","output":"No pending memory writes."}""" }
        val chat = openChat().apply { open() }

        chat.send("/memory pending")

        assertEquals("No pending memory writes.", chat.lastNotice())
        assertEquals(listOf("memory" to "pending"), dispatches())
        assertFalse(chat.state.value.busy)
        assertTrue(chat.state.value.entries.none { it is Entry.User })

        dispatch = { throw CoreError(4004, "usage: /goal [text | …]") }
        chat.send("/goal")
        assertEquals("/goal: usage: /goal [text | …]", chat.lastNotice())
        assertEquals("a blank arg is left out", "goal" to null, dispatches().last())
        assertFalse(chat.state.value.busy)

        dispatch = { """{"type":"send","message":"  "}""" }
        chat.send("/goal")
        assertEquals("/goal: empty message", chat.lastNotice())
        assertFalse(chat.state.value.busy)
        assertEquals(emptyList<Any>(), submits())
    }

    @Test
    fun anAliasRunsItsTargetOnceAndNeverLoops() = runTest(UnconfinedTestDispatcher()) {
        dispatch = { p -> if (p.getValue("name").jsonPrimitive.content == "hi") """{"type":"alias","target":"github"}""" else skillOf(p) }
        val chat = openChat().apply { open() }

        chat.send("/hi fix it")

        assertEquals(listOf("hi" to "fix it", "github" to "fix it"), dispatches())
        assertEquals(listOf("$BODY fix it"), submits().map { it.second.getValue("text").jsonPrimitive.content })
        turn()

        dispatch = { """{"type":"alias","target":"github"}""" }
        chat.send("/hi x")

        assertEquals(4, dispatches().size)  // /hi, then /github once: no third hop
        assertEquals("/github is not available in the Android app.", chat.lastNotice())
        assertFalse(chat.state.value.busy)
        assertEquals(1, submits().size)
    }

    @Test
    fun theAppsOwnCommandsCallTheAppsControlsAndNeverDispatch() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }

        chat.send("/retry")
        assertEquals(ChatSession.NOTHING_TO_RETRY, chat.lastNotice())

        chat.send("/new")
        assertEquals(2, core.calls.count { it.first == "session.create" })
        assertEquals(runtime("s2"), chat.sessionId)

        chat.send("/compress keep the plan")
        assertEquals("keep the plan", lastOf("session.compress").getValue("focus_topic").jsonPrimitive.content)
        assertEquals("Nothing to compress.", chat.lastNotice())

        chat.send("hi")
        turn("s2")
        chat.send("/retry")
        assertEquals("hi", lastSubmit().getValue("text").jsonPrimitive.content)
        assertEquals(2, submits().size)

        chat.send("/model kimi")
        assertEquals(listOf("kimi"), searched)

        assertEquals(emptyList<Any>(), dispatches())
        assertEquals(emptyList<String>(), slashSubmits())
    }

    @Test
    fun steerAndQueueAreTheBusyTimeSends() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")

        chat.send("/steer go left")
        assertEquals("go left", lastOf("session.steer").getValue("text").jsonPrimitive.content)
        val steered = chat.state.value.entries.last() as Entry.User
        assertEquals("go left" to BusyMode.STEER, steered.text to steered.mode)

        chat.send("/queue later")
        assertEquals(listOf("later"), chat.items())
        assertEquals(1, submits().size)

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")  // "later" goes out
        turn()
        chat.send("/queue now")
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"now"}"""), lastSubmit())

        turn()
        chat.send("/steer x")
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"x"}"""), lastSubmit())

        chat.send("/steer")
        assertEquals(ChatSession.STEER_USAGE, chat.lastNotice())
        chat.send("/queue")
        assertEquals(ChatSession.QUEUE_USAGE, chat.lastNotice())
        assertEquals(emptyList<Any>(), dispatches())
    }

    @Test
    fun aSlashLineTypedWhileATurnRunsWaitsInTheQueueAsTypedAndIsDispatchedAtItsEnd() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }  // busy mode interrupt: a plain busy send would redirect
        chat.send("hi")
        core.emit("message.start", "s1")
        val mark = core.calls.size
        val shown = chat.state.value.entries

        chat.send("/github fix it")

        assertEquals(listOf("commands.catalog"), callsAfter(mark).map { it.first })  // no session.redirect, no command.dispatch
        assertEquals(listOf("/github fix it"), chat.items())
        assertEquals(shown, chat.state.value.entries)

        chat.send("/status")
        assertEquals("/status is not available in the Android app.", chat.lastNotice())
        assertEquals(listOf("/github fix it"), chat.items())

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(listOf("commands.catalog", "command.dispatch", "prompt.submit"), callsAfter(mark).map { it.first })
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"$BODY fix it","queued":true}"""), lastSubmit())
        assertEquals("/github fix it", (chat.state.value.entries.last { it is Entry.User } as Entry.User).text)
        assertEquals(emptyList<String>(), chat.items())
        assertEquals(emptyList<String>(), slashSubmits())
    }

    @Test
    fun anUnknownSlashIsTextAndAnUnreadableCatalogSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        val mark = core.calls.size

        chat.send("/sdcard/notes.txt sum it up")

        assertEquals(listOf("commands.catalog", "prompt.submit"), callsAfter(mark).map { it.first })
        assertEquals("/sdcard/notes.txt sum it up", lastSubmit().getValue("text").jsonPrimitive.content)
        turn()
        chat.send("/tmp x")
        assertEquals(1, core.calls.count { it.first == "commands.catalog" })  // one per session
        assertEquals("/tmp x", lastSubmit().getValue("text").jsonPrimitive.content)
        turn()

        catalog = { throw CoreError(5020, "skill discovery unavailable") }
        chat.open()  // a new session: its own catalog
        val sent = submits().size

        chat.send("/github x")

        assertEquals(sent, submits().size)
        assertEquals("Commands could not be read: skill discovery unavailable", chat.lastNotice())
        assertFalse(chat.state.value.busy)
    }

    @Test
    fun theMenuReadsTheCatalogAtEachOpeningAndNarrowsWithCompleteSlash() = runTest(UnconfinedTestDispatcher()) {
        assertEquals("no session, no menu", emptyList<SlashItem>(), openChat().slashItems("/"))
        val chat = openChat().apply { open() }
        val mark = core.calls.size

        val menu = chat.slashItems("/")

        assertEquals(listOf("commands.catalog" to "s1"), callsAfter(mark))
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1"}"""), lastOf("commands.catalog"))
        assertEquals(listOf("/new", "/model", "/compress", "/retry", "/steer", "/queue", "/undo", "/goal", "/memory", "/hi", "/plug",
                            "/worktree-kickoff", "/github"), menu.map { it.name })

        completion = { """{"items":[{"text":"github","kind":"skill"}],"replace_from":1}""" }
        assertEquals(listOf("/github "), chat.slashItems("/gi").map { it.fill })
        assertEquals(Json.parseToJsonElement("""{"text":"/gi","session_id":"s1"}"""), lastOf("complete.slash"))
        assertEquals(1, core.calls.count { it.first == "commands.catalog" })

        chat.slashItems("/")
        assertEquals("each opening reads it again (D5)", 2, core.calls.count { it.first == "commands.catalog" })

        completion = { throw CoreError(5020, "prompt_toolkit unavailable") }
        assertEquals(listOf("/github"), chat.slashItems("/git").map { it.name })

        val before = core.calls.size
        assertEquals(emptyList<SlashItem>(), chat.slashItems("hi"))
        assertEquals(emptyList<SlashItem>(), chat.slashItems("/a\nb"))
        assertEquals(before, core.calls.size)
    }

    /** The texts of every `prompt.submit`, in order. */
    private fun submitted() = submits().map { it.second.getValue("text").jsonPrimitive.content }

    @Test
    fun aQueuedAppCommandActsBeforeTheNextHeadLeaves() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("/retry")  // the Queue button: the line as typed
        chat.queue("more")

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals("/retry sent hi again, and more waits for that turn", listOf("hi", "hi"), submitted())
        assertEquals(listOf("more"), chat.items())
        assertTrue(chat.state.value.entries.none { it is Entry.Notice })  // never "There is nothing to retry now."
        turn()
        assertEquals(Json.parseToJsonElement("""{"session_id":"s1","text":"more","queued":true}"""), lastSubmit())
        assertEquals(emptyList<String>(), chat.items())
        turn()

        // An alias whose target is an app command, reached from the drain, holds the queue the same way.
        dispatch = { p -> if (p.getValue("name").jsonPrimitive.content == "hi") """{"type":"alias","target":"retry"}""" else skillOf(p) }
        chat.send("again")
        core.emit("message.start", "s1")
        chat.queue("/hi")
        chat.queue("after")

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(listOf("hi" to null), dispatches())
        assertEquals(listOf("hi", "hi", "more", "again", "again"), submitted())
        assertEquals(listOf("after"), chat.items())
        assertTrue(chat.state.value.entries.none { it is Entry.Notice })
        turn()
        assertEquals("after", submitted().last())
        assertEquals(emptyList<String>(), slashSubmits())
    }

    @Test
    fun aQueuedCompressOrNewChatKeepsTheRestOfTheQueueOffTheCoreUntilItHasActed() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.queue("/compress")
        chat.queue("more")

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(1, core.calls.count { it.first == "session.compress" })  // it compressed: never "still replying"
        assertEquals("Nothing to compress.", chat.lastNotice())
        assertEquals("a notice since the turn: the rest pauses for Resume", listOf("more"), chat.items())
        assertTrue(chat.queueState.value.paused)
        assertEquals(listOf("hi"), submitted())

        chat.deleteQueued(chat.idOf("more"))
        chat.send("again")
        core.emit("message.start", "s1")
        chat.queue("/new")
        chat.queue("later")

        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(runtime("s2"), chat.sessionId)
        assertEquals("later stays with the chat it was queued in", listOf("hi", "again"), submitted())
        assertEquals(listOf("later"), saved("db1"))
        assertEquals(emptyList<String>(), chat.items())
    }

    @Test
    fun aQueuedSlashLineNeverSteersTheTurnAsWords() = runTest(UnconfinedTestDispatcher()) {
        val chat = openChat().apply { open() }  // busy mode interrupt: Steer now would redirect
        chat.send("hi")
        core.emit("message.start", "s1")
        chat.send("/github fix it")  // waits in the queue as typed (invariant 5)
        chat.queue("/q later")  // the Queue button: an app command, by its alias
        chat.queue("/status")  // listed by the core, not run on the phone
        chat.queue("/sdcard/notes.txt sum it up")  // a path: words after all
        val mark = core.calls.size

        chat.steerNow(chat.idOf("/github fix it"))
        assertEquals("/github is a command and cannot steer the turn; it stays in the queue.", chat.lastNotice())
        chat.steerNow(chat.idOf("/q later"))
        assertEquals("/queue is a command and cannot steer the turn; it stays in the queue.", chat.lastNotice())
        chat.steerNow(chat.idOf("/status"))
        assertEquals("/status is a command and cannot steer the turn; it stays in the queue.", chat.lastNotice())

        assertEquals(emptyList<Pair<String, String?>>(), callsAfter(mark))  // no session.redirect, no session.steer
        assertEquals(listOf("/github fix it", "/q later", "/status", "/sdcard/notes.txt sum it up"), chat.items())

        chat.steerNow(chat.idOf("/sdcard/notes.txt sum it up"))
        assertEquals(listOf("session.redirect" to "s1"), callsAfter(mark))
        assertEquals("/sdcard/notes.txt sum it up", lastOf("session.redirect").getValue("text").jsonPrimitive.content)
        assertEquals(listOf("/github fix it", "/q later", "/status"), chat.items())
    }

    @Test
    fun aRetryOfACommandsTurnDispatchesItAgainAndASkillsTurnReplaysAsBefore() = runTest(UnconfinedTestDispatcher()) {
        dispatch = { p ->
            if (p.getValue("name").jsonPrimitive.content != "goal") skillOf(p)
            else buildJsonObject { put("type", "send"); put("message", "GOAL: ${p["arg"]?.jsonPrimitive?.content}"); put("notice", "⊙ Goal set") }.toString()
        }
        var rows = 40L
        submit = { """{"status":"streaming","user_row_id":${++rows}}""" }  // a bound row: a cut would be possible
        val chat = openChat().apply { open() }
        chat.send("/goal ship it")
        assertEquals("D6: no display, the typed line", "/goal ship it", (chat.state.value.entries.last() as Entry.User).text)
        turn()

        chat.send("/retry")

        assertFalse("a retried command is never cut (B5-D1)", "truncate_before_row_id" in lastSubmit())
        assertEquals(listOf("goal" to "ship it", "goal" to "ship it"), dispatches())
        assertEquals(listOf("GOAL: ship it", "GOAL: ship it"), submitted())
        assertEquals("/goal ship it", (chat.state.value.entries.last { it is Entry.User } as Entry.User).text)
        assertEquals(emptyList<String>(), slashSubmits())
        turn()

        // A skill's turn replays as P7 sends it: the bubble's invocation, which the core re-expands (session_history.py:244-256).
        chat.send("/github fix it")
        turn()
        chat.send("/retry")
        assertEquals(3, dispatches().size)
        assertEquals("/github fix it", submitted().last())
    }

    @Test
    fun theRetryButtonAndTheRecoveryCardDispatchACommandsTurnAgainNeverSendingItsLine() = runTest(UnconfinedTestDispatcher()) {
        dispatch = { p ->
            if (p.getValue("name").jsonPrimitive.content != "goal") skillOf(p)
            else buildJsonObject { put("type", "send"); put("message", "GOAL: ${p["arg"]?.jsonPrimitive?.content}") }.toString()
        }
        var rows = 40L
        submit = { """{"status":"streaming","user_row_id":${++rows}}""" }  // a bound row: a cut would be possible
        val chat = openChat().apply { open() }
        chat.send("/goal ship it")
        turn()

        chat.retry()  // the Retry button under the answer (MainActivity's onRetry)

        assertFalse("a retried command is never cut (B5-D1)", "truncate_before_row_id" in lastSubmit())
        assertEquals(listOf("goal" to "ship it", "goal" to "ship it"), dispatches())
        assertEquals(listOf("GOAL: ship it", "GOAL: ship it"), submitted())
        assertEquals(emptyList<String>(), slashSubmits())
        turn()

        // A failed command turn: the recovery card's Retry runs the same call (RecoveryRoutes.retry).
        chat.send("/goal build it")
        turn(status = "error")
        chat.retry()
        assertFalse("a retried command is never cut (B5-D1)", "truncate_before_row_id" in lastSubmit())
        assertEquals(listOf("goal" to "ship it", "goal" to "ship it", "goal" to "build it", "goal" to "build it"), dispatches())
        assertEquals("GOAL: build it", submitted().last())
        assertEquals(emptyList<String>(), slashSubmits())
        turn()

        // A skill's turn still replays the bubble's invocation, which the core re-expands; a plain prompt its words.
        chat.send("/github fix it")
        turn()
        chat.retry()
        assertEquals(5, dispatches().size)
        assertEquals("/github fix it", submitted().last())
        turn()
        chat.send("hi")
        turn()
        chat.retry()
        assertEquals(5, dispatches().size)
        assertEquals("hi", submitted().last())
    }

    // An edited command prompt (architect #1, Q3): resend is the one admission for retry and edit, and the words that go
    // in the prompt's place decide: a command `command.dispatch` runs is dispatched again there, never sent as typed
    // (invariant 4), its message riding on the prompt.submit that cuts.

    /** [CATALOG] with the core's `/plan` beside `/goal`, in `pairs` and the Session category. */
    private val planCatalog = CATALOG.replace("""["/goal","Set a goal"]""", """["/goal","Set a goal"],["/plan","Plan first"]""")

    /** The core's answer to `command.dispatch` of [p]: `/plan` and `/goal` send a prompt built from the arg (no display, D6); else a skill. */
    private fun planOrGoal(p: JsonObject): String {
        val arg = p["arg"]?.jsonPrimitive?.content
        return when (p.getValue("name").jsonPrimitive.content) {
            "plan" -> buildJsonObject { put("type", "send"); put("message", "PLAN: $arg") }.toString()
            "goal" -> buildJsonObject { put("type", "send"); put("message", "GOAL: $arg") }.toString()
            else -> skillOf(p)
        }
    }

    /** A chat whose catalog lists `/plan` and whose core dispatches `/plan` and `/goal`. */
    private suspend fun TestScope.planChat(): ChatSession {
        catalog = { planCatalog }
        dispatch = { p -> planOrGoal(p) }
        return openChat().apply { open() }
    }

    @Test
    fun anEditedPlanBubbleIsDispatchedInItsPlaceNeverSentAsTyped() = runTest(UnconfinedTestDispatcher()) {
        val chat = planChat()
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("/plan build it")
        turn()
        assertEquals("D6: the bubble is the typed line", "/plan build it", (chat.state.value.entries.first() as Entry.User).text)
        core.emit("todo.updated", "s1", """{"todos":[{"id":"1","content":"Sketch","status":"pending"}],"revision":3}""")
        assertNotNull("a plan before the cut", chat.state.value.todos)
        submit = { """{"status":"streaming","user_row_id":52}""" }
        val mark = core.calls.size

        chat.resend(chat.edit(0, "/plan build it with tests"))

        assertNull("the dispatched cut drops the abandoned plan too", chat.state.value.todos)

        assertEquals("the catalog is cached", listOf("command.dispatch", "prompt.submit"), callsAfter(mark).map { it.first })
        assertEquals("plan" to "build it with tests", dispatches().last())
        val sent = lastSubmit()
        assertEquals("PLAN: build it with tests", sent.getValue("text").jsonPrimitive.content)
        assertEquals("41", sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_truncate").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_empty_truncate").jsonPrimitive.content)
        assertFalse("not deep: nothing after it", "confirm_deep_truncate" in sent)
        assertEquals("never literal (invariant 4)", emptyList<String>(), slashSubmits())
        assertEquals("the old reply cut, the edited line shown (D6)", listOf(Entry.User("/plan build it with tests", 52, stableId = 2)),
                     chat.state.value.entries.untimed())
    }

    @Test
    fun aCommandBubbleEditedIntoPlainWordsIsAPlainEdit() = runTest(UnconfinedTestDispatcher()) {
        val chat = planChat()
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("/goal ship it")
        turn()
        submit = { """{"status":"streaming","user_row_id":52}""" }
        val (mark, dispatched) = core.calls.size to dispatches().size

        chat.resend(chat.edit(0, "just ship it"))

        assertEquals(listOf("prompt.submit"), callsAfter(mark).map { it.first })
        assertEquals("no new dispatch: the goal stays set in the core", dispatched, dispatches().size)
        val sent = lastSubmit()
        assertEquals("just ship it", sent.getValue("text").jsonPrimitive.content)
        assertEquals("41", sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals(listOf(Entry.User("just ship it", 52, stableId = 2)), chat.state.value.entries.untimed())
    }

    @Test
    fun plainWordsEditedIntoAPlanLineAreDispatched() = runTest(UnconfinedTestDispatcher()) {
        val chat = planChat()
        answeredTurn(chat, "build it", 41, "ok")
        submit = { """{"status":"streaming","user_row_id":52}""" }
        val mark = core.calls.size

        chat.resend(chat.edit(0, "/plan build it"))

        assertEquals(listOf("commands.catalog", "command.dispatch", "prompt.submit"), callsAfter(mark).map { it.first })
        assertEquals(listOf("plan" to "build it"), dispatches())
        val sent = lastSubmit()
        assertEquals("PLAN: build it", sent.getValue("text").jsonPrimitive.content)
        assertEquals("41", sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals(emptyList<String>(), slashSubmits())
        assertEquals(listOf(Entry.User("/plan build it", 52, stableId = 2)), chat.state.value.entries.untimed())
    }

    @Test
    fun aDispatchedEditKeepsTheEditRules() = runTest(UnconfinedTestDispatcher()) {
        val chat = planChat()
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("/plan a")
        turn()
        answeredTurn(chat, "b", 43, "B")
        val (mark, shown) = core.calls.size to chat.state.value.entries

        chat.resend(chat.edit(0, "/plan a2", deep = false))

        assertEquals("no consent: nothing asked of the core, no dispatch either (inv. 1)", mark, core.calls.size)
        assertEquals(shown, chat.state.value.entries)
        assertFalse(chat.state.value.busy)

        submit = { """{"status":"streaming","user_row_id":60}""" }
        chat.resend(chat.edit(0, "/plan a2", deep = true))

        assertEquals(listOf("command.dispatch", "prompt.submit"), callsAfter(mark).map { it.first })
        val sent = lastSubmit()
        assertEquals("PLAN: a2", sent.getValue("text").jsonPrimitive.content)
        assertEquals("41", sent.getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals("true", sent.getValue("confirm_deep_truncate").jsonPrimitive.content)
        assertEquals(listOf(Entry.User("/plan a2", 60, 4)), chat.state.value.entries.untimed())

        // Busy: the turn is stopped before anything is dispatched (inv. 2, D3).
        core.emit("message.start", "s1")
        submit = { """{"status":"streaming","user_row_id":70}""" }
        val busy = core.calls.size
        val job = launch { chat.resend(chat.edit(4, "/plan a3")) }

        assertEquals("stopped first, nothing dispatched yet", listOf("session.interrupt"), callsAfter(busy).map { it.first })
        core.emit("message.complete", "s1", """{"text":"Pl","status":"interrupted"}""")
        job.join()

        assertEquals(listOf("session.interrupt", "command.dispatch", "prompt.submit"), callsAfter(busy).map { it.first })
        assertEquals("PLAN: a3", lastSubmit().getValue("text").jsonPrimitive.content)
        assertEquals("60", lastSubmit().getValue("truncate_before_row_id").jsonPrimitive.content)
        assertEquals(emptyList<String>(), slashSubmits())
    }

    @Test
    fun aDispatchedEditThatDoesNotSendCutsNothing() = runTest(UnconfinedTestDispatcher()) {
        val chat = planChat()
        answeredTurn(chat, "hello", 41, "hi")
        val (mark, shown) = core.calls.size to chat.state.value.entries
        dispatch = { """{"type":"exec","output":"No pending memory writes."}""" }

        chat.resend(chat.edit(0, "/memory pending"))

        assertEquals(listOf("commands.catalog", "command.dispatch"), callsAfter(mark).map { it.first })
        assertEquals("No pending memory writes.", chat.lastNotice())
        assertEquals("the old turn still shown", shown, chat.state.value.entries.filter { it !is Entry.Notice })
        assertFalse(chat.state.value.busy)

        dispatch = { throw CoreError(4004, "usage: /goal [text | …]") }
        chat.resend(chat.edit(0, "/goal"))

        assertEquals("/goal: usage: /goal [text | …]", chat.lastNotice())
        assertEquals(shown, chat.state.value.entries.filter { it !is Entry.Notice })
        assertFalse(chat.state.value.busy)
        assertTrue(core.calls.drop(mark).none { it.first == "prompt.submit" })
    }

    @Test
    fun aRefusedDispatchedEditShowsTheCoresTranscriptAndSaysEditFailed() = runTest(UnconfinedTestDispatcher()) {
        val chat = planChat()
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("/plan build it")
        turn()
        submit = { throw CoreError(4018, "target user message is no longer in session history") }
        history = { """{"count":2,"messages":[{"role":"user","text":"PLAN: build it","row_id":41},{"role":"assistant","text":"Done.","row_id":42}]}""" }

        chat.resend(chat.edit(0, "/plan build it with tests"))

        assertEquals(listOf(Entry.User("PLAN: build it", 41, 3), Entry.Reply("Done.", TurnStatus.COMPLETE, stableId = 4, rowId = 42),
                            Entry.Notice("Edit failed: This turn is no longer in server history (it may have been compressed away).", stableId = 5)),
                     chat.state.value.entries.untimed())
        assertFalse(chat.state.value.busy)
        assertEquals("the refused submit carried the plan, never the line", "PLAN: build it with tests", submitted().last())
    }

    @Test
    fun anEditIntoACommandWhoseCatalogCannotBeReadSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        catalog = { throw CoreError(5000, "skill discovery unavailable") }
        val chat = openChat().apply { open() }
        answeredTurn(chat, "hello", 41, "hi")
        val (mark, shown) = core.calls.size to chat.state.value.entries

        chat.resend(chat.edit(0, "/plan x"))

        assertEquals(listOf("commands.catalog"), callsAfter(mark).map { it.first })
        assertEquals("Edit failed: Commands could not be read: skill discovery unavailable", chat.lastNotice())
        assertEquals("nothing cut", shown, chat.state.value.entries.dropLast(1))
        assertFalse(chat.state.value.busy)
    }

    @Test
    fun aDispatchedEditAnswersWhatItsCommandDid() = runTest(UnconfinedTestDispatcher()) {
        val chat = planChat()
        submit = { """{"status":"streaming","user_row_id":41}""" }
        chat.send("/plan build it")
        turn(chat.sessionId.value)
        submit = { """{"status":"streaming","user_row_id":52}""" }

        assertEquals(Resend.Taken, chat.resend(chat.edit(0, "/plan build it with tests")))

        val other = planChat()
        answeredTurn(other, "hello", 41, "hi")
        dispatch = { throw CoreError(4004, "usage: /goal [text | …]") }

        assertEquals(Resend.Refused("/goal: usage: /goal [text | …]"), other.resend(other.edit(0, "/goal")))

        dispatch = { """{"type":"exec","output":"No pending memory writes."}""" }

        assertEquals("the command ran and said its words", Resend.Taken, other.resend(other.edit(0, "/memory pending")))

        val stale = planChat()
        submit = { """{"status":"streaming","user_row_id":41}""" }
        stale.send("/plan build it")
        turn(stale.sessionId.value)
        submit = { throw CoreError(4018, "target user message is no longer in session history") }
        history = { """{"count":2,"messages":[{"role":"user","text":"PLAN: build it","row_id":41},{"role":"assistant","text":"Done.","row_id":42}]}""" }

        assertEquals(Resend.Refused("Edit failed: This turn is no longer in server history (it may have been compressed away)."),
                     stale.resend(stale.edit(0, "/plan build it with tests")))

        catalog = { throw CoreError(5000, "skill discovery unavailable") }
        val unread = openChat().apply { open() }
        answeredTurn(unread, "hello", 41, "hi")

        assertEquals(Resend.Refused("Edit failed: Commands could not be read: skill discovery unavailable"),
                     unread.resend(unread.edit(0, "/plan x")))
    }
}
