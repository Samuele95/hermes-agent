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
import com.nousresearch.hermes.bridge.protocol.ConfigGetParams
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PromptSubmitParams
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
 */
class ChatSession(private val core: CoreGateway, private val turns: TurnHost, private val scope: CoroutineScope,
                  private val keepSecret: suspend (name: String, value: ByteArray) -> Unit,
                  private val notifier: Notifier = Notifier.None,
                  private val gatewaySeen: () -> GatewaySeen = { GatewaySeen(GatewayStatus.RUNNING) },
                  private val runOwned: suspend (StoredSessionId) -> Boolean? = { null },
                  private val queues: QueueStore = QueueStore.inMemory(),
                  private val providerFailed: suspend () -> Unit = {},
                  private val exporter: suspend (StoredSessionId) -> ExportFile = { throw SetupRefusal(NOTHING_TO_EXPORT) },
                  private val log: (String) -> Unit = {}, private val clock: () -> Long = System::currentTimeMillis) {
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
     */
    private data class Live(val ref: SessionRef? = null, val chat: Chat = Chat(), val waiting: Map<String, InTurnPrompt> = emptyMap(),
                            val jobsNoticed: Boolean = false, val resend: Long? = null, val queue: QueueUi = QueueUi(),
                            val nextQueued: Long = 0) {
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
            if (chat.busy || chat.compressing || chat.runOwned) return this to null
            val (item, rest) = queue.next() ?: return this to null
            return copy(chat = chat.sent(item.text, at), queue = rest) to item.text
        }

        /**
         * The queue as no change may leave it: never idle, unpaused and stranded with a head to send. After a turn that
         * completed the head goes out ([draining]; Desktop drains whenever idle and not parked, so a missed edge cannot
         * strand it, `use-composer-queue.ts:580-594`); after anything else (a refused prompt, a late refusal, a notice
         * since) it pauses, so Resume shows (DC-M6). Busy, paused, or nothing but the edited item: this and null.
         * [at] is the time a head that goes out is sent.
         */
        fun settled(at: Long): Pair<Live, String?> = when {
            ref == null || chat.busy || queue.next() == null -> this to null
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
        drained?.let { text -> after.ref?.runtime?.let { session -> scope.launch { submit(session, text, queued = true) } } }
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

    /** The user dismissed the billing wall: it leaves the chat, and nothing is sent (inv. 5). */
    fun dismissWall() {
        change { now -> now.copy(chat = now.chat.withoutWall()) }
    }

    /**
     * Sends [text] to the chat's session; refused while the session compresses. While a turn runs, the core's busy
     * mode says how ([Chat.busySend]): the words redirect or steer the turn, which never hard-interrupts it (inv. 1);
     * in queue mode they go into the chat's queue; nothing goes out before the turn started or while a question waits.
     */
    suspend fun send(text: String) {
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
                now.copy(chat = (if (rowId != null) now.chat.rewound(target) else now.chat).sent(target.text, clock()))
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
        drained?.let { (session, text) -> scope.launch { submit(session, text, queued = true) } }
    }

    /**
     * Queued item [id] corrects the running turn now, as a busy Send does ([Chat.steerMode]: steered in the core's steer
     * mode, else redirected, D3): it leaves the queue and becomes the correction's bubble. Only where a correction can
     * land, and not the item being edited; otherwise nothing (D9: a `rejected` answer follows M2's rule).
     */
    suspend fun steerNow(id: Long) {
        var steer: Triple<RuntimeSessionId, Long, Pair<String, BusyMode>>? = null  // the session, the bubble, the words and mode
        change { now ->
            val ref = now.ref
            val item = now.queue.items.firstOrNull { it.id == id }
            val mode = now.chat.steerMode
            if (ref == null || item == null || now.queue.editing == id || mode == null) now
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
        drained?.let { text -> scope.launch { submit(session, text, queued = true) } }
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
