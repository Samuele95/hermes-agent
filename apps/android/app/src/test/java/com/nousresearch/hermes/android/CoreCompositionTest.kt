package com.nousresearch.hermes.android

import com.nousresearch.hermes.android.chat.ContextUi
import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.android.chat.Notifier
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.CoreRuntime
import com.nousresearch.hermes.bridge.FrameSink
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.protocol.BackendContract
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownResult
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The chat process's composition root runs over any [CoreRuntime]: here, a core that is a function. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreCompositionTest {
    private var configured = true
    private var recent: String? = null
    private val core = FakeCoreGateway { method, _ ->
        when (method) {
            "session.create" -> """{"session_id":"s1","stored_session_id":"db1","message_count":0,"messages":[],
                                   "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
            "setup.status" -> """{"provider_configured":$configured}"""
            "model.options" -> """{"providers":[]}"""
            "config.get" -> """{"config":{}}"""
            "session.most_recent" -> """{"session_id":${recent?.let { "\"$it\"" } ?: "null"}}"""
            "session.resume" -> """{"session_id":"s-last","message_count":0,"messages":[],"session_key":"db-last",
                                   "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
            "session.context_breakdown" -> EMPTY_BREAKDOWN
            else -> error("unexpected $method")
        }
    }
    private val bridgeCalls = mutableListOf<String>()
    private val runtime = object : CoreRuntime {
        val connected = mutableListOf<HermesPaths>()
        override suspend fun connect(paths: HermesPaths, secrets: SecretStore): CoreGateway = core.also { connected += paths }
        override fun startGateway(paths: HermesPaths, secrets: SecretStore, events: FrameSink) = error("not the chat's core")
        override val bridge = AppBridge { function, _ ->
            bridgeCalls += function
            if (function == "runtime_facts") """{"python":"3.14.7","sqlite":"3.53.4","fts5":true,"executable":""}""" else "{}"
        }
    }
    private val secrets = object : SecretStore {
        override fun put(name: String, value: ByteArray) {}
        override fun get(name: String): ByteArray? = null
        override fun delete(name: String) {}
        override fun all() = emptyMap<String, ByteArray>()
    }
    private val turns = object : TurnHost {
        override fun begin(turn: String) {}
        override fun end(turn: String) {}
    }
    private val paths = File("/data/app-files").let { HermesPaths(File(it, "runtime"), it, File(it, ".hermes"), File(it, "cache")) }

    @Test
    fun theComposedCoreHasOpenedTheChatsSessionAndSaysWhatRuns() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, secrets, turns, Notifier.None, backgroundScope)

        assertEquals(listOf(paths), runtime.connected)
        assertEquals("android", core.calls.first { it.first == "session.create" }.second.getValue("source").jsonPrimitive.content)
        assertEquals("s1", ready.chat.sessionId)
        assertEquals("Python 3.14.7 · SQLite 3.53.4 with FTS5", ready.about)
    }

    @Test
    fun aCoreWithoutAProviderShowsSetupFirst() = runTest(UnconfinedTestDispatcher()) {
        configured = false

        val ready = composeCore(runtime, paths, secrets, turns, Notifier.None, backgroundScope)

        assertNotNull(ready.setup.state.value)
    }

    @Test
    fun aConfiguredCoreGoesStraightToTheChat() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, secrets, turns, Notifier.None, backgroundScope)

        assertNull(ready.setup.state.value)
    }

    @Test
    fun theComposedChatReopensTheCoresMostRecentConversation() = runTest(UnconfinedTestDispatcher()) {
        recent = "db-last"

        val ready = composeCore(runtime, paths, secrets, turns, Notifier.None, backgroundScope)

        assertEquals("s-last", ready.chat.sessionId)
        assertFalse(core.calls.any { it.first == "session.create" })
        assertEquals("db-last", core.calls.single { it.first == "session.resume" }.second.getValue("session_id").jsonPrimitive.content)
    }

    @Test
    fun theContextPanelReadsTheChatsSession() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, secrets, turns, Notifier.None, backgroundScope)

        ready.context.open()

        assertEquals("s1", core.calls.single { it.first == "session.context_breakdown" }.second.getValue("session_id").jsonPrimitive.content)
        // A fresh session has no meter yet: the core reported no window.
        assertEquals(ContextUi(null, Json.decodeFromString(SessionContextBreakdownResult.serializer(), EMPTY_BREAKDOWN)),
                     ready.context.state.value)
    }

    private companion object {
        const val EMPTY_BREAKDOWN = """{"categories":[],"context_max":0,"context_percent":0,"context_used":0,"estimated_total":0,
            "context_estimated":false,"context_source":"provider_usage","model":"m"}"""
    }
}
