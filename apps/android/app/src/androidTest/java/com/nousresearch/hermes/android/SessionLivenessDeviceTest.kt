package com.nousresearch.hermes.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
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
    private suspend fun persisted(runtime: String): String {
        gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = runtime, title = "b3 left ${System.nanoTime()}"))
        return checkNotNull(gateway.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = runtime)).sessionKey) { "no stored key" }
    }

    /** Deletes stored row [stored], asking again while the core still has its session live (4023). */
    private suspend fun deleteOnceClosed(stored: String): String {
        while (true) {
            try {
                return gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)).deleted
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
        var left: String? = null
        var stored: String? = null
        try {
            chat.open()
            left = chat.sessionId
            stored = persisted(left)

            chat.open()  // leaves `left` idle: the chat closes it once its agent build has settled
            val since = System.nanoTime()
            val deleted = withTimeout(120_000) { deleteOnceClosed(stored) }

            assertEquals(stored, deleted)
            Log.i("SessionLiveness", "left session deletable after ${(System.nanoTime() - since) / 1_000_000} ms")
        } finally {
            // A failed run leaves no session or row behind on the shared phone.
            runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = chat.sessionId)) }
            left?.let { runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it)) } }
            stored?.let { runCatching { gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = it)) } }
            scope.cancel()
        }
    }

    /** The core's answer to deleting [stored] now: its refusal code. */
    private fun refusalOf(stored: String): Int = assertThrows(CoreError::class.java) {
        runBlocking { gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
    }.code

    @Test
    fun theChatsOwnSessionIsDeletedByMovingOffIt() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = ChatSession(gateway, noTurns, scope, keepSecret = { _, _ -> })
        val runtimes = mutableListOf<String>()
        val rows = mutableListOf<String>()
        try {
            chat.open()
            val own = chat.sessionId.also(runtimes::add)
            val stored = persisted(own).also(rows::add)

            chat.delete(stored)  // the chat's own: a new chat first, the old session closed, then the row

            assertNotEquals(own, chat.sessionId.also(runtimes::add))
            assertEquals(4007, refusalOf(stored))  // gone
            chat.delete(stored)  // gone already counts as deleted

            val other = persisted(chat.sessionId).also(rows::add)
            chat.open()  // leaves it idle; its close waits for its agent build
            runtimes += chat.sessionId
            val since = System.nanoTime()
            withTimeout(120_000) {
                while (true) {
                    try {
                        chat.delete(other)
                        break
                    } catch (live: SetupRefusal) {  // still live in the core until the close lands
                        delay(200)
                    }
                }
            }
            Log.i("SessionLiveness", "other session deleted after ${(System.nanoTime() - since) / 1_000_000} ms")
            assertEquals(4007, refusalOf(other))
        } finally {
            // A failed run leaves no session or row behind on the shared phone.
            runtimes.forEach { runCatching { gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it)) } }
            rows.forEach { runCatching { gateway.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = it)) } }
            scope.cancel()
        }
    }
}
