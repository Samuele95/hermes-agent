package com.nousresearch.hermes.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.Dispatch
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.chat.SlashKind
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.protocol.CommandDispatchParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The slash menu on the app's own core, no model (chat-20, bet 9): the real `commands.catalog` cut to what the phone
 * runs, the real `complete.slash` narrowed and filled, a real skill dispatch's display and message, and a real review
 * command's output as a notice. No prompt is ever submitted.
 */
@RunWith(AndroidJUnit4::class)
class SlashCommandsDeviceTest {
    companion object {
        // One interpreter per process, owned by the application: every test talks to its core.
        private val ready: CoreState.Ready by lazy {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
            state as? CoreState.Ready ?: error("the core did not start: $state")
        }
        private const val TAG = "SlashCommands"
    }

    private val noTurns = object : TurnHost {
        override fun begin(turn: String) {}
        override fun end(turn: String) {}
    }

    /** [body] with a chat open on a session of its own, which is closed after (device.md 6). */
    private fun withChat(body: suspend (ChatSession) -> Unit) = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = ChatSession(ready.gateway, noTurns, scope, keepSecret = { _, _ -> })
        try {
            chat.open()
            body(chat)
        } finally {
            chat.openSessionId?.let { runCatching { ready.gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it.value)) } }
            scope.cancel()
        }
    }

    @Test
    fun theRealCatalogListsWhatThePhoneRuns() = withChat { chat ->
        val since = System.nanoTime()
        val menu = chat.slashItems("/")
        val tookMs = (System.nanoTime() - since) / 1_000_000
        val names = menu.map { it.name }

        listOf("/new", "/model", "/compress", "/retry", "/steer", "/queue", "/memory").forEach { assertTrue(it, it in names) }
        assertEquals(SlashKind.SKILL, menu.single { it.name == "/github" }.kind)
        listOf("/status", "/tools", "/usage", "/snapshot", "/density").forEach { assertFalse("$it is slash.exec's", it in names) }
        val commands = menu.filter { it.kind == SlashKind.COMMAND }.map { it.name }
        Log.i(TAG, "commands.catalog: ${menu.size} items (${commands.size} commands, ${menu.count { it.kind == SlashKind.SKILL }} skills) in $tookMs ms")
        Log.i(TAG, "commands listed: ${commands.joinToString(" ")}")
    }

    @Test
    fun typingNarrowsWithTheRealCompletionAndAPickFillsTheLine() = withChat { chat ->
        chat.slashItems("/")  // the catalog first, as the menu opens
        val since = System.nanoTime()
        val narrowed = chat.slashItems("/git")
        val tookMs = (System.nanoTime() - since) / 1_000_000

        assertEquals("/github ", narrowed.single { it.name == "/github" }.fill)
        assertTrue(narrowed.none { it.name == "/worktree" || it.name == "/diff" })
        Log.i(TAG, "complete.slash /git: ${narrowed.map { it.name }} in $tookMs ms")
    }

    @Test
    fun aSkillDispatchesToItsBodyAndAReviewCommandIsANotice() = withChat { chat ->
        val directive = Dispatch.of(ready.gateway.call(Methods.COMMAND_DISPATCH,
                                                       CommandDispatchParams(name = "github", arg = "probe", sessionId = chat.sessionId.value)))
        val send = directive as Dispatch.Send
        assertEquals("/github probe", send.display)
        assertNotEquals(send.display, send.message)
        assertTrue(send.message.contains("probe"))
        Log.i(TAG, "skill dispatch: display '${send.display}', message ${send.message.length} chars")

        val users = chat.state.value.entries.count { it is Entry.User }
        chat.send("/memory pending")
        val after = withTimeout(10_000) { chat.state.first { !it.busy && it.entries.lastOrNull() is Entry.Notice } }

        assertEquals(users, after.entries.count { it is Entry.User })
        Log.i(TAG, "/memory pending: ${(after.entries.last() as Entry.Notice).text.lineSequence().first()}")
    }
}
