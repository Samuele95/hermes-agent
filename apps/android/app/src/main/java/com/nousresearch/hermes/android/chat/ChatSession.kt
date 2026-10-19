package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.InTurnPrompt
import com.nousresearch.hermes.bridge.answer
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.protocol.ApprovalResult
import com.nousresearch.hermes.bridge.protocol.ClarifyResult
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PromptSubmitParams
import com.nousresearch.hermes.bridge.protocol.Requests
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import com.nousresearch.hermes.bridge.protocol.ValueResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * One chat: a core session, the prompts sent to it, its turns, which [turns] keeps alive, and the questions
 * a turn asks. A secret the agent asks for goes to [keepSecret] (the keystore), never back to the core:
 * the core would write an answered value to `.env`.
 */
class ChatSession(private val core: CoreGateway, private val turns: TurnHost, scope: CoroutineScope,
                  private val keepSecret: suspend (name: String, value: ByteArray) -> Unit) {
    companion object {
        /** The session's source, which is also the agent's platform; "android" keeps the CLI toolsets. */
        const val SOURCE = "android"
    }

    private val chat = MutableStateFlow(Chat())
    val state: StateFlow<Chat> = chat.asStateFlow()

    @Volatile
    private var id: String? = null

    /** The core's requests behind [Chat.prompts], by id. */
    private val waiting = ConcurrentHashMap<String, InTurnPrompt>()

    /** The core session this chat sends to; the chat must be open. */
    val sessionId: String
        get() = checkNotNull(id) { "open() the chat first" }

    init {
        // Collect before the first request: the core does not replay events.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { event -> if (event.sessionId != null && event.sessionId == id) on(event) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Nothing else shows the core's requests: one this chat cannot show is declined, or its turn would wait it out.
            core.prompts.collect { raw ->
                val asked = prompt(raw, id)
                if (asked == null) {
                    core.decline(raw, "not available in the Android app")
                } else {
                    waiting[asked.id] = raw
                    chat.update { it.asked(asked) }
                }
            }
        }
    }

    /** Opens a new core session; reopening leaves the previous one, and its transcript, behind. */
    suspend fun open() {
        val previous = id
        val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = SOURCE)).sessionId
        // The previous session's turn would end in events this chat no longer follows: it ends here for the app.
        if (previous != null && chat.value.running) turns.end(previous)
        id = created
        chat.value = Chat()
        // The previous session's questions lose their card: settle them rather than leave its turn waiting.
        waiting.values.forEach { core.decline(it, "the chat moved to a new session") }
        waiting.clear()
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

    fun approve(prompt: Prompt.Approval, choice: ApprovalChoice) =
        settle(prompt) { core.answer(it, Requests.APPROVAL, ApprovalResult(choice)) }

    /** [answers] by question id; null skips a question, [multiSelect] encodes a multi-select one. */
    fun clarify(prompt: Prompt.Clarify, answers: Map<String, String?>) =
        settle(prompt) { core.answer(it, Requests.CLARIFY, ClarifyResult(answers)) }

    /** Keeps [value] (null: skipped) and wipes the caller's copy; the core gets an empty answer either way. */
    suspend fun secret(prompt: Prompt.Secret, value: ByteArray?) {
        try {
            value?.let { keepSecret(prompt.envVar, it) }
        } catch (refused: IllegalArgumentException) {
            chat.update { it.notice(refused.message ?: "The key could not be kept.") }
            settle(prompt) { core.decline(it, "the app could not keep the key") }
            return
        } finally {
            value?.fill(0)
        }
        settle(prompt) { core.answer(it, Requests.SECRET, ValueResult("")) }
    }

    /** Sends the one answer [prompt] gets; one the core already withdrew is gone and gets none. */
    private fun settle(prompt: Prompt, send: (InTurnPrompt) -> Unit) {
        waiting.remove(prompt.id)?.let(send)
        chat.update { it.settled(prompt.id) }
    }

    /** The app keeps a turn alive exactly while the transcript says it runs: the reducer decides when a turn ends. */
    private fun on(event: CoreEvent) {
        val before = chat.value.running
        val after = chat.updateAndGet { it.reduce(event) }
        if (!before && after.running) turns.begin(event.sessionId!!)
        if (before && !after.running) turns.end(event.sessionId!!)
        waiting.keys.retainAll(after.prompts.mapTo(HashSet()) { it.id })  // withdrawn by the core
    }
}
