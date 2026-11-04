package com.nousresearch.hermes.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.RuntimeSessionId
import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
import com.nousresearch.hermes.bridge.protocol.SessionMostRecentParams
import com.nousresearch.hermes.bridge.protocol.SessionTitleParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A chat started on the app's own core is in the conversation the core says was last, as the app is after a restart. */
@RunWith(AndroidJUnit4::class)
class ReopenLastSessionDeviceTest {
    companion object {
        // One interpreter per process, owned by the application: every test talks to its core.
        private val gateway: CoreGateway by lazy {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
            (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway
        }
    }

    private val noTurns = object : TurnHost {
        override fun begin(turn: String) {}
        override fun end(turn: String) {}
    }

    /** Titles live session [runtime] with [title] (which persists its row) and answers its stored key. */
    private suspend fun persisted(runtime: RuntimeSessionId, title: String): StoredSessionId {
        gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = runtime.value, title = title))
        return StoredSessionId(checkNotNull(gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = runtime.value)).sessionKey) { "no stored key" })
    }

    /** True while the core still has runtime session [runtime] live. */
    private suspend fun live(runtime: RuntimeSessionId): Boolean = try {
        gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = runtime.value))
        true
    } catch (gone: CoreError) {
        false
    }

    @Test
    fun aChatStartsInTheCoresMostRecentConversation() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chatA = ChatSession(gateway, noTurns, scope, keepSecret = { _, _ -> })
        val chatB = ChatSession(gateway, noTurns, scope, keepSecret = { _, _ -> })
        var left: RuntimeSessionId? = null
        var stored: StoredSessionId? = null
        try {
            chatA.open()
            left = chatA.sessionId
            val title = "b4 last ${System.nanoTime()}"
            stored = persisted(left, title)

            chatA.open()  // leaves `left` idle: the chat closes it once its agent build has settled
            withTimeout(120_000) { while (live(left)) delay(200) }  // so the start below resumes it cold

            assertEquals("the titled row is the core's most recent", stored.value,
                         gateway.call(Methods.SESSION_MOST_RECENT, SessionMostRecentParams()).sessionId)

            val since = System.nanoTime()
            chatB.start()
            Log.i("ReopenLast", "start reopened in ${(System.nanoTime() - since) / 1_000_000} ms")

            val reopened = gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = chatB.sessionId.value))
            assertEquals(stored.value, reopened.sessionKey)
            assertEquals(title, reopened.title)
            assertNotEquals(left, chatB.sessionId)
        } finally {
            // A failed run leaves no session or row behind on the shared phone.
            runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = chatA.sessionId.value)) }
            runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = chatB.sessionId.value)) }
            left?.let { runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it.value)) } }
            stored?.let { runCatching { gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = it.value)) } }
            scope.cancel()
        }
    }
}
