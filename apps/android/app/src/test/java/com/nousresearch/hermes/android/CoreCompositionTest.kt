package com.nousresearch.hermes.android

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
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The chat process's composition root runs over any [CoreRuntime]: here, a core that is a function. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreCompositionTest {
    private var configured = true
    private val core = FakeCoreGateway { method, _ ->
        when (method) {
            "session.create" -> """{"session_id":"s1","stored_session_id":"db1","message_count":0,"messages":[],
                                   "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
            "setup.status" -> """{"provider_configured":$configured}"""
            "model.options" -> """{"providers":[]}"""
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
}
