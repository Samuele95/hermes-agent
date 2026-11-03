package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.SetupRefusal
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
import com.nousresearch.hermes.bridge.protocol.ConfigGetParams
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PromptSubmitParams
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import com.nousresearch.hermes.bridge.protocol.Requests
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
import com.nousresearch.hermes.bridge.protocol.SessionHistoryParams
import com.nousresearch.hermes.bridge.protocol.SessionResumeParams
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import com.nousresearch.hermes.bridge.protocol.SessionListParams
import com.nousresearch.hermes.bridge.protocol.SessionMostRecentParams
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.nousresearch.hermes.bridge.protocol.BackendContract
import com.nousresearch.hermes.bridge.protocol.SessionInterruptParams
import com.nousresearch.hermes.bridge.protocol.ValueResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonPrimitive

/** The core session a chat follows: [runtime] is the id its frames carry, [stored] the key its stored row goes by. */
data class SessionRef(val runtime: String, val stored: String) {
    /** After [event] of this session: a `session.info` names the stored key, which a compression rotates. */
    fun seen(event: CoreEvent): SessionRef = if (event.type != Events.SESSION_INFO.name) this else
        runCatching { event.decode(Events.SESSION_INFO)!!.storedSessionId }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { copy(stored = it) } ?: this
}

/**
 * One chat: a core session, the prompts sent to it, its turns, which [turns] keeps alive, and the questions
 * a turn asks, which [notifier] hears of along with finished turns. A secret the agent asks for goes to
 * [keepSecret] (the keystore), never back to the core: the core would write an answered value to `.env`.
 */
