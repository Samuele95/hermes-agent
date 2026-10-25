package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.forUser
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
import com.nousresearch.hermes.bridge.protocol.SessionResumeParams
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import com.nousresearch.hermes.bridge.protocol.SessionListParams
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
import com.nousresearch.hermes.bridge.protocol.BackendContract
import com.nousresearch.hermes.bridge.protocol.SessionInterruptParams
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
 * a turn asks, which [notifier] hears of along with finished turns. A secret the agent asks for goes to
 * [keepSecret] (the keystore), never back to the core: the core would write an answered value to `.env`.
 */
class ChatSession(private val core: CoreGateway, private val turns: TurnHost, scope: CoroutineScope,
                  private val keepSecret: suspend (name: String, value: ByteArray) -> Unit,
                  private val notifier: Notifier = Notifier.None) {
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
                    notifier.promptPending(asked)
                }
            }
        }
    }

    /** Opens a new core session; reopening leaves the previous one, and its transcript, behind. */
    suspend fun open() {
        val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = SOURCE))
        switchTo(created.sessionId, Chat(), created.info.desktopContract)
    }

    /** Past conversations with something in them, as the core lists them (newest first). */
    suspend fun pastSessions(): List<SessionListRow> =
        core.call(Methods.SESSION_LIST, SessionListParams(limit = 100)).sessions.filter { it.messageCount > 0 }

    /** Continues stored session [sessionId]: the chat shows its transcript and sends to it from now on. */
    suspend fun resume(sessionId: String) {
        val resumed = core.call(Methods.SESSION_RESUME, SessionResumeParams(sessionId = sessionId, source = SOURCE))
        switchTo(resumed.sessionId, Chat(entries = transcriptOf(resumed.messages)), resumed.info.desktopContract)
    }

    /** Makes [live] the chat's session, showing [shown]; the previous session's turn and questions are let go. */
    private fun switchTo(live: String, shown: Chat, reportedContract: JsonElement?) {
        val previous = id
        // The previous session's turn would end in events this chat no longer follows: it ends here for the app.
        if (previous != null && chat.value.running) turns.end(previous)
        id = live
        chat.value = shown
        contractNotice(reportedContract)?.let { notice -> chat.update { it.notice(notice) } }
        // The previous session's questions lose their card: settle them rather than leave its turn waiting.
        waiting.values.forEach { core.decline(it, "the chat moved to a new session") }
        if (waiting.isNotEmpty()) notifier.promptsSettled()
        waiting.clear()
    }

    /**
     * The core and this client were built against the same `desktop_contract`, or the user hears that they were not:
     * a payload rebuilt from another core would otherwise fail later, in ways that look like the core's own. The
     * desktop warns the same way; a missing value reads as an older core.
     */
    private fun contractNotice(reported: JsonElement?): String? {
        val core = (reported as? JsonPrimitive)?.intOrNull ?: 0
        if (core == BackendContract.BACKEND_CONTRACT) return null
        return "This app was built for core protocol ${BackendContract.BACKEND_CONTRACT}, but its core reports $core. " +
            "Rebuild the app so the two match; some features may not work until then."
    }

    suspend fun send(text: String) {
        val session = sessionId
        chat.update { it.sent(text) }
        try {
            core.call(Methods.PROMPT_SUBMIT, PromptSubmitParams(sessionId = session, text = JsonPrimitive(text)))
        } catch (refused: CoreError) {
            chat.update { it.notice(refused.message ?: "The core refused the prompt (${refused.code}).") }
        } catch (failed: Exception) {  // anything else: the message did not go out, and the user hears it
            chat.update { it.notice(forUser(failed)) }
        }
    }

    /**
     * Stops the running turn, as the desktop's Stop does: the core interrupts it and ends it with an `interrupted`
     * completion, which ends the turn here too. Nothing runs, nothing is asked.
     */
    suspend fun cancel() {
        if (!chat.value.running) return
        try {
            core.call(Methods.SESSION_INTERRUPT, SessionInterruptParams(sessionId = sessionId))
        } catch (failed: Exception) {
            chat.update { it.notice(forUser(failed)) }
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
        } catch (refused: IllegalArgumentException) {  // the store's own words: a name that is no variable name
            chat.update { it.notice(refused.message ?: "The key could not be kept.") }
            settle(prompt) { core.decline(it, "the app could not keep the key") }
            return
        } catch (failed: Exception) {
            chat.update { it.notice(forUser(failed)) }
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
        if (chat.updateAndGet { it.settled(prompt.id) }.prompts.isEmpty()) notifier.promptsSettled()
    }

    /** The app keeps a turn alive exactly while the transcript says it runs: the reducer decides when a turn ends. */
    private fun on(event: CoreEvent) {
        val before = chat.value.running
        val after = chat.updateAndGet { it.reduce(event) }
        if (!before && after.running) turns.begin(event.sessionId!!)
        if (before && !after.running) {
            turns.end(event.sessionId!!)
            notifier.turnFinished(after.entries.lastOrNull() as? Entry.Reply)
        }
        // Withdrawn by the core: the turn no longer waits for them.
        if (waiting.keys.retainAll(after.prompts.mapTo(HashSet()) { it.id }) && after.prompts.isEmpty()) notifier.promptsSettled()
    }
}
