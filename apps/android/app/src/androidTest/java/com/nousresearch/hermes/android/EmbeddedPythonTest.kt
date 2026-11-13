package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.protocol.ClientCapabilitiesParams
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronManageParams
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Method
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PingParams
import com.nousresearch.hermes.bridge.protocol.PingResult
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownParams
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
import com.nousresearch.hermes.bridge.protocol.SessionTitleParams
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
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
    fun aSessionIsTitledClosedAndDeletedThroughTheGeneratedCalls() = runBlocking {
        // session.create pre-warms the session's agent; its outcome (session.info, or error without a provider)
        // goes to python.stdout once the session is closed, so the session is closed only after it settled.
        val settled = Channel<String>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { if (it.type == "session.info" || it.type == "error") it.sessionId?.let(settled::trySend) }
        }
        // A second session beside the chat's own; "android" = ChatSession.SOURCE (Dv16).
        val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = "android"))
        val stored = created.storedSessionId
        try {
            withTimeout(120_000) { while (settled.receive() != created.sessionId) Unit }
            val title = "b1 probe ${System.nanoTime()}"

            val titled = core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = created.sessionId, title = title))

            assertEquals(title, titled.title)
            assertEquals(false, titled.pending)
            // A set answers title + pending; the read also names the session's key: the stored id session.delete takes.
            val read = core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = created.sessionId))
            assertEquals(title, read.title)
            assertEquals(stored, read.sessionKey)

            val breakdown = core.call(Methods.SESSION_CONTEXT_BREAKDOWN, SessionContextBreakdownParams(sessionId = created.sessionId))
            assertTrue("${breakdown.contextUsed}", breakdown.contextUsed >= 0)

            val live = assertThrows(CoreError::class.java) {
                runBlocking { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
            }
            assertEquals(live.message, 4023, live.code)

            assertTrue(core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)).closed)
            assertEquals(stored, core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)).deleted)
        } finally {
            watcher.cancel()
            // A failed run leaves no session or row behind on the shared phone.
            runCatching { core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)) }
            runCatching { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
        }
    }

    @Test
    fun aJobIsAddedPausedResumedAndRemovedByItsId() = runBlocking {
        // Frames nobody collects are not replayed: the watcher runs before the first write.
        val signals = Channel<CoreEvent>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { if (it.decode(Events.CRON_CHANGED) != null) signals.trySend(it) }
        }
        // A weekly interval: its first run is a week away, so the debug app's :gateway ticker never fires it here.
        val added = core.call(
            Methods.CRON_MANAGE,
            CronManageParams(
                action = CronAction.ADD, name = "j1 probe ${System.nanoTime()}", schedule = "every 10080m",
                prompt = "Say hello.", deliver = "local",
            ),
        )
        val id = checkNotNull(added.jobId) { "add answered ${added.error}" }
        try {
            assertEquals(added.error, true, added.success)
            assertEquals("scheduled", added.job!!.state)
            // The signal reaches the chat process's gateway; a tick of the debug :gateway would produce the same fact.
            assertEquals("", withTimeout(15_000) { signals.receive() }.sessionId)

            // The reference the app sends is the core's job_id, in `name`.
            val paused = core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.PAUSE, name = id)).job!!
            assertEquals("paused", paused.state)
            assertEquals(false, paused.enabled)

            val listed = core.call(Methods.CRON_MANAGE, CronManageParams()).jobs.orEmpty()
            assertTrue("a paused job is listed without include_disabled", listed.none { it.jobId == id })
            val all = core.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true))).jobs.orEmpty()
            assertEquals("paused", all.single { it.jobId == id }.state)

            val resumed = core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.RESUME, name = id))
            assertEquals("scheduled", resumed.job!!.state)

            val removed = core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = id))
            assertEquals(id, removed.removedJob!!.id)
            val after = core.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true))).jobs.orEmpty()
            assertTrue("$id is still listed after its removal", after.none { it.jobId == id })
        } finally {
            watcher.cancel()
            // A failed run leaves no job behind on the shared phone.
            runCatching { core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = id)) }
        }
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
