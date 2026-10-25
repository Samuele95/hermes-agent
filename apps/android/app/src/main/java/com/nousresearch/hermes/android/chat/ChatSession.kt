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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Everything the chat changes together: the core session it follows, its transcript, and the core's requests
     * behind the transcript's question cards. Events, questions and session switches arrive on different threads;
     * each change is one [change] of this one value, so none of them can see or leave a half-made state.
     */
    private data class Live(val session: String? = null, val chat: Chat = Chat(), val waiting: Map<String, InTurnPrompt> = emptyMap()) {
        /** The requests the core withdrew (their cards are gone from the transcript) let go too. */
        fun withdrawn(): Live = copy(waiting = waiting.filterKeys { id -> chat.prompts.any { it.id == id } })
    }

    private val lock = Any()
    private var live = Live()  // guarded by lock
    private val shown = MutableStateFlow(Chat())
    val state: StateFlow<Chat> = shown.asStateFlow()

    /** Replaces the live value by [next] of it, atomically; returns the value before and after. Pure and quick inside. */
    private inline fun change(next: (Live) -> Live): Pair<Live, Live> = synchronized(lock) {
        val before = live
        live = next(before)
        shown.value = live.chat
        before to live
    }

    private fun say(notice: String) {
        change { it.copy(chat = it.chat.notice(notice)) }
    }

    /** The core session this chat sends to; the chat must be open. */
    val sessionId: String
        get() = checkNotNull(synchronized(lock) { live.session }) { "open() the chat first" }

    init {
        // Collect before the first request: the core does not replay events.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { core.events.collect(::on) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Nothing else shows the core's requests: one this chat cannot show is declined, or its turn would wait it out.
            core.prompts.collect { raw ->
                var asked: Prompt? = null
                change { now -> prompt(raw, now.session)?.let { p -> asked = p; now.copy(chat = now.chat.asked(p), waiting = now.waiting + (p.id to raw)) } ?: now }
                asked?.let(notifier::promptPending) ?: core.decline(raw, "not available in the Android app")
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

    /**
     * Continues stored session [sessionId]: the chat shows its transcript and sends to it from now on. A session still
     * in a turn comes back running (the reply so far, Stop, the foreground service), with the questions it waits on.
     */
    suspend fun resume(sessionId: String) {
        val resumed = core.call(Methods.SESSION_RESUME, SessionResumeParams(sessionId = sessionId, source = SOURCE))
        val inTurn = resumed.running == true
        val chat = Chat(entries = transcriptOf(resumed.messages),
                        streaming = if (inTurn) resumed.inflight?.assistant.orEmpty() else null)
        val open = resumed.openRequests.orEmpty().map { InTurnPrompt(it.id, it.method, it.params) }
        switchTo(resumed.sessionId, chat, resumed.info.desktopContract, open)
    }

    /**
     * Makes [session] the chat's session, showing [chat] with the cards of the [open] requests it can show; the previous
     * session's turn and questions are let go.
     */
    private fun switchTo(session: String, chat: Chat, reportedContract: JsonElement?, open: List<InTurnPrompt> = emptyList()) {
        val notice = contractNotice(reportedContract)
        val (before, after) = change { now ->
            val opened = open.mapNotNull { raw -> prompt(raw, session)?.let { it to raw } }
            // The same session resumed: questions it asked after the core's snapshot are still its own.
            val kept = if (now.session == session) now.chat.prompts.filter { p -> opened.none { it.first.id == p.id } }
                       .mapNotNull { p -> now.waiting[p.id]?.let { p to it } } else emptyList()
            val cards = opened + kept
            Live(session, cards.fold(notice?.let(chat::notice) ?: chat) { shownChat, (p, _) -> shownChat.asked(p) },
                 cards.associate { (p, raw) -> p.id to raw })
        }
        // The previous session's turn would end in events this chat no longer follows: it ends here for the app.
        syncTurn()
        // Questions that lost their card are settled rather than left waiting; one carried over (the same session
        // resumed) keeps its request and its card.
        val dropped = before.waiting.filterKeys { it !in after.waiting }
        dropped.values.forEach { core.decline(it, "the chat moved to a new session") }
        if (dropped.isNotEmpty()) notifier.promptsSettled()
        after.chat.prompts.filter { it.id !in before.waiting }.forEach(notifier::promptPending)
    }

    private val turnLock = Any()
    private var activeTurn: String? = null  // guarded by turnLock: the turn [turns] keeps alive now

    /**
     * Brings [turns] to the chat's current state: a turn for the session while its transcript runs, none otherwise.
     * Reconciled from the latest state under its own lock, so changes reported out of order by different threads
     * cannot leave a turn begun after it ended.
     */
    private fun syncTurn() = synchronized(turnLock) {
        val now = synchronized(lock) { live }
        val wanted = now.session?.takeIf { now.chat.running }
        if (wanted == activeTurn) return@synchronized
        activeTurn?.let(turns::end)
        wanted?.let(turns::begin)
        activeTurn = wanted
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
        change { it.copy(chat = it.chat.sent(text)) }
        try {
            core.call(Methods.PROMPT_SUBMIT, PromptSubmitParams(sessionId = session, text = JsonPrimitive(text)))
        } catch (refused: CoreError) {
            say(refused.message ?: "The core refused the prompt (${refused.code}).")
        } catch (failed: Exception) {  // anything else: the message did not go out, and the user hears it
            say(forUser(failed))
        }
    }

    /**
     * Stops the running turn, as the desktop's Stop does: the core interrupts it and ends it with an `interrupted`
     * completion, which ends the turn here too. Nothing runs, nothing is asked.
     */
    suspend fun cancel() {
        if (!shown.value.running) return
        try {
            core.call(Methods.SESSION_INTERRUPT, SessionInterruptParams(sessionId = sessionId))
        } catch (failed: Exception) {
            say(forUser(failed))
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
            say(refused.message ?: "The key could not be kept.")
            settle(prompt) { core.decline(it, "the app could not keep the key") }
            return
        } catch (failed: Exception) {
            say(forUser(failed))
            settle(prompt) { core.decline(it, "the app could not keep the key") }
            return
        } finally {
            value?.fill(0)
        }
        settle(prompt) { core.answer(it, Requests.SECRET, ValueResult("")) }
    }

    /** Sends the one answer [prompt] gets; one the core already withdrew is gone and gets none. */
    private fun settle(prompt: Prompt, send: (InTurnPrompt) -> Unit) {
        val (before, after) = change { it.copy(chat = it.chat.settled(prompt.id), waiting = it.waiting - prompt.id) }
        before.waiting[prompt.id]?.let(send)
        if (after.chat.prompts.isEmpty()) notifier.promptsSettled()
    }

    /**
     * One event of the core, applied only if it is the chat's session's at the moment of the change. The app keeps a
     * turn alive exactly while the transcript says it runs: the reducer decides when a turn ends.
     */
    private fun on(event: CoreEvent) {
        val session = event.sessionId ?: return
        val (before, after) = change { now -> if (now.session != session) now else now.copy(chat = now.chat.reduce(event)).withdrawn() }
        if (before === after) return
        if (before.chat.running != after.chat.running) syncTurn()
        if (before.chat.running && !after.chat.running) notifier.turnFinished(after.chat.entries.lastOrNull() as? Entry.Reply)
        // Withdrawn by the core: the turn no longer waits for them.
        if (after.waiting.size < before.waiting.size && after.chat.prompts.isEmpty()) notifier.promptsSettled()
    }
}
