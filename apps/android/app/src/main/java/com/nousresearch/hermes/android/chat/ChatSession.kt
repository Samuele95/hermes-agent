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
import com.nousresearch.hermes.bridge.protocol.SessionCompressParams
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownParams
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownResult
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
import com.nousresearch.hermes.bridge.protocol.SessionHistoryParams
import com.nousresearch.hermes.bridge.protocol.SessionResumeParams
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import com.nousresearch.hermes.bridge.protocol.SessionListParams
import com.nousresearch.hermes.bridge.protocol.SessionMostRecentParams
import com.nousresearch.hermes.bridge.protocol.SessionTitleParams
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.nousresearch.hermes.bridge.protocol.BackendContract
import com.nousresearch.hermes.bridge.protocol.SessionInterruptParams
import com.nousresearch.hermes.bridge.protocol.ValueResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

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
        /** Said when a delete could not close the session first (W5, W6, W8): nothing was deleted. */
        const val NOT_CLOSED = "Hermes could not close it yet, so it was not deleted; try again in a moment."
        /** Said when Compress is asked for while a reply is being written. */
        const val STILL_REPLYING = "Hermes is still replying; compress when the reply has ended."
        /** Said when a prompt, a switch or a second compression is asked for while the core compresses the chat's session (invariant 6). */
        const val STILL_COMPRESSING = "Hermes is compressing this conversation; try again when it has finished."
        /** Said when Compress is asked for before the chat has a session (improve #6). */
        const val NOTHING_TO_COMPRESS = "Nothing to compress yet."
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
    private val holds = AtomicLong()  // one TurnHost key per compression (improve #2, V8)
    private var live = Live()  // guarded by lock
    private val shown = MutableStateFlow(Chat())
    val state: StateFlow<Chat> = shown.asStateFlow()
    /** Open/resume and the close of a left session, one at a time: a resume of the left session cannot race its close (D3). */
    private val switching = Mutex()
    /** The sessions this chat left and their closes (B3-D2/D3/D5; improve #5). */
    private val leftSessions = LeftSessions(scope, switching, stillHere = { synchronized(lock) { live.ref?.runtime } == it },
                                            close = { core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it.value)) })

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

    /** The runtime id of the core session this chat sends to (the id its frames carry, never a drawer row's key); the chat must be open. */
    val sessionId: RuntimeSessionId
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
     * `false`, when it has none, or when that one cannot be resumed or read. Which conversation is last is the core's to say:
     * the app keeps no copy, so a session the gateway process held counts too.
     */
    suspend fun start() {
        val last = lastSession()
        // Deleted, unreadable or undecodable since it was listed: a new chat, quietly (the TUI's rule; I1-V1, V3).
        if (last != null && orElse(false) { resumed(last); true }) return
        opened()
    }

    /** The stored id to reopen, or null: the setting is off, or the core has none (D2). An ordinary value (EJ-69). */
    private suspend fun lastSession(): StoredSessionId? {
        if (!reopensLast()) return null
        // A core without the method, or an answer this app cannot read: nothing to reopen (D2).
        return orElse(null) { core.call(Methods.SESSION_MOST_RECENT, SessionMostRecentParams()).sessionId?.ifBlank { null }?.let(::StoredSessionId) }
    }

    /** `display.resume_last_session` is not `false`: unset means the default (on), as does a config that cannot be read or decoded (D1). */
    private suspend fun reopensLast(): Boolean = orElse(true) {
        val display = core.call(Methods.CONFIG_GET, ConfigGetParams(key = "full")).config?.get("display") as? JsonObject
        display?.get("resume_last_session") != JsonPrimitive(false)
    }

    /**
     * Opens a new core session; reopening leaves the previous session behind, closed in the core unless a turn still
     * runs in it (its stored row stays resumable).
     */
    suspend fun open() {
        opened()
    }

    /** [open]'s work; returns the outcome of the close of the session it left, if any, which the screen does not wait for. */
    private suspend fun opened(): Deferred<LeftSessions.Outcome>? = switching.withLock { opening() }

    /** [opened] with [switching] already held. */
    private suspend fun opening(): Deferred<LeftSessions.Outcome>? {
        refuseWhileCompressing()
        val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = SOURCE))
        val chat = Chat(model = created.info.model.ifBlank { null }, meter = created.info.usage?.let(ContextMeter::of))
        return switchTo(SessionRef(RuntimeSessionId(created.sessionId), StoredSessionId(created.storedSessionId)), chat, created.info.desktopContract)
    }

    /** Past conversations with something in them, as the core lists them (newest first). */
    suspend fun pastSessions(): List<SessionListRow> =
        core.call(Methods.SESSION_LIST, SessionListParams(limit = 100)).sessions.filter { it.messageCount > 0 }

    /** What fills the session's context, computed by the core when asked (`session.context_breakdown`). */
    suspend fun breakdown(): SessionContextBreakdownResult =
        core.call(Methods.SESSION_CONTEXT_BREAKDOWN, SessionContextBreakdownParams(sessionId = sessionId.value))

    /**
     * Deletes stored session [stored] (a drawer row's key). The core refuses a session live here, so the chat's own is
     * left first, a new chat opening and the old one closing (invariant 4); not while its turn runs. Whether the row is
     * the chat's own is decided with [switching] held, so no open or resume slips in between (e). The delete then acts on
     * what became of that close, within a bound (W4): one that could not close puts the chat back on the row and says
     * so, [NOT_CLOSED], rather than meeting the core's refusal (W5, W6). A row whose left session is still closing waits
     * for that close (W7); the delete itself goes out with [switching] held and the own-row check made again (W8).
     */
    suspend fun delete(stored: StoredSessionId) {
        var moved: RuntimeSessionId? = null  // the new session the chat moved to off its own row, if it did
        val closing = switching.withLock {  // (e): own or not is decided with no open/resume in between
            val now = synchronized(lock) { live }
            if (now.ref?.stored == stored) {
                if (now.chat.busy) throw SetupRefusal(STILL_WORKING)
                opening().also { moved = synchronized(lock) { live.ref?.runtime } }
            } else leftSessions.closing(stored)  // W7: a row the chat left that is still closing
        }
        val outcome = closing?.let { leftSessions.waitFor(it) }  // bounded (W4); null: still closing
        switching.withLock {
            if (closing != null && outcome != LeftSessions.Outcome.CLOSED) {
                // W5: not closed, still live; the chat goes back to the row if it still sits where this delete put it.
                val back = (outcome == LeftSessions.Outcome.FAILED || outcome == LeftSessions.Outcome.UNSETTLED) &&
                    moved != null && synchronized(lock) { live.ref?.runtime } == moved
                if (back) orElse(Unit) { resuming(stored) }
                throw SetupRefusal(NOT_CLOSED)
            }
            if (synchronized(lock) { live.ref?.stored } == stored) throw SetupRefusal(NOT_CLOSED)  // W8: back on it meanwhile
            deleteRow(stored)
        }
    }

    /** `session.delete` of [stored]: gone already is done (4007); a session still live in a turn is refused (4023). */
    private suspend fun deleteRow(stored: StoredSessionId) {
        try {
            core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored.value))
        } catch (refused: CoreError) {
            when (refused.code) {
                4007 -> Unit                                      // already gone: what was asked (D4)
                4023 -> throw SetupRefusal(STILL_WORKING)          // a left session whose turn still runs (§8)
                else -> throw refused
            }
        }
    }

    /**
     * Renames stored session [stored] (a drawer row's key) to [title]. The core titles live sessions only
     * (`session.title`): the chat's own is titled where it is; any other row is attached aside — resumed lazily, never
     * shown — titled, and let go again (DC-B7). A refusal (a title in use, too long) is thrown in the core's words; a
     * row attached aside is let go either way.
     */
    suspend fun rename(stored: StoredSessionId, title: String): Unit = switching.withLock {  // D3: no switch interleaves
        val own = synchronized(lock) { live.ref }
        if (own?.stored == stored) {  // D4: the listing's id is the tip, which SessionRef follows
            core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = own.runtime.value, title = title))
            return@withLock
        }
        // D1: an agent-less read-only mount: no build to wait for, no transcript, no auto-continue.
        val attached = core.call(Methods.SESSION_RESUME,
                                 SessionResumeParams(sessionId = stored.value, source = SOURCE, lazy = true, omitMessages = true))
        val side = RuntimeSessionId(attached.sessionId)  // the resume boundary: the reply's session_id is the runtime id
        try {
            core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = side.value, title = title))
        } finally {
            // D2: only what this rename attached is let go. The core answers a session live here as it is: one still
            // writing, or the chat's own reached by another key of its lineage, runs on (invariant 3).
            if (attached.running != true && side != synchronized(lock) { live.ref?.runtime }) closeAside(side)
        }
    }

    /**
     * Continues stored conversation [stored] (a drawer row's key): the chat shows its transcript and sends to it from now on. A session still
     * in a turn comes back running (the reply so far, Stop, the foreground service), with the questions it waits on.
     */
    suspend fun resume(stored: StoredSessionId) {
        resumed(stored)
    }

    /** [resume]'s work; returns the outcome of the close of the session it left, if any. */
    private suspend fun resumed(stored: StoredSessionId): Deferred<LeftSessions.Outcome>? = switching.withLock { resuming(stored) }

    /** [resumed] with [switching] already held. */
    private suspend fun resuming(stored: StoredSessionId): Deferred<LeftSessions.Outcome>? {
        refuseWhileCompressing()
        val resumed = core.call(Methods.SESSION_RESUME, SessionResumeParams(sessionId = stored.value, source = SOURCE))
        val inTurn = resumed.running == true
        val chat = Chat(entries = transcriptOf(resumed.messages),
                        streaming = if (inTurn) resumed.inflight?.assistant.orEmpty() else null, model = resumed.info.model.ifBlank { null },
                        todos = resumed.todoState?.let { TodoList.of(it.todos, it.revision) }, meter = resumed.info.usage?.let(ContextMeter::of))
        val open = resumed.openRequests.orEmpty().map { InTurnPrompt(it.id, it.method, it.params) }
        // The live info's key first (the core's own); the reply's session_key next; the caller's id as the last resort.
        val key = (resumed.info.storedSessionId.ifBlank { null } ?: resumed.sessionKey ?: resumed.storedSessionId)?.let(::StoredSessionId) ?: stored
        return switchTo(SessionRef(RuntimeSessionId(resumed.sessionId), key), chat, resumed.info.desktopContract, open)
    }

    /** Invariant 6: no switch while the core compresses the chat's session; [switching] is held, so none is admitted meanwhile. */
    private fun refuseWhileCompressing() {
        if (synchronized(lock) { live.chat.compressing }) throw SetupRefusal(STILL_COMPRESSING)
    }

    /**
     * Makes [ref] the chat's session, showing [chat] with the cards of the [open] requests it can show; the previous
     * session's turn and questions are let go, and the session itself is closed in the core if it was idle: the
     * returned value is that close's outcome.
     */
    private fun switchTo(ref: SessionRef, chat: Chat, reportedContract: JsonElement?,
                         open: List<InTurnPrompt> = emptyList()): Deferred<LeftSessions.Outcome>? {
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
        // Invariant 3, decided from the one atomic swap (EJ-78): an idle session the chat left is closed; a busy one (a turn
        // running or about to) runs on.
        val left = before.ref?.takeIf { !before.chat.busy && it.runtime != after.ref?.runtime }
        // The previous session's turn would end in events this chat no longer follows: it ends here for the app.
        syncTurn()
        // Questions that lost their card are settled rather than left waiting; one carried over (the same session
        // resumed) keeps its request and its card.
        val dropped = before.waiting.filterKeys { it !in after.waiting }
        dropped.values.forEach { core.decline(it, "the chat moved to a new session") }
        if (dropped.isNotEmpty()) notifier.promptsSettled()
        after.chat.prompts.filter { it.id !in before.waiting }.forEach(notifier::promptPending)
        return left?.let(leftSessions::leave)
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
        val wanted = now.ref?.runtime?.value?.takeIf { now.chat.running }
        if (wanted == activeTurn) return@synchronized
        activeTurn?.let(turns::end)
        wanted?.let(turns::begin)
        activeTurn = wanted
    }

    /** Closes [runtime], attached aside by [rename], at once: a lazy mount builds no agent, so nothing settles (D2). Best-effort (B3's D5). */
    private suspend fun closeAside(runtime: RuntimeSessionId) {
        try {
            core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = runtime.value))
            leftSessions.forget(runtime)  // a session.info the title emitted marked it; it is gone now
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed  // a failed close leaves it live, as before
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

    /**
     * [step]'s value, or [fallback] when it fails in any way but cancellation: the core's refusal, a reply this app
     * cannot decode, a broken bridge. Only for a step whose failure has a documented fallback (start's reopen, retry's
     * row lookup), so every failure of it is substitutable for that outcome (SOLID-L, as [forUser] is one rule).
     */
    private inline fun <T> orElse(fallback: T, step: () -> T): T = try {
        step()
    } catch (failed: Exception) {
        if (failed is CancellationException) throw failed  // not a failure: the caller's scope is ending
        fallback
    }

    /**
     * Sends [text] to the chat's session; nothing while a turn runs or is about to (Stop stands where Send was); refused
     * while the session compresses.
     */
    suspend fun send(text: String) {
        var session: RuntimeSessionId? = null
        change { now ->  // the session and the bubble decided in one change (EJ-78)
            val ref = checkNotNull(now.ref) { "open() the chat first" }
            if (now.chat.compressing) throw SetupRefusal(STILL_COMPRESSING)  // invariant 6 (V4)
            if (now.chat.busy) now else { session = ref.runtime; now.copy(chat = now.chat.sent(text)) }
        }
        val to = session ?: return  // V5: a second tap before the turn started
        try {
            val result = core.call(Methods.PROMPT_SUBMIT, PromptSubmitParams(sessionId = to.value, text = JsonPrimitive(text)))
            result.userRowId?.let { bind(to, it) }
        } catch (refused: CoreError) {
            unsent(to, refused.message ?: "The core refused the prompt (${refused.code}).")
        } catch (failed: Exception) {  // anything else: the message did not go out, and the user hears it
            unsent(to, forUser(failed))
        }
    }

    /** [session]'s prompt did not go out: idle again if the chat is still there; [words] said either way (V7). */
    private fun unsent(session: RuntimeSessionId, words: String) {
        change { now -> now.copy(chat = (if (now.ref?.runtime == session) now.chat.unsent() else now.chat).notice(words)) }
    }

    /** The core named the row of [session]'s newest prompt; only while the chat is still on that session. */
    private fun bind(session: RuntimeSessionId, rowId: Long) {
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
                sessionId = session.value, text = JsonPrimitive(target.text), truncateBeforeRowId = rowId,
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
    // No history to read or decode: a plain resubmit, as the desktop degrades (B5-D1).
    private suspend fun rowIdOf(session: RuntimeSessionId, text: String): Long? = orElse(null) {
        core.call(Methods.SESSION_HISTORY, SessionHistoryParams(sessionId = session.value)).messages
            .filter { it.role == "user" && it.displayKind == null && it.rowId != null }
            .singleOrNull { it.text.orEmpty().trim() == text.trim() }?.rowId
    }

    /** After a refused retry: the core's transcript re-read (invariant 5), else [before] (D5); then [words]. */
    private suspend fun resync(session: RuntimeSessionId, before: List<Entry>, words: String) {
        val entries = try {
            transcriptOf(core.call(Methods.SESSION_HISTORY, SessionHistoryParams(sessionId = session.value)).messages)
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            before
        }
        change { now ->
            when {
                now.ref?.runtime != session -> now
                now.chat.running -> now.copy(chat = now.chat.notice(words))  // a turn began after all: its events are the truth
                else -> now.copy(chat = now.chat.copy(entries = entries).unsent().notice(words))
            }
        }
    }

    /**
     * Compresses the chat's session, as the desktop's /compress does: the core summarizes the earlier conversation (on
     * [focus] when given; its own forms like `here 2` too) and answers with what it kept, which the chat then shows with
     * the new fill (invariant 5). Not while a turn runs or another compression of it does; while it runs no prompt,
     * switch or own-row delete is admitted (invariant 6). [turns] keeps the app alive meanwhile under a key of its own
     * (DC-B10, improve #2). Returns what it did, in the core's words; a refusal is thrown, also when no session is open.
     */
    suspend fun compress(focus: String?): String {
        // Admitted with [switching] held (V3): no open, resume or own-row delete is half done, and none starts until
        // the flag is set (invariant 6). Decided in one change; a refusal leaves the chat as it was.
        val session = switching.withLock {
            var admitted: RuntimeSessionId? = null
            change { now ->
                val ref = now.ref ?: throw SetupRefusal(NOTHING_TO_COMPRESS)
                if (now.chat.busy) throw SetupRefusal(STILL_REPLYING)
                if (now.chat.compressing) throw SetupRefusal(STILL_COMPRESSING)
                admitted = ref.runtime
                now.copy(chat = now.chat.copy(compressing = true))
            }
            checkNotNull(admitted)
        }
        val hold = "compress:${session.value}#${holds.incrementAndGet()}"  // its own, never the turn's key (DC-B10, V8)
        turns.begin(hold)
        try {
            val result = core.call(Methods.SESSION_COMPRESS,
                                   SessionCompressParams(sessionId = session.value, focusTopic = focus?.trim()?.ifEmpty { null }))
            change { now ->
                // The chat left, or a turn began or was sent meanwhile: that session's own frames are the truth (as resync; I4-V4).
                val ref = now.ref
                if (ref?.runtime != session || now.chat.busy) now
                else now.copy(ref = result.info?.storedSessionId?.ifBlank { null }?.let { ref.copy(stored = StoredSessionId(it)) } ?: ref,  // D4
                              chat = now.chat.compressed(result).copy(compressing = false))
            }
            return compressionOutcome(result)
        } finally {
            // Success, refusal, failure or cancellation: the session is the chat's again (V7).
            change { now -> if (now.ref?.runtime == session && now.chat.compressing) now.copy(chat = now.chat.copy(compressing = false)) else now }
            turns.end(hold)
        }
    }

    /**
     * Stops the running turn, as the desktop's Stop does: the core interrupts it and ends it with an `interrupted`
     * completion, which ends the turn here too; or the turn about to start, which the core ends with an `error`.
     * Nothing runs, nothing is asked.
     */
    suspend fun cancel() {
        // The session seen busy, read with its state (EJ-78): Stop before the turn started cancels it too (V6).
        val session = synchronized(lock) { live.ref?.runtime?.takeIf { live.chat.busy } } ?: return
        try {
            core.call(Methods.SESSION_INTERRUPT, SessionInterruptParams(sessionId = session.value))
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
        val session = RuntimeSessionId(event.sessionId ?: return)
        if (event.type == Events.SESSION_INFO.name || event.type == Events.ERROR.name) leftSessions.settled(session)
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
