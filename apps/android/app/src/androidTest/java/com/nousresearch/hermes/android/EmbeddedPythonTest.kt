package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.protocol.ClientCapabilitiesParams
import com.nousresearch.hermes.bridge.protocol.Method
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PingParams
import com.nousresearch.hermes.bridge.protocol.PingResult
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The core the app embeds answers the app's gateway over the in-process transport. */
@RunWith(AndroidJUnit4::class)
class EmbeddedPythonTest {
    companion object {
        // One interpreter per process, owned by the application: every test talks to its core.
        private val core: CoreGateway by lazy {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
            (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway
        }
    }

    @Test
    fun theCoreAnswersATypedRequest() = runBlocking {
        assertEquals(PingResult(pong = true), core.call(Methods.PING, PingParams))
    }

    @Test
    fun theAppIsAClientThatAnswersInTurnPrompts() = runBlocking {
        val asked = core.call(Methods.CLIENT_CAPABILITIES, ClientCapabilitiesParams(serverRequests = true)).serverRequests

        assertTrue("$asked", asked.containsAll(listOf("approval", "clarify", "secret")))
    }

    @Test
    fun framesCrossTheBridgeAsUtf8BothWays() {
        val unknown = Method("no.such.😀", PingParams.serializer(), PingResult.serializer())

        val error = assertThrows(CoreError::class.java) { runBlocking { core.call(unknown, PingParams) } }

        assertEquals(-32601, error.code)
        assertTrue(error.message, error.message!!.contains("no.such.😀"))
    }

    @Test
    fun theInterpreterHasFts5AndOffersNoExecutable() {
        core
        val facts = JSONObject(EmbeddedPython.call("runtime_facts", ""))

        assertEquals("3.14.7", facts.getString("python"))
        assertTrue("sqlite ${facts.getString("sqlite")} lacks FTS5", facts.getBoolean("fts5"))
        assertEquals("", facts.getString("executable"))
    }

    @Test
    fun onlyTheDeclaredBridgeCallsReachThePythonHalf() {
        core
        // `stop` is a function of hermes_android, but no bridge call: calling it would close the app's core.
        val refused = assertThrows(RuntimeException::class.java) { EmbeddedPython.call("stop", "") }

        assertTrue(refused.message, refused.message!!.contains("not a bridge call"))
        assertEquals(PingResult(pong = true), runBlocking { core.call(Methods.PING, PingParams) })
    }

    @Test
    fun aNetworkProbeDoesNotHoldTheCoresFrames() =
        assertAHeldCallLetsFramesThrough("probe_endpoint") { """{"address":"http://127.0.0.1:$it/v1","api_key":null}""" }

    @Test
    fun anSshHostLookDoesNotHoldTheCoresFrames() =
        // asyncssh connects and waits for a banner that never comes (up to 15 s); the closed listener ends it with {error}.
        assertAHeldCallLetsFramesThrough("ssh_host_key") { """{"host":"127.0.0.1","port":$it}""" }

    /** Holds the bridge call [function] on a silent listener ([request] gets its port) and checks a ping still answers. */
    private fun assertAHeldCallLetsFramesThrough(function: String, request: (port: Int) -> String) {
        core
        // Never accepted: the kernel completes the connect and nothing answers (tests/android/fake_endpoint.py's
        // silent_listener), so the call waits on the bridge's network thread. IPv4 by name: Android's
        // getLoopbackAddress() is ::1, where the call's 127.0.0.1 would be refused at once.
        val silent = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        var reply: Result<String>? = null
        val held = thread { reply = runCatching { EmbeddedPython.call(function, request(silent.localPort)) } }
        try {
            Thread.sleep(1_500)

            assertEquals(PingResult(pong = true), runBlocking { withTimeout(3_000) { core.call(Methods.PING, PingParams) } })
            assertTrue("$function had already ended ($reply), so the ping proves nothing about a held call", held.isAlive)
        } finally {
            silent.close()
        }
        held.join(30_000)
        assertFalse("$function did not end after its listener closed", held.isAlive)
    }
}
