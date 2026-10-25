package com.nousresearch.hermes.android.chat

import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.protocol.BackendContract
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private var contract = """"desktop_contract":${BackendContract.BACKEND_CONTRACT}"""
    private val core = FakeCoreGateway { method, _ ->
        when (method) {
            "session.create" -> """{"session_id":"s${++sessions}","stored_session_id":"db1","message_count":0,"messages":[],"info":{$contract}}"""
            "prompt.submit" -> submit()
            "session.interrupt" -> """{"status":"interrupted"}"""
            "session.list" -> """{"sessions":[{"id":"db-old","title":"Calendar","message_count":3,"started_at":1.0},
                                                 {"id":"db-empty","message_count":0,"started_at":2.0}]}"""
            "session.resume" -> """{"session_id":"s-resumed","message_count":2,"stored_session_id":"db-old",
                                   "messages":[{"role":"user","text":"what's on my calendar?"},{"role":"assistant","text":"Two meetings."}],
                                   "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
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
}
