package com.nousresearch.hermes.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.protocol.ConfigGetParams
import com.nousresearch.hermes.bridge.protocol.ConfigSetParams
import com.nousresearch.hermes.bridge.protocol.MessageReactParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ReactionAuthor
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The user's reactions on the app's own core, no model (chat-14): the Message Reactions switch's real `config.set` /
 * `config.get full` round trip, and the shipped `message.react` handler's answer to the app's own frame.
 */
@RunWith(AndroidJUnit4::class)
class MessageReactionsDeviceTest {
    companion object {
        // One interpreter per process, owned by the application: every test talks to its core.
        private val ready: CoreState.Ready by lazy {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
            state as? CoreState.Ready ?: error("the core did not start: $state")
        }
        private const val TAG = "MessageReactions"
    }

    private val noTurns = object : TurnHost {
        override fun begin(turn: String) {}
        override fun end(turn: String) {}
    }

    @Test
    fun theSwitchRoundTripsThroughTheAppsOwnCore() = runBlocking<Unit> {
        val settings = ready.pages.settings
        val since = System.nanoTime()
        ready.gateway.call(Methods.CONFIG_GET, ConfigGetParams(key = "full"))  // its timing only, never its contents
        Log.i(TAG, "config.get full answered in ${(System.nanoTime() - since) / 1_000_000} ms")
        settings.open()
        val was = settings.state.value!!.reactions!!
        try {
            val answer = ready.gateway.call(Methods.CONFIG_SET, ConfigSetParams(key = "display.message_reactions", value = JsonPrimitive(was)))
            Log.i(TAG, "config.set display.message_reactions answered value ${answer.value} (${answer.value?.javaClass?.simpleName})")

            settings.reactions(!was)
            assertEquals(!was, settings.state.value?.reactions)
            assertEquals(!was, settings.reactionsOn.value)
            assertEquals(null, settings.state.value?.error)

            settings.close()
            settings.open()
            assertEquals("the core re-read it", !was, settings.state.value?.reactions)
        } finally {
            // The shared phone keeps the switch as it found it (device.md 6), proven by one more read.
            settings.reactions(was)
            settings.close()
            settings.open()
            val after = settings.state.value?.reactions
            settings.close()
            Log.i(TAG, "the switch left as found: ${after == was}")
            assertEquals("the switch is left as it was found", was, after)
        }
    }

    @Test
    fun aReactionOnARowOutsideTheSessionIsRefusedInTheCoresWords() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val chat = ChatSession(ready.gateway, noTurns, scope, keepSecret = { _, _ -> })
        try {
            chat.open()
            try {
                ready.gateway.call(Methods.MESSAGE_REACT, MessageReactParams(sessionId = chat.sessionId.value, rowId = Long.MAX_VALUE,
                                                                             emoji = "❤️", author = ReactionAuthor.USER))
                fail("the core stored a reaction on a row outside the session")
            } catch (refused: CoreError) {
                Log.i(TAG, "message.react on an unknown row: ${refused.code} ${refused.message}")
                assertEquals(4040, refused.code)
                assertTrue(refused.message.orEmpty(), refused.message.orEmpty().isNotBlank())
            }
        } finally {
            runCatching { ready.gateway.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = chat.sessionId.value)) }
            scope.cancel()
        }
    }
}