class ChatSession(private val core: CoreGateway, private val turns: TurnHost, private val scope: CoroutineScope,
                  private val keepSecret: suspend (name: String, value: ByteArray) -> Unit,
                  private val notifier: Notifier = Notifier.None) {
    companion object {
        /** The session's source, which is also the agent's platform; "android" keeps the CLI toolsets. */
        const val SOURCE = "android"
        /** Said when a session cannot be deleted because a reply is still being written in it. */
        const val STILL_WORKING = "It is still working; delete it when it finishes."
    }

    /**
     * Everything the chat changes together: the core session it follows, its transcript, and the core's requests
     * behind the transcript's question cards. Events, questions and session switches arrive on different threads;
     * each change is one [change] of this one value, so none of them can see or leave a half-made state.
     */
    private data class Live(val ref: SessionRef? = null, val chat: Chat = Chat(), val waiting: Map<String, InTurnPrompt> = emptyMap()) {
        /** The requests the core withdrew (their cards are gone from the transcript) let go too. */
        fun withdrawn(): Live = copy(waiting = waiting.filterKeys { id -> chat.prompts.any { it.id == id } })
    }

    private val lock = Any()
    private var live = Live()  // guarded by lock
    private val shown = MutableStateFlow(Chat())
    val state: StateFlow<Chat> = shown.asStateFlow()
    /** Sessions whose agent build has announced itself (`session.info`) or failed (`error`): only those are closed (D2). */
    private val settled = MutableStateFlow<Set<String>>(emptySet())
    /** Open/resume and the close of a left session, one at a time: a resume of the left session cannot race its close (D3). */
    private val switching = Mutex()

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
        get() = checkNotNull(synchronized(lock) { live.ref?.runtime }) { "open() the chat first" }

    init {
        // Collect before the first request: the core does not replay events.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { core.events.collect(::on) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Nothing else shows the core's requests: one this chat cannot show is declined, or its turn would wait it out.
            core.prompts.collect { raw ->
                var asked: Prompt? = null
                change { now -> prompt(raw, now.ref?.runtime)?.let { p -> asked = p; now.copy(chat = now.chat.asked(p), waiting = now.waiting + (p.id to raw)) } ?: now }
                asked?.let(notifier::promptPending) ?: core.decline(raw, "not available in the Android app")
            }
        }
    }

    /**
     * Opens the chat at process start in the conversation the core says was last (`session.most_recent`), as the
     * desktop's "Reopen last chat on launch" does; a new one when the core's `display.resume_last_session` is
     * `false`, when it has none, or when that one cannot be resumed. Which conversation is last is the core's to say:
     * the app keeps no copy, so a session the gateway process held counts too.
     */
    suspend fun start() {
        val last = lastSession()
        if (last != null) {
            try {
                resumed(last)
                return
            } catch (gone: CoreError) {  // deleted or unreadable since it was listed: a new chat, quietly (the TUI's rule)
            }
        }
        opened()
    }

    /** The stored id to reopen, or null: the setting is off, or the core has none (D2). An ordinary value (EJ-69). */
    private suspend fun lastSession(): String? {
        if (!reopensLast()) return null
        return try {
            core.call(Methods.SESSION_MOST_RECENT, SessionMostRecentParams()).sessionId?.ifBlank { null }
        } catch (refused: CoreError) {  // a core without the method: nothing to reopen
            null
        }
    }

    /** `display.resume_last_session` is not `false`: unset means the default (on), as does an unreadable config (D1). */
    private suspend fun reopensLast(): Boolean = try {
        val display = core.call(Methods.CONFIG_GET, ConfigGetParams(key = "full")).config?.get("display") as? JsonObject
        display?.get("resume_last_session") != JsonPrimitive(false)
    } catch (refused: CoreError) {
        true
    }

    /**
     * Opens a new core session; reopening leaves the previous session behind, closed in the core unless a turn still
     * runs in it (its stored row stays resumable).
     */
    suspend fun open() {
        opened()
    }

    /** [open]'s work; returns the close of the session it left, if any, which the screen does not wait for. */
    private suspend fun opened(): Job? = switching.withLock {
        val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = SOURCE))
        switchTo(SessionRef(created.sessionId, created.storedSessionId), Chat(model = created.info.model.ifBlank { null }), created.info.desktopContract)
    }

    /** Past conversations with something in them, as the core lists them (newest first). */
    suspend fun pastSessions(): List<SessionListRow> =
        core.call(Methods.SESSION_LIST, SessionListParams(limit = 100)).sessions.filter { it.messageCount > 0 }

    /**
     * Deletes stored session [storedId] (a drawer row's id). The core refuses a session live here, so the chat's own is
     * left first, a new chat opening and the old one closing (invariant 4); not while its turn runs.
     */
    suspend fun delete(storedId: String) {
        val now = synchronized(lock) { live }
        if (now.ref?.stored == storedId) {
            if (now.chat.running) throw SetupRefusal(STILL_WORKING)
            opened()?.join()
        }
        try {
            core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = storedId))
        } catch (refused: CoreError) {
            when (refused.code) {
                4007 -> Unit                                      // already gone: what was asked (D4)
                4023 -> throw SetupRefusal(STILL_WORKING)          // a left session whose turn still runs (§8)
                else -> throw refused
            }
        }
    }

    /**
     * Continues stored session [sessionId]: the chat shows its transcript and sends to it from now on. A session still
     * in a turn comes back running (the reply so far, Stop, the foreground service), with the questions it waits on.
     */
    suspend fun resume(sessionId: String) {
        resumed(sessionId)
    }

    /** [resume]'s work; returns the close of the session it left, if any. */
    private suspend fun resumed(sessionId: String): Job? = switching.withLock {
        val resumed = core.call(Methods.SESSION_RESUME, SessionResumeParams(sessionId = sessionId, source = SOURCE))
        val inTurn = resumed.running == true
        val chat = Chat(entries = transcriptOf(resumed.messages),
                        streaming = if (inTurn) resumed.inflight?.assistant.orEmpty() else null, model = resumed.info.model.ifBlank { null })
        val open = resumed.openRequests.orEmpty().map { InTurnPrompt(it.id, it.method, it.params) }
        // The live info's key first (the core's own); the reply's session_key next; the caller's id as the last resort.
        val stored = resumed.info.storedSessionId.ifBlank { null } ?: resumed.sessionKey ?: resumed.storedSessionId ?: sessionId
        switchTo(SessionRef(resumed.sessionId, stored), chat, resumed.info.desktopContract, open)
    }

    /**
     * Makes [ref] the chat's session, showing [chat] with the cards of the [open] requests it can show; the previous
     * session's turn and questions are let go, and the session itself is closed in the core if it was idle: the
     * returned job is that close.
     */
    private fun switchTo(ref: SessionRef, chat: Chat, reportedContract: JsonElement?, open: List<InTurnPrompt> = emptyList()): Job? {
        val notice = contractNotice(reportedContract)
        val (before, after) = change { now ->
            val opened = open.mapNotNull { raw -> prompt(raw, ref.runtime)?.let { it to raw } }
            // The same session resumed: questions it asked after the core's snapshot are still its own.
            val kept = if (now.ref?.runtime == ref.runtime) now.chat.prompts.filter { p -> opened.none { it.first.id == p.id } }
                       .mapNotNull { p -> now.waiting[p.id]?.let { p to it } } else emptyList()
            val cards = opened + kept
            Live(ref, cards.fold(notice?.let(chat::notice) ?: chat) { shownChat, (p, _) -> shownChat.asked(p) },
                 cards.associate { (p, raw) -> p.id to raw })
        }
        // Invariant 3, decided from the one atomic swap (EJ-78): an idle session the chat left is closed; a running one runs on.
        val left = before.ref?.runtime?.takeIf { !before.chat.running && it != after.ref?.runtime }
        // The previous session's turn would end in events this chat no longer follows: it ends here for the app.
        syncTurn()
        // Questions that lost their card are settled rather than left waiting; one carried over (the same session
        // resumed) keeps its request and its card.
        val dropped = before.waiting.filterKeys { it !in after.waiting }
        dropped.values.forEach { core.decline(it, "the chat moved to a new session") }
        if (dropped.isNotEmpty()) notifier.promptsSettled()
        after.chat.prompts.filter { it.id !in before.waiting }.forEach(notifier::promptPending)
        return left?.let(::closeWhenSettled)
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
        val wanted = now.ref?.runtime?.takeIf { now.chat.running }
        if (wanted == activeTurn) return@synchronized
        activeTurn?.let(turns::end)
        wanted?.let(turns::begin)
        activeTurn = wanted
    }

    /** Closes [left] once its agent build has settled (D2), unless the chat came back to it meanwhile (D3). Best-effort (D5). */
    private fun closeWhenSettled(left: String): Job = scope.launch {
        settled.first { left in it }
        switching.withLock {
            if (synchronized(lock) { live.ref?.runtime } == left) return@withLock
            try {
                core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = left))
                settled.update { it - left }
            } catch (failed: Exception) {
                if (failed is CancellationException) throw failed  // a failed close leaves it live, as before (D5)
            }
        }
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
            val result = core.call(Methods.PROMPT_SUBMIT, PromptSubmitParams(sessionId = session, text = JsonPrimitive(text)))
            result.userRowId?.let { bind(session, it) }
        } catch (refused: CoreError) {
            say(refused.message ?: "The core refused the prompt (${refused.code}).")
        } catch (failed: Exception) {  // anything else: the message did not go out, and the user hears it
            say(forUser(failed))
        }
    }

    /** The core named the row of [session]'s newest prompt; only while the chat is still on that session. */
    private fun bind(session: String, rowId: Long) {
        change { if (it.ref?.runtime == session) it.copy(chat = it.chat.boundUser(rowId)) else it }
    }

    /**
     * Runs the last prompt again, as the desktop's Retry does: the core cuts its transcript before that prompt's row
     * (`truncate_before_row_id`) and answers it anew. A prompt whose row the chat never learned is looked up in the
     * core's history by its exact words; with no single match it is sent again after the old turn, nothing cut (D1).
     * A refusal shows the core's transcript, re-read, with the core's words: never the one the app predicted.
     */
    suspend fun retry() {
        val (session, target) = synchronized(lock) { live.ref?.runtime to live.chat.retryTarget() }
        if (session == null || target == null) return
        val rowId = target.rowId ?: rowIdOf(session, target.text)
        var shownBefore: List<Entry>? = null
        change { now ->
            val again = now.chat.retryTarget()
            // Decided in the one change that rewinds (EJ-78, D4): the chat left, a turn began, or the prompt moved → nothing.
            if (now.ref?.runtime != session || again?.index != target.index || again.text != target.text) now
            else {
                shownBefore = now.chat.entries
                now.copy(chat = (if (rowId != null) now.chat.rewound(target) else now.chat).sent(target.text))
            }
        }
        val before = shownBefore ?: return
        try {
            val result = core.call(Methods.PROMPT_SUBMIT, PromptSubmitParams(
                sessionId = session, text = JsonPrimitive(target.text), truncateBeforeRowId = rowId,
                // The core cuts only with consent; a first-turn cut empties the transcript, which needs its own (D7: never deep).
                confirmTruncate = rowId?.let { true }, confirmEmptyTruncate = rowId?.let { true }))
            result.userRowId?.let { bind(session, it) }
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            val words = if (failed is CoreError) failed.message ?: "The core refused the retry (${failed.code})." else forUser(failed)
            resync(session, before, words)
        }
    }

    /** The row of [text] in the core's history: the one user row with exactly those words, else null (D6, EJ-62). */
    private suspend fun rowIdOf(session: String, text: String): Long? = try {
        core.call(Methods.SESSION_HISTORY, SessionHistoryParams(sessionId = session)).messages
            .filter { it.role == "user" && it.displayKind == null && it.rowId != null }
            .singleOrNull { it.text.orEmpty().trim() == text.trim() }?.rowId
    } catch (refused: CoreError) {  // no history to read: a plain resubmit, as the desktop degrades
        null
    }

    /** After a refused retry: the core's transcript re-read (invariant 5), else [before] (D5); then [words]. */
    private suspend fun resync(session: String, before: List<Entry>, words: String) {
        val entries = try {
            transcriptOf(core.call(Methods.SESSION_HISTORY, SessionHistoryParams(sessionId = session)).messages)
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            before
        }
        change { now ->
            when {
                now.ref?.runtime != session -> now
                now.chat.running -> now.copy(chat = now.chat.notice(words))  // a turn began after all: its events are the truth
                else -> now.copy(chat = now.chat.copy(entries = entries).notice(words))
            }
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
        if (event.type == Events.SESSION_INFO.name || event.type == Events.ERROR.name) settled.update { it + session }
        val (before, after) = change { now ->
            if (now.ref?.runtime != session) now else now.copy(ref = now.ref.seen(event), chat = now.chat.reduce(event)).withdrawn()
        }
        if (before === after) return
        if (before.chat.running != after.chat.running) syncTurn()
        if (before.chat.running && !after.chat.running) notifier.turnFinished(after.chat.entries.lastOrNull() as? Entry.Reply)
        // Withdrawn by the core: the turn no longer waits for them.
        if (after.waiting.size < before.waiting.size && after.chat.prompts.isEmpty()) notifier.promptsSettled()
    }
}
