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
import com.nousresearch.hermes.bridge.protocol.SessionListParams
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
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** A chat on the app's own core closes the sessions it leaves idle, so their stored rows can be deleted, its own included. */
@RunWith(AndroidJUnit4::class)
class SessionLivenessDeviceTest {
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

    /** Titles live session [runtime] (which persists its row) and answers its stored key. */
    private suspend fun persisted(runtime: RuntimeSessionId): StoredSessionId {
        gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = runtime.value, title = "b3 left ${System.nanoTime()}"))
        return StoredSessionId(checkNotNull(gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = runtime.value)).sessionKey) { "no stored key" })
    }

    /** Deletes stored row [stored], asking again while the core still has its session live (4023). */
    private suspend fun deleteOnceClosed(stored: StoredSessionId): String {
        while (true) {
            try {
                return gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored.value)).deleted
            } catch (live: CoreError) {
                if (live.code != 4023) throw live
                delay(200)
            }
        }
    }

    @Test
    fun aSessionTheChatLeavesIdleCanBeDeleted() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = ChatSession(gateway, noTurns, scope, keepSecret = { _, _ -> })
        var left: RuntimeSessionId? = null
        var stored: StoredSessionId? = null
        try {
            chat.open()
            left = chat.sessionId
            stored = persisted(left)

            chat.open()  // leaves `left` idle: the chat closes it once its agent build has settled
            val since = System.nanoTime()
            val deleted = withTimeout(120_000) { deleteOnceClosed(stored) }

            assertEquals(stored.value, deleted)
            Log.i("SessionLiveness", "left session deletable after ${(System.nanoTime() - since) / 1_000_000} ms")
        } finally {
            // A failed run leaves no session or row behind on the shared phone.
            runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = chat.sessionId.value)) }
            left?.let { runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it.value)) } }
            stored?.let { runCatching { gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = it.value)) } }
            scope.cancel()
        }
    }

    /** The core's answer to deleting [stored] now: its refusal code. */
    private fun refusalOf(stored: StoredSessionId): Int = assertThrows(CoreError::class.java) {
        runBlocking { gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored.value)) }
    }.code

    @Test
    fun theChatsOwnSessionIsDeletedByMovingOffIt() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = ChatSession(gateway, noTurns, scope, keepSecret = { _, _ -> })
        val runtimes = mutableListOf<String>()
        val rows = mutableListOf<StoredSessionId>()
        try {
            chat.open()
            val own = chat.sessionId.also { runtimes += it.value }
            val stored = persisted(own).also(rows::add)

            chat.delete(stored)  // the chat's own: a new chat first, the old session closed, then the row

            assertNotEquals(own, chat.sessionId.also { runtimes += it.value })
            assertEquals(4007, refusalOf(stored))  // gone
            chat.delete(stored)  // gone already counts as deleted

            val other = persisted(chat.sessionId).also(rows::add)
            chat.open()  // leaves it idle; its close waits for its agent build
            runtimes += chat.sessionId.value
            val since = System.nanoTime()
            withTimeout(120_000) { chat.delete(other) }  // waits for the left session's close itself (W7)
            Log.i("SessionLiveness", "other session deleted after ${(System.nanoTime() - since) / 1_000_000} ms")
            assertEquals(4007, refusalOf(other))
        } finally {
            // A failed run leaves no session or row behind on the shared phone.
            runtimes.forEach { runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it)) } }
            rows.forEach { runCatching { gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = it.value)) } }
            scope.cancel()
        }
    }

    /** The stored row the core lists under exactly [title]. */
    private suspend fun rowTitled(title: String): StoredSessionId =
        gateway.call(Methods.SESSION_LIST, SessionListParams(title = title)).sessions.single().let { StoredSessionId(it.resolvedId ?: it.id) }

    @Test
    fun aStoredRowIsRenamedAsideAndLetGo() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = ChatSession(gateway, noTurns, scope, keepSecret = { _, _ -> })
        val runtimes = mutableListOf<String>()
        val rows = mutableListOf<StoredSessionId>()
        try {
            chat.open()
            val left = chat.sessionId.also { runtimes += it.value }
            val stored = persisted(left).also(rows::add)
            chat.open()  // leaves `left` idle
            runtimes += chat.sessionId.value

            // The left session's own close first, so the rename meets a fresh lazy mount, not a reuse.
            withTimeout(120_000) {
                while (true) {
                    try {
                        gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = left.value))
                        delay(200)
                    } catch (gone: CoreError) {
                        assertEquals(4001, gone.code)  // the runtime is gone
                        break
                    }
                }
            }

            val named = "b8 renamed ${System.nanoTime()}"
            chat.rename(stored, named)

            assertEquals(stored, rowTitled(named))
            // Let go after the title: the first delete is not refused as live (4023).
            assertEquals(stored.value, gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored.value)).deleted)

            val running = chat.sessionId
            val own = persisted(running).also(rows::add)
            val mine = "b8 own ${System.nanoTime()}"
            chat.rename(own, mine)
            assertEquals(own, rowTitled(mine))
            assertEquals(running, chat.sessionId)
        } finally {
            // A failed run leaves no session or row behind on the shared phone.
            runtimes.forEach { runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it)) } }
            rows.forEach { runCatching { gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = it.value)) } }
            scope.cancel()
        }
    }
}
