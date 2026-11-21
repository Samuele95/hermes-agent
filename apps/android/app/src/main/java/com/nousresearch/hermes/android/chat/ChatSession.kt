package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.android.entry.IncomingText
import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.InTurnPrompt
import com.nousresearch.hermes.bridge.answer
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.protocol.ApprovalResult
import com.nousresearch.hermes.bridge.protocol.ClarifyResult
import com.nousresearch.hermes.bridge.protocol.CommandDispatchParams
import com.nousresearch.hermes.bridge.protocol.CommandsCatalogParams
import com.nousresearch.hermes.bridge.protocol.CompleteSlashParams
import com.nousresearch.hermes.bridge.protocol.ConfigGetParams
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.MessageReactParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ReactionAuthor
import com.nousresearch.hermes.bridge.protocol.PromptSubmitParams
import com.nousresearch.hermes.bridge.protocol.PromptSubmitResult
import com.nousresearch.hermes.bridge.protocol.PromptSubmitStatus
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import com.nousresearch.hermes.bridge.protocol.Requests
import com.nousresearch.hermes.bridge.protocol.SessionCompressParams
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownParams
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownResult
import com.nousresearch.hermes.bridge.protocol.SessionCorrectionParams
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
import com.nousresearch.hermes.bridge.protocol.SessionHistoryParams
import com.nousresearch.hermes.bridge.protocol.SessionResumeParams
import com.nousresearch.hermes.bridge.protocol.SessionStatusParams
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import com.nousresearch.hermes.bridge.protocol.SessionListParams
import com.nousresearch.hermes.bridge.protocol.SessionMostRecentParams
import com.nousresearch.hermes.bridge.protocol.SessionTitleParams
import com.nousresearch.hermes.bridge.protocol.TranscriptMessage
import com.nousresearch.hermes.bridge.protocol.TurnStatus
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One chat: a core session, the prompts sent to it, its turns, which [turns] keeps alive, and the questions
 * a turn asks, which [notifier] hears of along with finished turns. A secret the agent asks for goes to
 * [keepSecret] (the keystore), never back to the core: the core would write an answered value to `.env`.
 * [gatewaySeen] is the app's observation of the always-on gateway: a job the agent schedules while it will not fire is said once (D39 F11).
 * [runOwned] asks whether the gateway's scheduler still owns a job's run (the app's `cron_run_owned`); while it does,
 * the chat shows the run and sends nothing into it.
 * [queues] keeps each stored session's queue of unsent messages across process deaths (batch-3 F2, DC-M5).
 * [providerFailed] hears of a turn of this chat that failed on its provider's side (auth, provider, billing): readiness
 * is checked again (F6).
 * [exporter] writes a stored session's file into the app's cache (the app's `session_export`, sessions-12 F8); without
 * one, nothing is exported.
 * [log] hears what the chat notes for diagnosis only, never shown: a drained queue item the core put in its own queue
 * (/architect #5); it must not throw.
 * [clock] is the wall clock (epoch ms) a message's time and a turn's length are read from; tests fix it.
 * [openModels] opens this chat's model picker, its search set to the words after `/model` (P6; D11, D13).
 */
class ChatSession(private val core: CoreGateway, private val turns: TurnHost, private val scope: CoroutineScope,
                  private val keepSecret: suspend (name: String, value: ByteArray) -> Unit,
                  private val notifier: Notifier = Notifier.None,
                  private val gatewaySeen: () -> GatewaySeen = { GatewaySeen(GatewayStatus.RUNNING) },
                  private val runOwned: suspend (StoredSessionId) -> Boolean? = { null },
                  private val queues: QueueStore = QueueStore.inMemory(),
                  private val providerFailed: suspend () -> Unit = {},
                  private val exporter: suspend (StoredSessionId) -> ExportFile = { throw SetupRefusal(NOTHING_TO_EXPORT) },
                  private val log: (String) -> Unit = {}, private val clock: () -> Long = System::currentTimeMillis,
                  private val openModels: suspend (search: String) -> Unit = {}) {
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
        /** Said once per session when the agent schedules a job the gateway will not fire yet (D39 F11). */
        const val JOBS_NEED_GATEWAY = "Scheduled jobs run only while the gateway is on — drawer → Gateway."
        /** Said when a send is refused because the gateway's scheduler still owns the job's run (/architect #4). */
        const val RUN_STILL_OWNED = "This job is still running in the background. You can read it here; write once it has finished."
        /** Said when the core turned a correction away because its turn had ended, and that turn was stopped or failed (D6). */
        const val CORRECTION_NOT_SENT = "The run ended before Hermes took this, so it was not sent."
        /** Said when the agent cannot take corrections (4010): the words wait at the queue's head instead (DC-M4, D10). */
        const val CORRECTION_QUEUED = "Hermes cannot take a correction in this run, so it waits in the queue for the next turn."
        /** Said when the chat's own export is asked for before the chat has a session (sessions-12, D7). */
        const val NOTHING_TO_EXPORT = "Nothing to export yet."
        /** Said when Status is asked for before the chat has a session (protocol-21). */
        const val NO_STATUS = "There is no session to report on yet."
        /** Said before why a reaction did not land (Desktop's "Could not react", reactions.ts:113). */
        const val REACT_FAILED = "Could not react"
        /** Said before why an edit was not sent (Desktop's `editFailed`). */
        const val EDIT_FAILED = "Edit failed"
        /** Why, when the core no longer holds the edited prompt's row (4018; Desktop's `editTurnUnavailable`, D8). */
        const val EDIT_UNAVAILABLE = "This turn is no longer in server history (it may have been compressed away)."
        /** The core's own words for a session still in a turn (`methods_prompt.py:822`). */
        const val SESSION_BUSY = "session busy"
        /** How long an edit waits for the turn it stopped to end (D3; Desktop's busy bound, `utils.ts:279`). */
        val STOP_WAIT = 6.seconds
        /** How often an edit that met 4009 is sent again after a second interrupt, and how far apart (D4, `utils.ts:279-280`). */
        const val BUSY_RESENDS = 40
        val BUSY_RESEND_GAP = 150.milliseconds
        /** Said for a command the core lists that only `slash.exec` runs (chat-21; Desktop's words, `slash.ts:289`; D4). */
        const val NOT_ON_PHONE = "%s is not available in the Android app."
        /** Said before why the core's slash catalog could not be read: the `/` line is not sent (D12). */
        const val SLASH_UNREAD = "Commands could not be read"
        /** Said when `/retry` finds no answered prompt to send again. */
        const val NOTHING_TO_RETRY = "There is nothing to retry now."
        /** `/steer` without words, in the core's words (`methods_tools.py:875`). */
        const val STEER_USAGE = "usage: /steer <prompt>"
        /** `/queue` without words, in the core's words (`methods_tools.py:756`). */
        const val QUEUE_USAGE = "usage: /queue <prompt>"
        /** A send directive with nothing to send (Desktop's words, `slash.ts:362-366`). */
        const val EMPTY_MESSAGE = "empty message"
        /** Steer now on a queued `/` line the catalog knows: a command is never words for the model (invariant 4; fix round 1). */
        const val NOT_STEERED = "%s is a command and cannot steer the turn; it stays in the queue."
    }

    /**
     * Everything the chat changes together: the core session it follows, its transcript, and the core's requests
     * behind the transcript's question cards. Events, questions and session switches arrive on different threads;
     * each change is one [change] of this one value, so none of them can see or leave a half-made state.
     * [jobsNoticed]: the chat has said [JOBS_NEED_GATEWAY] in this session.
     * [resend]: the id of a correction the core turned away (`rejected`) before this chat saw its turn end; it waits
     * for that end, in this session only (D6).
     * [queue]: the session's queue of unsent messages (§2.4); [nextQueued] the id the next queued item takes. It only
     * grows in this process, so a tap on a stale row never meets a new item (inv. 4).
     * [acting]: the app's own commands a slash line let go (a queue head, an alias's target) still acting; while any
     * acts the queue neither drains nor pauses, so no head leaves before the command has acted ([actFor]).
     */
    private data class Live(val ref: SessionRef? = null, val chat: Chat = Chat(), val waiting: Map<String, InTurnPrompt> = emptyMap(),
                            val jobsNoticed: Boolean = false, val resend: Long? = null, val queue: QueueUi = QueueUi(),
                            val nextQueued: Long = 0, val acting: Int = 0) {
        /** [text] at the queue's tail. */
        fun queued(text: String): Live = copy(queue = queue.added(text, nextQueued), nextQueued = nextQueued + 1)

        /** Stop or a turn that did not complete: the items listed wait for Resume; an empty queue stays unpaused (D5, DC-M6). */
        fun paused(): Live = copy(queue = queue.copy(paused = queue.items.isNotEmpty()))

        /**
         * The queue's head on its way, when nothing runs or is about to, in a session the chat may write to: its bubble
         * (sent [at], epoch ms: the time it went out) and its leaving the queue in one value, with its words to send;
         * else this and null (inv. 3, inv. 4).
         */
        fun draining(at: Long): Pair<Live, String?> {
            if (chat.busy || chat.compressing || chat.runOwned || acting > 0) return this to null
            val (item, rest) = queue.next() ?: return this to null
            // A slash head gets its bubble once the catalog says what it is (D9): a dispatch shows the core's display.
            val shown = if (item.text.startsWith("/")) chat.copy(submitted = true) else chat.sent(item.text, at)
            return copy(chat = shown, queue = rest) to item.text
        }

        /**
         * The queue as no change may leave it: never idle, unpaused and stranded with a head to send. After a turn that
         * completed the head goes out ([draining]; Desktop drains whenever idle and not parked, so a missed edge cannot
         * strand it, `use-composer-queue.ts:580-594`); after anything else (a refused prompt, a late refusal, a notice
         * since) it pauses, so Resume shows (DC-M6). Busy, an app command still [acting], paused, or nothing but the
         * edited item: this and null. [at] is the time a head that goes out is sent.
         */
        fun settled(at: Long): Pair<Live, String?> = when {
            ref == null || chat.busy || acting > 0 || queue.next() == null -> this to null
            chat.endedComplete -> draining(at)
            else -> paused() to null
        }

        /** The requests the core withdrew (their cards are gone from the transcript) let go too. */
        fun withdrawn(): Live = copy(waiting = waiting.filterKeys { id -> chat.prompts.any { it.id == id } })

        /**
         * A waiting [resend], once its turn has ended: a prompt after a turn that completed, else (stopped, failed)
         * [CORRECTION_NOT_SENT]; either way it waits no longer (D6, inv. 1).
         */
        fun resettled(): Live {
            val id = resend ?: return this
            if (chat.busy) return this
            return copy(chat = if (chat.endedComplete) chat.prompted(id) else chat.notice(CORRECTION_NOT_SENT), resend = null)
        }
    }

    private val lock = Any()
    private var catalog: Pair<RuntimeSessionId, SlashCatalog>? = null  // guarded by lock: one per session (D5)
    private val holds = AtomicLong()  // one TurnHost key per compression (improve #2, V8)
    private var live = Live()  // guarded by lock
    private val shown = MutableStateFlow(Chat())
    val state: StateFlow<Chat> = shown.asStateFlow()
    private val shownQueue = MutableStateFlow(QueueUi())
    /** The chat's queue of unsent messages, in its session; it lives beside the transcript, not in it (§2.4). */
    val queueState: StateFlow<QueueUi> = shownQueue.asStateFlow()
    /** The queues to write, newest per stored key (guarded by [lock]); [flush] writes them, one writer at a time. */
    private val unsaved = mutableMapOf<StoredSessionId, QueueUi>()
    private val saving = Mutex()
    /** Open/resume and the close of a left session, one at a time: a resume of the left session cannot race its close (D3). */
    private val switching = Mutex()
    /** The sessions this chat left and their closes (B3-D2/D3/D5; improve #5). */
    private val leftSessions = LeftSessions(scope, switching, stillHere = { synchronized(lock) { live.ref?.runtime } == it },
                                            close = { core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = it.value)) })

    /**
     * Replaces the live value by [next] of it, atomically; returns the value before and after. Pure and quick inside.
     * A queue it changed is written after, off the lock: one small write per queue change (§6). Every change ends
     * [Live.settled], so a head it leaves due goes out after it, with `queued: true`, decided once (EJ-78).
     */
    private inline fun change(next: (Live) -> Live): Pair<Live, Live> {
        var drained: String? = null
        val (before, after, toSave) = synchronized(lock) {
            val before = live
            live = next(before).settled(clock()).let { (settled, text) -> drained = text; settled }
            shown.value = live.chat
            shownQueue.value = live.queue
            Triple(before, live, recordQueue(before, live))
        }
        if (toSave) scope.launch { flush() }
        drained?.let { text -> after.ref?.runtime?.let { session -> scope.launch { deliver(session, text) } } }
        return before to after
    }

    /**
     * What [change] must write of the queue (under [lock]): in one session only, never on a switch, whose queue was just
     * loaded. A rotated stored key takes its queue along (D11); otherwise items or pause that changed. True: recorded.
     */
    private fun recordQueue(before: Live, after: Live): Boolean {
        val was = before.ref ?: return false
        val now = after.ref ?: return false
        if (was.runtime != now.runtime) return false
        if (was.stored != now.stored) {
            unsaved[was.stored] = QueueUi()
            unsaved[now.stored] = after.queue
            return true
        }
        if (before.queue.items == after.queue.items && before.queue.paused == after.queue.paused) return false
        unsaved[now.stored] = after.queue
        return true
    }

    /** Writes the queues [change] recorded, serially, the newest per key; a failed write loses only persistence. */
    private suspend fun flush() {
        saving.withLock {
            synchronized(lock) { unsaved.toMap().also { unsaved.clear() } }
                .forEach { (key, queue) -> orElse(Unit) { queues.save(key, queue) } }
        }
    }

    private fun say(notice: String) {
        change { it.copy(chat = it.chat.notice(notice)) }
    }

    /**
     * The agent scheduled a job in [session] (F11): when the gateway will not fire it, the chat says so once per
     * session, with the way to the Gateway page. The observation is read outside [lock] and only here (V4); one that
     * fails says nothing, since "cannot tell" is not "off". Decided in one change: never twice, never on a session
     * the chat has left meanwhile.
     */
    private fun noticeJobs(session: RuntimeSessionId) {
        if (synchronized(lock) { live.jobsNoticed }) return  // said already: no observation needed
        val seen = runCatching { gatewaySeen() }.getOrNull() ?: return
        if (firesJobs(seen)) return
        change { now ->
            if (now.ref?.runtime != session || now.jobsNoticed) now
            else now.copy(chat = now.chat.notice(JOBS_NEED_GATEWAY, NoticeShortcut.GATEWAY), jobsNoticed = true)
        }
    }

    /**
     * The cron store moved (`cron.changed`: a run that ends writes its job's `last_run_at`): a run the chat shows as
     * still owned is asked again, so the verdict never latches (V4). Only a refusing verdict is re-read; an answer
     * that no longer confirms it (false, null, a failed read) lets the chat write. Decided in one change, only on the
     * session it was asked for.
     */
    private fun recheckRun() {
        val ref = synchronized(lock) { live.ref?.takeIf { live.chat.runOwned } } ?: return
        scope.launch {
            if (refusesSend(orElse(null) { runOwned(ref.stored) })) return@launch
            change { now -> if (now.ref?.runtime != ref.runtime || !now.chat.runOwned) now else now.copy(chat = now.chat.copy(runOwned = false)) }
        }
    }

    /** The runtime id of the core session this chat sends to (the id its frames carry, never a drawer row's key); the chat must be open. */
    val sessionId: RuntimeSessionId
        get() = checkNotNull(openSessionId) { "open() the chat first" }

    /** The runtime id of the chat's core session, or null before the first `open()` (the model picker's read, D12). */
    val openSessionId: RuntimeSessionId? get() = synchronized(lock) { live.ref?.runtime }

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
     * Writes stored session [stored]'s file (a drawer row's key), or the chat's own when null (D7): the key its stored row
     * goes by now, which a compression rotates, so not while the chat's session compresses. Not refused while a turn
     * runs: the file holds the rows stored so far (D12). The export goes out off the lock.
     */
    suspend fun export(stored: StoredSessionId?): ExportFile {
        val key = stored ?: synchronized(lock) { live }.let { now ->
            val ref = now.ref ?: throw SetupRefusal(NOTHING_TO_EXPORT)
            if (now.chat.compressing) throw SetupRefusal(STILL_COMPRESSING)
            ref.stored
        }
        return exporter(key)
    }

    /**
     * The core's `/status` text for the chat's session (`session.status`), exactly as it wrote it (D10). It asks the
     * runtime session, the id `session.status` takes; a stored key would be refused (D9).
     */
    suspend fun status(): String {
        val session = synchronized(lock) { live.ref?.runtime } ?: throw SetupRefusal(NO_STATUS)
        return core.call(Methods.SESSION_STATUS, SessionStatusParams(sessionId = session.value)).output
    }

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
        synchronized(lock) { unsaved[stored] = QueueUi() }  // its queue goes with its row (§8, D11)
        flush()
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
        val chat = Chat(streaming = if (inTurn) resumed.inflight?.assistant.orEmpty() else null, model = resumed.info.model.ifBlank { null },
                        todos = resumed.todoState?.let { TodoList.of(it.todos, it.revision) }, meter = resumed.info.usage?.let(ContextMeter::of))
        val open = resumed.openRequests.orEmpty().map { InTurnPrompt(it.id, it.method, it.params) }
        // The live info's key first (the core's own); the reply's session_key next; the caller's id as the last resort.
        val key = (resumed.info.storedSessionId.ifBlank { null } ?: resumed.sessionKey ?: resumed.storedSessionId)?.let(::StoredSessionId) ?: stored
        // A job's run is asked once, as it opens, whether the gateway's scheduler still owns it (V2, V3); a failed read
        // cannot tell, which keeps today's rule (V6).
        val owned = if (isCronRun(key)) orElse(null) { runOwned(key) } else null
        // Its queue as last written (a write still pending lands first); paused unless its turn still runs (D6).
        flush()
        val queue = orElse(QueueUi()) { queues.load(key) }.resumed(inTurn)
        return switchTo(SessionRef(RuntimeSessionId(resumed.sessionId), key), chat.copy(runOwned = refusesSend(owned)),
                        resumed.info.desktopContract, open, transcript = resumed.messages, queue = queue)
    }

    /** Invariant 6: no switch while the core compresses the chat's session; [switching] is held, so none is admitted meanwhile. */
    private fun refuseWhileCompressing() {
        if (synchronized(lock) { live.chat.compressing }) throw SetupRefusal(STILL_COMPRESSING)
    }

    /**
     * Makes [ref] the chat's session, showing [chat] with the cards of the [open] requests it can show; the previous
     * session's turn and questions are let go, and the session itself is closed in the core if it was idle: the
     * returned value is that close's outcome. [transcript] is shown keyed on after every id the chat showed before (V5);
     * [queue] is the session's own (a new session's is empty), its ids never below one the chat gave (inv. 4). An offer
     * not yet taken follows the chat (D7). A billing wall does not follow: it was the left session's last turn (inv. 5).
     */
    private fun switchTo(ref: SessionRef, chat: Chat, reportedContract: JsonElement?, open: List<InTurnPrompt> = emptyList(),
                         transcript: List<TranscriptMessage> = emptyList(), queue: QueueUi = QueueUi()): Deferred<LeftSessions.Outcome>? {
        val notice = contractNotice(reportedContract)
        val (before, after) = change { now ->
            val shown = chat.copy(nextId = now.chat.nextId, offer = now.chat.offer).restored(transcript)
            val opened = open.mapNotNull { raw -> prompt(raw, ref.runtime)?.let { it to raw } }
            // The same session resumed: questions it asked after the core's snapshot are still its own.
            val kept = if (now.ref?.runtime == ref.runtime) now.chat.prompts.filter { p -> opened.none { it.first.id == p.id } }
                       .mapNotNull { p -> now.waiting[p.id]?.let { p to it } } else emptyList()
            val cards = opened + kept
            Live(ref, cards.fold(notice?.let(shown::notice) ?: shown) { shownChat, (p, _) -> shownChat.asked(p) },
                 cards.associate { (p, raw) -> p.id to raw }, queue = queue,
                 nextQueued = maxOf(now.nextQueued, (queue.items.maxOfOrNull { it.id } ?: -1) + 1))
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
        wanted?.let { turns.begin(it); readBusyMode(RuntimeSessionId(it)) }
        activeTurn = wanted
    }

    /**
     * The core's `display.busy_input_mode`, read once as [session]'s turn begins (D2): the composer's label and the
     * send both use what was read. A failed read leaves no mode, so Stop stands alone; one answered after the chat
     * left [session] is dropped.
     */
    private fun readBusyMode(session: RuntimeSessionId) {
        scope.launch {
            val mode = orElse(null) { BusyMode.of(core.call(Methods.CONFIG_GET, ConfigGetParams(key = "busy")).value) }
            change { now -> if (now.ref?.runtime == session) now.copy(chat = now.chat.copy(busyMode = mode)) else now }
        }
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
     * Words another app shared land in the chat as its offer, a newer one in place of one not taken. "An offer
     * (`IncomingText`) lands in the chat as a draft; the composer consumes it once; it is never sent by `ChatSession`"
     * (inv. 6, bet 8): admitted with or without a session, busy or not, because it sends nothing.
     */
    fun offer(text: IncomingText) {
        change { now -> now.copy(chat = now.chat.copy(offer = text)) }
    }

    /** The composer took [taken] as its draft: the offer leaves, unless a newer one has replaced it meanwhile (D3, EJ-78). */
    fun offerTaken(taken: IncomingText) {
        change { now -> if (now.chat.offer == taken) now.copy(chat = now.chat.copy(offer = null)) else now }
    }

    /** The composer took [taken], the core's prefill, as its draft: it leaves, unless a newer one replaced it (as [offerTaken]). */
    fun prefillTaken(taken: String) {
        change { now -> if (now.chat.prefill == taken) now.copy(chat = now.chat.copy(prefill = null)) else now }
    }

    /**
     * The slash menu for [draft] (chat-20, §3.3): none unless it [opensMenu] in an open chat. The bare `/` reads the
     * core's catalog afresh (D5: how a skills change shows); after it, the cached catalog narrowed by the core's
     * `complete.slash`, or by name when that fails. A catalog that cannot be read is thrown: no menu.
     */
    suspend fun slashItems(draft: String): List<SlashItem> {
        if (!opensMenu(draft)) return emptyList()
        val session = openSessionId ?: return emptyList()
        if (draft == "/") return catalogOf(session, fresh = true).browse()
        val catalog = catalogOf(session)
        val completed = orElse(null) { core.call(Methods.COMPLETE_SLASH, CompleteSlashParams(text = draft, sessionId = session.value)) }
        return completed?.let { catalog.narrowed(draft, it) } ?: catalog.prefixed(draft)
    }

    /** [session]'s slash catalog: the one kept for it unless [fresh], else the core's, kept for it. Throws the core's failure. */
    private suspend fun catalogOf(session: RuntimeSessionId, fresh: Boolean = false): SlashCatalog {
        if (!fresh) synchronized(lock) { catalog?.takeIf { it.first == session }?.second }?.let { return it }
        val read = SlashCatalog.of(core.call(Methods.COMMANDS_CATALOG, CommandsCatalogParams(sessionId = session.value)))
        synchronized(lock) { catalog = session to read }
        return read
    }

    /** The user dismissed the billing wall: it leaves the chat, and nothing is sent (inv. 5). */
    fun dismissWall() {
        change { now -> now.copy(chat = now.chat.withoutWall()) }
    }

    /**
     * Sends [text] to the chat's session; refused while the session compresses. While a turn runs, the core's busy
     * mode says how ([Chat.busySend]): the words redirect or steer the turn, which never hard-interrupts it (inv. 1);
     * in queue mode they go into the chat's queue; nothing goes out before the turn started or while a question waits.
     * A `/` line never reaches the model as typed (invariant 4, chat-20): one the core's catalog names runs through
     * the app's own controls (`/new`, `/model`, `/compress`, `/retry`, `/steer`, `/queue`, at once, busy or not, under
     * their own rules: D3) or through `command.dispatch`; one only `slash.exec` runs is refused ([NOT_ON_PHONE]); an
     * unreadable catalog sends nothing ([SLASH_UNREAD]); only a `/` the catalog does not know (a path) is text. A
     * dispatched line typed while a turn runs waits in the queue as typed and is dispatched at the drain (invariant 5).
     */
    suspend fun send(text: String) {
        if (text.startsWith("/")) {
            val at = checkNotNull(openSessionId) { "open() the chat first" }
            val catalog = try {
                catalogOf(at)
            } catch (failed: Exception) {
                return say("$SLASH_UNREAD: ${forUser(failed)}")  // a cancellation goes on
            }
            catalog.line(text)?.let { return runSlash(at, Typed(it, text)) }
            catalog.unavailable(text)?.let { return say(NOT_ON_PHONE.format(it)) }
        }
        var session: RuntimeSessionId? = null
        var correction: Pair<Long, BusyMode>? = null  // the bubble's id and the mode it goes out by
        change { now ->  // the session and the bubble decided in one change (EJ-78)
            val ref = checkNotNull(now.ref) { "open() the chat first" }
            if (now.chat.compressing) throw SetupRefusal(STILL_COMPRESSING)  // invariant 6 (V4)
            val busySend = now.chat.busySend
            when {
                now.chat.runOwned -> now.copy(chat = now.chat.notice(RUN_STILL_OWNED))  // viewing only: nothing goes out (V5)
                busySend != null -> {
                    session = ref.runtime; correction = now.chat.nextId to busySend
                    now.copy(chat = now.chat.correcting(text, busySend, clock()))  // the user's words at once (inv. 2)
                }
                now.chat.running && now.chat.busyMode == BusyMode.QUEUE && now.chat.queueable -> now.queued(text)  // the app's queue (M2's D3)
                now.chat.busy -> now
                else -> { session = ref.runtime; now.copy(chat = now.chat.sent(text, clock())) }
            }
        }
        val to = session ?: return  // V5: a second tap before the turn started
        correction?.let { (id, mode) -> return correct(to, id, text, mode) }
        submit(to, text)
    }

    /**
     * A slash line on its way: the [line] it runs, the [text] the user typed, whether it came from the [queued] drain,
     * whether it is already an alias's target ([aliased]: one hop only, D2), and the [cut] an edit makes when its
     * dispatch sends (Q3).
     */
    private data class Typed(val line: SlashLine, val text: String, val queued: Boolean = false, val aliased: Boolean = false,
                             val cut: Cut? = null)

    /** An edited command's place: the [rewind] it came from and the row the core cuts before ([rowId]; null: no cut, B5-D1). */
    private data class Cut(val rewind: Rewind, val rowId: Long?)

    /**
     * [typed] in [session]: the app's own run at once; a dispatched one is decided in one change: refused while
     * compressing, said in a job's run, queued as typed while busy (invariant 5, DC-P13: never a correction, whatever
     * the busy mode), else the chat busy from here until its turn starts or the dispatch ends without one (V5).
     */
    private suspend fun runSlash(session: RuntimeSessionId, typed: Typed) {
        if (typed.line.runs == Runner.APP) return runApp(typed.line)
        var go = false
        change { now ->
            if (now.ref?.runtime != session) return@change now
            if (now.chat.compressing) throw SetupRefusal(STILL_COMPRESSING)  // invariant 6, as send
            when {
                now.chat.runOwned -> now.copy(chat = now.chat.notice(RUN_STILL_OWNED))
                now.chat.busy -> if (now.chat.queueable) now.queued(typed.text) else now
                else -> { go = true; now.copy(chat = now.chat.copy(submitted = true)) }
            }
        }
        if (go) dispatched(session, typed)
    }

    /**
     * `command.dispatch` of [typed] in [session], and the core's directive acted on: a send's message to the model with
     * the bubble showing its display, else the typed line, never the message (D6); a prefill into the composer over the
     * core's transcript, re-read (D7); words as a notice; an alias's target run once (D2). A failure says why in the
     * core's words, after the command's name. An edit's [Typed.cut] is made only by a send, in the change that paints
     * its bubble, and rides on its `prompt.submit` as a plain edit's does; every other outcome cuts nothing (Q3).
     * Answers what became of the line for an edit's [resend] (Q7): taken once the command acted (a send, a prefill,
     * words said, an app target run), refused with the words said, or left; every other caller ignores it.
     */
    private suspend fun dispatched(session: RuntimeSessionId, typed: Typed): Resend {
        val line = typed.line
        val answer = try {
            core.call(Methods.COMMAND_DISPATCH, CommandDispatchParams(name = line.name, arg = line.arg.ifBlank { null }, sessionId = session.value))
        } catch (failed: Exception) {
            return refusedUnsent(session, "/${line.name}: ${forUser(failed)}")  // a cancellation goes on
        }
        return when (val directive = Dispatch.of(answer)) {
            is Dispatch.Send -> {
                if (directive.message.isBlank()) return refusedUnsent(session, "/${line.name}: $EMPTY_MESSAGE")
                val cut = typed.cut
                var shownBefore: List<Entry>? = null
                var moved = false
                change { now ->
                    val again = cut?.let { now.chat.rewindTarget(it.rewind) }
                    moved = false
                    when {
                        now.ref?.runtime != session -> now
                        cut != null && again == null -> { moved = true; now.copy(chat = now.chat.unsent()) }  // the prompt moved: nothing cut
                        else -> {
                            shownBefore = now.chat.entries
                            val base = if (again != null && cut?.rowId != null) now.chat.rewound(again) else now.chat
                            val told = directive.notice?.let(base::notice) ?: base
                            now.copy(chat = told.sent(directive.display ?: typed.text, clock()))
                        }
                    }
                }
                val before = shownBefore ?: return if (moved) Resend.Refused(EDIT_FAILED) else Resend.Left
                if (cut == null) {
                    submit(session, directive.message, typed.queued)  // the model gets the message, never shown
                    return Resend.Taken
                }
                try {
                    submitPastBusy(session, rewindParams(session, directive.message, cut.rowId, cut.rewind.deep)).userRowId?.let { bind(session, it) }
                    Resend.Taken
                } catch (failed: Exception) {
                    if (failed is CancellationException) throw failed
                    val words = refused(cut.rewind, failed)
                    resync(session, before, words)
                    refusal(session, words)
                }
            }
            is Dispatch.Prefill -> {
                val messages = orElse(null) { core.call(Methods.SESSION_HISTORY, SessionHistoryParams(sessionId = session.value)).messages }
                change { now ->
                    if (now.ref?.runtime != session) now
                    else {
                        val kept = (messages?.let(now.chat::restored) ?: now.chat).unsent()
                        now.copy(chat = (directive.notice?.let(kept::notice) ?: kept).copy(prefill = directive.text.ifBlank { null }))
                    }
                }
                Resend.Taken  // the command acted: its words went to the draft
            }
            is Dispatch.Said -> { unsent(session, directive.words); Resend.Taken }  // the command ran and said its words
            is Dispatch.Alias -> {
                val target = orElse(null) { catalogOf(session).line("/${directive.target} ${line.arg}".trim()) }
                when {
                    target == null || typed.aliased -> refusedUnsent(session, NOT_ON_PHONE.format("/${directive.target}"))
                    target.runs == Runner.APP -> { actFor(session, target); Resend.Taken }
                    else -> dispatched(session, typed.copy(line = target, aliased = true))
                }
            }
        }
    }

    /** [session]'s dispatched line did not go out ([unsent], [why] said), and the edit it may carry is refused with [why]. */
    private fun refusedUnsent(session: RuntimeSessionId, why: String): Resend {
        unsent(session, why)
        return refusal(session, why)
    }

    /**
     * A queue head the drain let go (invariant 5): plain words as ever; a `/` line as Send runs it, its bubble painted
     * only once the catalog says what it is (D9). An unreadable catalog sends nothing, and the queue pauses.
     */
    private suspend fun deliver(session: RuntimeSessionId, text: String) {
        if (!text.startsWith("/")) return submit(session, text, queued = true)
        val catalog = try {
            catalogOf(session)
        } catch (failed: Exception) {
            return unsent(session, "$SLASH_UNREAD: ${forUser(failed)}")
        }
        val line = catalog.line(text)
        when {
            line?.runs == Runner.DISPATCH -> dispatched(session, Typed(line, text, queued = true))
            line != null -> actFor(session, line)  // a line the Queue button queued
            else -> catalog.unavailable(text)?.let { unsent(session, NOT_ON_PHONE.format(it)) } ?: run {
                change { now -> if (now.ref?.runtime == session) now.copy(chat = now.chat.sent(text, clock())) else now }
                submit(session, text, queued = true)  // text after all: a path
            }
        }
    }

    /**
     * The app's own command [line], let go in [session] by a slash line held busy (a queue head, an alias's target):
     * the chat is idle again, nothing went out, and the queue waits ([Live.acting]) until the command has acted, so the
     * next head never leaves before it (a `/retry` head then `more`: `more` goes after the retried turn). Then
     * [Live.settled] decides as after any change: a completed turn last lets the next head go; a notice since pauses it.
     */
    private suspend fun actFor(session: RuntimeSessionId, line: SlashLine) {
        var held = false
        change { now ->
            if (now.ref?.runtime != session) now
            else { held = true; now.copy(chat = now.chat.unsent(), acting = now.acting + 1) }
        }
        try {
            runApp(line)
        } finally {  // acted, refused, failed or cancelled: the queue goes on under its own rules
            if (held) change { now -> if (now.ref?.runtime == session && now.acting > 0) now.copy(acting = now.acting - 1) else now }
        }
    }

    /**
     * The app's own command [line], through the app's own controls, at once, busy or not (D3): each control keeps its
     * rules and says its refusals; every failure but a cancellation is said.
     */
    private suspend fun runApp(line: SlashLine) {
        try {
            when (line.name) {
                "new" -> open()
                "model" -> openModels(line.arg)
                "compress" -> say(compress(line.arg.ifBlank { null }))
                "retry" -> retryLine()
                "steer" -> steerLine(line.arg)
                "queue" -> when {
                    line.arg.isBlank() -> say(QUEUE_USAGE)
                    synchronized(lock) { live.chat.queueable } -> queue(line.arg)
                    else -> send(line.arg)  // the core's `_cmd_queue` sends it when nothing runs
                }
            }
        } catch (failed: Exception) {
            say(forUser(failed))  // a cancellation goes on
        }
    }

    /** `/retry`: the last prompt again ([retry]), or [NOTHING_TO_RETRY]. */
    private suspend fun retryLine() {
        val (session, target) = synchronized(lock) { live.ref?.runtime to live.chat.retryTarget() }
        if (session == null || target == null) return say(NOTHING_TO_RETRY)
        retry()
    }

    /**
     * `/steer [words]`: steers a turn a correction can land on (running, no question waiting, not compressing, not a
     * job's run: [Chat.steerMode]'s conditions); busy otherwise, the next turn's message ([queue]; the core's
     * `methods_tools.py:886`); idle, a plain message (the core's "No agent running; sent as next turn", `:879-880`).
     */
    private suspend fun steerLine(words: String) {
        if (words.isBlank()) return say(STEER_USAGE)
        var steer: Pair<RuntimeSessionId, Long>? = null
        var busy = false
        change { now ->
            val ref = now.ref ?: return@change now
            val c = now.chat
            when {
                c.running && c.prompts.isEmpty() && !c.compressing && !c.runOwned -> {
                    steer = ref.runtime to c.nextId
                    now.copy(chat = c.correcting(words, BusyMode.STEER, clock()))
                }
                else -> { busy = c.busy; now }
            }
        }
        steer?.let { (session, id) -> return correct(session, id, words, BusyMode.STEER) }
        if (busy) queue(words) else send(words)
    }

    /**
     * `prompt.submit` of [text] to [session], whose bubble is on its way; a refusal or a failure makes the chat idle again (V7).
     * A [queued] item goes with `queued: true`, so the core holds it for the next turn and never makes it a correction
     * (§0 coherence 1); one that does not go out leaves the chat idle after no completed turn, so [Live.settled] pauses
     * the rest of the queue, with no retry (D8). A `queued` answer to one (the core's turn was still ending) is the core
     * holding it for its own next turn: logged, no failure; its row binds when that turn completes.
     */
    private suspend fun submit(session: RuntimeSessionId, text: String, queued: Boolean = false) {
        try {
            val result = core.call(Methods.PROMPT_SUBMIT, PromptSubmitParams(sessionId = session.value, text = JsonPrimitive(text),
                                                                             queued = true.takeIf { queued }))
            result.userRowId?.let { bind(session, it) }
            // A drained item that met the core's turn still ending (message.complete before running = False,
            // prompt_turn.py:1193/1210) waits in the core's own queue, which runs it next: no row id yet (§3, V4).
            if (queued && result.status == PromptSubmitStatus.QUEUED)
                log("prompt.submit queued:true in ${session.value} answered queued: the core runs it after its own turn, no user_row_id")
        } catch (refused: CoreError) {
            unsent(session, refused.message ?: "The core refused the prompt (${refused.code}).")
        } catch (failed: Exception) {  // anything else: the message did not go out, and the user hears it
            unsent(session, forUser(failed))
        }
    }

    /**
     * The correction [id] ([text]) to [session]'s running turn by [mode]: `session.steer` or `session.redirect`, the
     * core's answer under its bubble. A refusal or a failure keeps the bubble and says why (D4): the turn runs on.
     * `rejected` (the turn ended meanwhile) is an answer, not an error (EJ-69): the words go out once as a prompt
     * after a turn that completed, now or at its end (D6); after one stopped or failed, the chat says they were not sent.
     */
    private suspend fun correct(session: RuntimeSessionId, id: Long, text: String, mode: BusyMode) {
        val method = when (mode) {
            BusyMode.STEER -> Methods.SESSION_STEER
            BusyMode.INTERRUPT -> Methods.SESSION_REDIRECT
            BusyMode.QUEUE -> error("queue mode never corrects")  // Chat.busySend leaves it out (D3)
        }
        val answer = try {
            core.call(method, SessionCorrectionParams(sessionId = session.value, text = text))
        } catch (refused: CoreError) {  // 4010: the agent cannot take corrections; 4001, 5000
            if (refused.code == 4010) return requeued(session, id, text)
            say(refused.message ?: "Hermes refused the correction (${refused.code}).")
            return
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            say(forUser(failed))
            return
        }
        when (val outcome = Correction.of(mode, answer.status)) {
            Correction.REDIRECTED, Correction.STEERED, Correction.QUEUED_BY_CORE ->
                change { now -> if (now.ref?.runtime == session) now.copy(chat = now.chat.corrected(id, outcome)) else now }
            Correction.REFUSED -> {
                var resend = false
                change { now ->  // decided with the turn's state in one change, so the words go out once (inv. 1)
                    when {
                        now.ref?.runtime != session -> now  // that transcript is gone
                        now.chat.busy -> now.copy(resend = id)  // the answer outran the turn's end: it decides (D6)
                        now.chat.endedComplete -> { resend = true; now.copy(chat = now.chat.prompted(id)) }
                        else -> now.copy(chat = now.chat.notice(CORRECTION_NOT_SENT))
                    }
                }
                if (resend) submit(session, text)
            }
        }
    }

    /**
     * The correction [id] ([text]) the agent cannot take (4010) leaves the transcript for the queue's head, so it goes
     * next and its words are in one place (DC-M4, D10); a chat that left [session] changes nothing.
     */
    private fun requeued(session: RuntimeSessionId, id: Long, text: String) {
        change { now ->
            if (now.ref?.runtime != session) now
            else now.copy(chat = now.chat.without(id).notice(CORRECTION_QUEUED)).queued(text)
                .let { it.copy(queue = it.queue.moved(now.nextQueued)) }
        }
    }

    /** [session]'s prompt did not go out: idle again if the chat is still there (its queue then pauses: [Live.settled]); [words] said either way (V7). */
    private fun unsent(session: RuntimeSessionId, words: String) {
        change { now ->
            if (now.ref?.runtime != session) now.copy(chat = now.chat.notice(words))
            else now.copy(chat = now.chat.unsent().notice(words))
        }
    }

    /** The core named the row of [session]'s newest prompt; only while the chat is still on that session. */
    private fun bind(session: RuntimeSessionId, rowId: Long) {
        change { if (it.ref?.runtime == session) it.copy(chat = it.chat.boundUser(rowId)) else it }
    }

    /**
     * The user's reaction [emoji] on the answer [stableId] (chat-14), or null to clear it; the same emoji again retracts
     * it, as the core applies a tap. Invariant 3: it is painted in the [change] that decides to send it and rolled back,
     * with [REACT_FAILED] and why (D9), in the [change] that sees the call fail, only while the answer still shows that
     * paint (EJ-78); the core's answer otherwise replaces it, the user's own only (D51). Only an answer whose row the
     * core named (D2: never a guess at the newest row), outside a job's run the gateway still owns (D10): anything else sends nothing
     * and says nothing. A chat that left the session changes nothing on the answer: the core stored it, the next resume
     * shows it. The author is always the user: the agent's reactions need a core change (D51).
     */
    suspend fun react(stableId: Long, emoji: String?) {
        var sent: Tap? = null
        change { now ->
            val ref = now.ref ?: return@change now
            val rowId = now.chat.reactable(stableId) ?: return@change now
            val before = (now.chat.entries.first { it.stableId == stableId } as Entry.Reply).reactions
            val painted = before.tapped(emoji)
            sent = Tap(ref.runtime, rowId, before, painted)
            now.copy(chat = now.chat.reacted(stableId, painted))
        }
        val tap = sent ?: return
        val answer = try {
            core.call(Methods.MESSAGE_REACT, MessageReactParams(sessionId = tap.session.value, rowId = tap.rowId, emoji = emoji,
                                                                author = ReactionAuthor.USER))
        } catch (failed: Exception) {
            val why = forUser(failed)  // a cancellation goes on
            change { now ->
                if (now.ref?.runtime != tap.session) now
                else now.copy(chat = now.chat.unreacted(stableId, tap.painted, tap.before).notice("$REACT_FAILED: $why"))
            }
            return
        }
        change { now -> if (now.ref?.runtime == tap.session) now.copy(chat = now.chat.reacted(stableId, reactionsOf(answer.reactions))) else now }
    }

    /** One tap on its way: the session and row it went to, the reactions shown before it, and the ones it painted. */
    private data class Tap(val session: RuntimeSessionId, val rowId: Long, val before: List<Reaction>, val painted: List<Reaction>)

    /**
     * Runs the last prompt again, as the desktop's Retry does: [resend] of its own words, never a deep cut (B5, DC-P7).
     * The one retry `/retry`, the Retry button and the recovery card share; a command's turn is dispatched again by
     * [resend]'s rule. Its outcome is not read: a refusal says itself in the transcript, as B5's did.
     */
    suspend fun retry() {
        val (session, target) = synchronized(lock) { live.ref?.runtime to live.chat.retryTarget() }
        if (session == null || target == null) return
        resend(Rewind(target, target.text, deep = false))
    }

    /**
     * Sends [rewind]'s words again from its prompt's place, the one path retry and edit share (invariant 1, bet 7): the
     * core cuts its transcript before that prompt's row (`truncate_before_row_id`) and answers the words anew. A prompt
     * whose row the chat never learned is looked up in the core's history by its exact words; with no single match the
     * words go after the old turn, nothing cut (B5-D1). A cut that archives later turns needs [Rewind.deep], the user's
     * "Restore & rerun" (inv. 1). An edit stops a busy chat first and waits for that turn's end, and one the core finds
     * still busy is stopped once more and sent again (inv. 2, D3, D4); a retry keeps B5's rules (D5). A refusal shows
     * the core's transcript, re-read, with the core's words (an edit's after "Edit failed"): never the one the app predicted.
     *
     * Words that go in the prompt's place as a command `command.dispatch` runs (`/plan …`, `/goal …`: the bubble shows
     * the typed line, D6) never reach the model as typed (invariant 4), since the core re-expands only a skill
     * (`session_history.py:244-256`): a retry dispatches them again after the old turn, nothing cut (as B5-D1's unmatched
     * retry); an edit keeps its rules above and dispatches them, the cut riding on the `prompt.submit` of the message
     * (Q3). Plain words, a skill line or an unknown `/path` go as words. A `/` whose catalog cannot be read sends nothing
     * ([SLASH_UNREAD], D12; an edit's after "Edit failed").
     *
     * Answers what became of the words, so an editor keeps them until the core has them (Q7): [Resend.Taken] once a
     * prompt went out in the old one's place or the command they name acted; [Resend.Refused] with the words the
     * transcript says (or "Edit failed" alone where it says nothing: the prompt moved, a turn began, a deep cut has
     * no consent); [Resend.Left] when the chat is on another session by then. It never throws but a cancellation.
     */
    suspend fun resend(rewind: Rewind): Resend {
        val (session, shown) = synchronized(lock) { live.ref?.runtime to live.chat }
        if (session == null) return Resend.Left
        if (shown.rewindTarget(rewind) == null) return refusal(session, EDIT_FAILED)
        val command = if (!rewind.text.startsWith("/")) null else try {
            catalogOf(session).dispatchedCommand(rewind.text)
        } catch (failed: Exception) {
            val why = "$SLASH_UNREAD: ${forUser(failed)}"  // a cancellation goes on
            val words = if (rewind.edits) "$EDIT_FAILED: $why" else why
            say(words)
            return refusal(session, words)
        }
        if (command != null && !rewind.edits) {
            runSlash(session, Typed(command, rewind.text))
            return Resend.Taken  // a retry's: no caller reads it
        }
        if (rewind.edits && shown.busy && !stopped(session)) {
            change { now -> if (now.ref?.runtime == session) now.copy(chat = now.chat.notice("$EDIT_FAILED: $SESSION_BUSY")) else now }
            return refusal(session, "$EDIT_FAILED: $SESSION_BUSY")
        }
        val known = synchronized(lock) { live.chat.rewindTarget(rewind) } ?: return refusal(session, EDIT_FAILED)
        val rowId = known.rowId ?: rowIdOf(session, known.text)
        var shownBefore: List<Entry>? = null
        var left = false
        change { now ->
            val again = now.chat.rewindTarget(rewind)
            left = now.ref?.runtime != session
            // Decided in the one change that cuts (EJ-78, B5-D4): the chat left, a turn began, the prompt moved, or a
            // deep cut has no consent → nothing (inv. 1); which of them, from the same snapshot.
            if (left || again == null || now.chat.busy || (now.chat.laterTurns(again) > 0 && !rewind.deep)) now
            else {
                shownBefore = now.chat.entries
                // A command is cut only once its dispatch sends (Q3): busy meanwhile, as a typed one is (V5).
                if (command != null) now.copy(chat = now.chat.copy(submitted = true))
                else now.copy(chat = (if (rowId != null) now.chat.rewound(again) else now.chat).sent(rewind.text, clock()))
            }
        }
        val before = shownBefore ?: return if (left) Resend.Left else Resend.Refused(EDIT_FAILED)
        if (command != null) return dispatched(session, Typed(command, rewind.text, cut = Cut(rewind, rowId)))
        val params = rewindParams(session, rewind.text, rowId, rewind.deep)
        return try {
            val result = if (rewind.edits) submitPastBusy(session, params) else core.call(Methods.PROMPT_SUBMIT, params)
            result.userRowId?.let { bind(session, it) }
            Resend.Taken
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            val words = refused(rewind, failed)
            resync(session, before, words)
            refusal(session, words)
        }
    }

    /** A refusal of [session]'s rewind: [why], while the chat is still on that session; else it left (Q7). */
    private fun refusal(session: RuntimeSessionId, why: String): Resend =
        if (synchronized(lock) { live.ref?.runtime } == session) Resend.Refused(why) else Resend.Left

    /**
     * `prompt.submit` of [text] to [session] in a rewound prompt's place: cut before [rowId] when known (else after the
     * old turn, B5-D1). The core cuts only with consent; a first-turn cut empties the transcript, which needs its own; a
     * deep cut only after "Restore & rerun" ([deep], inv. 1).
     */
    private fun rewindParams(session: RuntimeSessionId, text: String, rowId: Long?, deep: Boolean) = PromptSubmitParams(
        sessionId = session.value, text = JsonPrimitive(text), truncateBeforeRowId = rowId,
        confirmTruncate = rowId?.let { true }, confirmEmptyTruncate = rowId?.let { true },
        confirmDeepTruncate = true.takeIf { deep && rowId != null })

    /**
     * Stops [session]'s turn for an edit, as Stop does: the queue pauses (b3: queued items stay queued), then
     * `session.interrupt` (a failure is ignored, as Desktop's: the wait decides). True once the chat has seen that turn
     * end, within [STOP_WAIT] (D3).
     */
    private suspend fun stopped(session: RuntimeSessionId): Boolean {
        change { now -> if (now.ref?.runtime == session && now.chat.busy) now.paused() else now }
        orElse(Unit) { core.call(Methods.SESSION_INTERRUPT, SessionInterruptParams(sessionId = session.value)); Unit }
        return withTimeoutOrNull(STOP_WAIT) { state.first { !it.busy } } != null
    }

    /**
     * An edit's `prompt.submit`. 4009 (the stopped turn still ending in the core, `methods_prompt.py:815-822`) stops it
     * once more and sends again, [BUSY_RESEND_GAP] apart, at most [BUSY_RESENDS] times; the last refusal is the answer
     * (inv. 2, D4).
     */
    private suspend fun submitPastBusy(session: RuntimeSessionId, params: PromptSubmitParams): PromptSubmitResult {
        try {
            return core.call(Methods.PROMPT_SUBMIT, params)
        } catch (busy: CoreError) {
            if (busy.code != 4009) throw busy
        }
        orElse(Unit) { core.call(Methods.SESSION_INTERRUPT, SessionInterruptParams(sessionId = session.value)); Unit }
        repeat(BUSY_RESENDS) { attempt ->
            if (attempt > 0) delay(BUSY_RESEND_GAP)
            try {
                return core.call(Methods.PROMPT_SUBMIT, params)
            } catch (busy: CoreError) {
                if (busy.code != 4009 || attempt == BUSY_RESENDS - 1) throw busy
            }
        }
        error("unreachable: the last resend returns or throws")
    }

    /** What a refused [rewind] says: a retry the core's words, as B5 said them; an edit "Edit failed" and why, 4018 in Desktop's words (D8). */
    private fun refused(rewind: Rewind, failed: Exception): String {
        val why = when {
            failed !is CoreError -> forUser(failed)
            rewind.edits && failed.code == 4018 -> EDIT_UNAVAILABLE
            else -> failed.message ?: "The core refused the ${if (rewind.edits) "edit" else "retry"} (${failed.code})."
        }
        return if (rewind.edits) "$EDIT_FAILED: $why" else why
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
        val messages = try {
            core.call(Methods.SESSION_HISTORY, SessionHistoryParams(sessionId = session.value)).messages
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            null
        }
        change { now ->
            when {
                now.ref?.runtime != session -> now
                now.chat.running -> now.copy(chat = now.chat.notice(words))  // a turn began after all: its events are the truth
                else -> now.copy(chat = (messages?.let(now.chat::restored) ?: now.chat.copy(entries = before)).unsent().notice(words))
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
     * Nothing runs, nothing is asked; the queue pauses first, so nothing queued runs after a Stop (DC-M6).
     */
    suspend fun cancel() {
        // The session seen busy, read with its state (EJ-78): Stop before the turn started cancels it too (V6).
        var seen: RuntimeSessionId? = null
        change { now -> now.ref?.runtime?.takeIf { now.chat.busy }?.let { seen = it; now.paused() } ?: now }
        val session = seen ?: return
        try {
            core.call(Methods.SESSION_INTERRUPT, SessionInterruptParams(sessionId = session.value))
        } catch (failed: Exception) {
            say(forUser(failed))
        }
    }

    /**
     * Puts [text] at the tail of the chat's queue while a turn runs or is about to ([Chat.queueable]); nothing goes to
     * the core until a turn ends (§0 coherence 1). A blank text, an idle chat or one with no session queues nothing.
     */
    suspend fun queue(text: String) {
        val words = text.trim().ifEmpty { return }
        change { now -> if (now.ref != null && now.chat.queueable) now.queued(words) else now }
    }

    /** Queued item [id] is being edited, so it waits; null, or an item no longer listed, ends the edit (D4). */
    fun beginEdit(id: Long?) {
        change { now -> now.copy(queue = now.queue.copy(editing = id?.takeIf { i -> now.queue.items.any { it.id == i } })) }
    }

    /** Queued item [id] takes the words [text] and its edit ends; a blank text, or an item already sent, changes nothing (inv. 4). */
    fun editQueued(id: Long, text: String) {
        val words = text.trim().ifEmpty { return }
        change { now -> now.copy(queue = now.queue.edited(id, words)) }
    }

    /** Queued item [id] leaves the queue unsent; one already sent changes nothing (inv. 4). */
    fun deleteQueued(id: Long) {
        change { now -> now.copy(queue = now.queue.removed(id)) }
    }

    /**
     * Queued item [id] goes next, and a pause lifts: while a turn runs it moves to the front, never interrupting (inv. 1,
     * D2); while nothing runs it goes out now. One already sent, or the one being edited, changes nothing (inv. 4).
     */
    fun sendNext(id: Long) {
        drainAfter { queue -> queue.takeIf { id != it.editing && it.items.any { item -> item.id == id } }?.moved(id)?.copy(paused = false) }
    }

    /** Resume after a pause: the head goes out now when nothing runs, else at the next finished turn (`index.tsx:1323-1331`). */
    fun resumeQueue() {
        drainAfter { it.copy(paused = false) }
    }

    /**
     * One change of the queue by [step] (null: none), then its head out at once if nothing runs or is about to, with
     * `queued: true`; decided in the same change, so it is never both sent and still listed (EJ-78).
     */
    private fun drainAfter(step: (QueueUi) -> QueueUi?) {
        var drained: Pair<RuntimeSessionId, String>? = null
        change { now ->
            val ref = now.ref ?: return@change now
            val queue = step(now.queue) ?: return@change now
            now.copy(queue = queue).draining(clock()).let { (next, text) -> text?.let { drained = ref.runtime to it }; next }
        }
        drained?.let { (session, text) -> scope.launch { deliver(session, text) } }
    }

    /**
     * Queued item [id] corrects the running turn now, as a busy Send does ([Chat.steerMode]: steered in the core's steer
     * mode, else redirected, D3): it leaves the queue and becomes the correction's bubble. Only where a correction can
     * land, and not the item being edited; otherwise nothing (D9: a `rejected` answer follows M2's rule). A `/` line
     * the catalog knows is a command, never words to steer with: it stays in the queue and the chat says so
     * ([NOT_STEERED]; invariant 4); one it cannot read sends nothing ([SLASH_UNREAD], D12); a `/` it does not know is words.
     */
    suspend fun steerNow(id: Long) {
        val (at, text) = synchronized(lock) { live.ref?.runtime to live.queue.items.firstOrNull { it.id == id }?.text }
        if (at != null && text != null && text.startsWith("/")) {
            val catalog = try {
                catalogOf(at)
            } catch (failed: Exception) {
                return say("$SLASH_UNREAD: ${forUser(failed)}")  // a cancellation goes on
            }
            (catalog.line(text)?.let { "/${it.name}" } ?: catalog.unavailable(text))?.let { return say(NOT_STEERED.format(it)) }
        }
        var steer: Triple<RuntimeSessionId, Long, Pair<String, BusyMode>>? = null  // the session, the bubble, the words and mode
        change { now ->
            val ref = now.ref
            val item = now.queue.items.firstOrNull { it.id == id }
            val mode = now.chat.steerMode
            // An item whose words changed since they were read (an edit saved meanwhile) is not the one checked: nothing.
            if (ref == null || item == null || item.text != text || now.queue.editing == id || mode == null) now
            else {
                steer = Triple(ref.runtime, now.chat.nextId, item.text to mode)
                now.copy(chat = now.chat.correcting(item.text, mode, clock()), queue = now.queue.removed(id).copy(paused = false))
            }
        }
        val (session, bubble, words) = steer ?: return
        correct(session, bubble, words.first, words.second)
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
        if (event.type == Events.CRON_CHANGED.name) return recheckRun()  // global: no session id (V4)
        val session = RuntimeSessionId(event.sessionId ?: return)
        if (event.type == Events.SESSION_INFO.name || event.type == Events.ERROR.name) leftSessions.settled(session)
        var reply: Entry.Reply? = null  // the reduced transcript's last entry, before a waiting correction settles after it
        var drained: String? = null  // the queue's head, sent as this turn ends
        val (before, after) = change { now ->
            if (now.ref?.runtime != session) now
            else now.copy(ref = now.ref.seen(event), chat = now.chat.reduce(event, clock)).withdrawn()
                .also { reply = it.chat.entries.lastOrNull() as? Entry.Reply }.resettled()
                .let { next ->
                    // The turn's end is busy going false, with or without a running edge (D1); decided here, once (inv. 3).
                    when {
                        !now.chat.busy || next.chat.busy -> next  // not this turn's end, or a resend took it (D6)
                        next.chat.endedComplete -> next.draining(clock()).also { drained = it.second }.first
                        else -> next.paused()  // stopped, failed, an error frame
                    }
                }
        }
        if (before === after) return
        // A correction the core turned away, now a prompt after the turn that completed: it goes out once (D6).
        val resent = before.resend?.takeIf { after.resend == null && after.chat.submitted }
        resent?.let { id -> (after.chat.entries.firstOrNull { it.stableId == id } as? Entry.User)?.text }
            ?.let { text -> scope.launch { submit(session, text) } }
        drained?.let { text -> scope.launch { deliver(session, text) } }
        if (before.chat.running != after.chat.running) syncTurn()
        if (before.chat.running && !after.chat.running) notifier.turnFinished(reply)
        // Withdrawn by the core: the turn no longer waits for them.
        if (after.waiting.size < before.waiting.size && after.chat.prompts.isEmpty()) notifier.promptsSettled()
        // After the reducer, never in it (FP): the reducer stays a function of (Chat, event).
        if (schedulesAJob(event)) noticeJobs(session)
        // After the reducer: the provider may not answer any more (F6, bet 10).
        if (failedOnProvider(event)) scope.launch { providerFailed() }
    }
}

/** The newest entry is the answer of a turn that completed: not stopped, not failed (D6). */
private val Chat.endedComplete: Boolean get() = (entries.lastOrNull() as? Entry.Reply)?.status == TurnStatus.COMPLETE

/** The layers of a failed turn after which the provider may not answer any more: its key, the provider, the account's credit (F6). */
private val RECHECKED_LAYERS = setOf("auth", "provider", "billing")

/** [event] is a completion whose `error_surface` (advisory: absent reads as no) names one of [RECHECKED_LAYERS]. */
internal fun failedOnProvider(event: CoreEvent): Boolean = runCatching {
    event.type == Events.MESSAGE_COMPLETE.name && event.decode(Events.MESSAGE_COMPLETE)?.errorSurface?.layer in RECHECKED_LAYERS
}.getOrDefault(false)
