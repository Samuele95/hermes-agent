package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PromptSubmitParams
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** One chat: a core session, the prompts sent to it, and its turns, which [turns] keeps alive. */
class ChatSession(private val core: CoreGateway, private val turns: TurnHost, scope: CoroutineScope) {
    companion object {
        /** The session's source, which is also the agent's platform; "android" keeps the CLI toolsets. */
        const val SOURCE = "android"
    }

    private val chat = MutableStateFlow(Chat())
    val state: StateFlow<Chat> = chat.asStateFlow()

    @Volatile
    private var id: String? = null

    /** The core session this chat sends to; the chat must be open. */
    val sessionId: String
        get() = checkNotNull(id) { "open() the chat first" }

    init {
        // Collect before the first request: the core does not replay events.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { event -> if (event.sessionId != null && event.sessionId == id) on(event) }
        }
    }

    /** Opens a new core session; reopening leaves the previous one, and its transcript, behind. */
    suspend fun open() {
        id = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = SOURCE)).sessionId
        chat.value = Chat()
    }

    suspend fun send(text: String) {
        val session = sessionId
        chat.update { it.sent(text) }
        try {
            core.call(Methods.PROMPT_SUBMIT, PromptSubmitParams(sessionId = session, text = JsonPrimitive(text)))
        } catch (refused: CoreError) {
            chat.update { it.notice(refused.message ?: "The core refused the prompt (${refused.code}).") }
        }
    }

    private fun on(event: CoreEvent) {
        when (event.type) {
            Events.MESSAGE_START.name -> turns.begin(event.sessionId!!)
            Events.MESSAGE_COMPLETE.name -> turns.end(event.sessionId!!)
        }
        chat.update { it.reduce(event) }
    }
}
