package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.entry.IncomingText
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.MessageCompletePayload
import com.nousresearch.hermes.bridge.protocol.SessionCompressResult
import com.nousresearch.hermes.bridge.protocol.TranscriptMessage
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import com.nousresearch.hermes.bridge.protocol.Usage
import kotlinx.serialization.json.JsonPrimitive

/** One item of a chat's transcript. */
sealed interface Entry {
    /**
     * This row's own id, given once by the chat that adds it ([Chat.nextId]) and kept by every copy: never another
     * entry's in the same chat, so the transcript keys its row state by it, not by its place (/improve 20261113T221531Z #8).
     */
    val stableId: Long

    /**
     * The user's words; [rowId] is the core's durable row for them once the core names it (null: unproven, never "none").
     * [mode]: the busy mode they were sent by while a turn ran, a correction (null: a prompt); [correction]: the core's
     * answer to it once it came.
     */
    data class User(val text: String, val rowId: Long? = null, override val stableId: Long, val mode: BusyMode? = null,
                    val correction: Correction? = null) : Entry

    /** The agent's commentary between tool calls, sealed as its own segment while its turn runs on (not its answer). */
    data class Interim(val text: String, override val stableId: Long) : Entry

    /** The agent's answer; [reasoning] is the model's reasoning for it, when the core shows reasoning. */
    data class Reply(val text: String, val status: TurnStatus, val error: String? = null, val reasoning: String? = null,
                     override val stableId: Long) : Entry

    data class Tool(val id: String, val name: String, val summary: String? = null, val done: Boolean = false,
                    override val stableId: Long) : Entry

    /** Something the chat tells the user; [opens] is a place it offers to go (none: words only). */
    data class Notice(val text: String, val opens: NoticeShortcut? = null, override val stableId: Long) : Entry
}

/** A place a notice offers to open: the always-on gateway's page. */
enum class NoticeShortcut { GATEWAY }

/** The last prompt, to run again: its place in the transcript, its words, and the core's row id when known. */
data class Retry(val index: Int, val text: String, val rowId: Long?)

/**
 * A chat's transcript. While a turn runs, [streaming] is the reply written since the turn began or since its last
 * sealed commentary, [reasoning] the model's reasoning so far (when the core shows it), [activity] what the agent is
 * doing, and [prompts] what it is waiting on the user for. [model] is the model the session runs, as the core last named it.
 * [todos] is the agent's plan and [meter] how full the context is, both as the core last sent them.
 * [submitted]: a prompt is on its way and its turn has not started yet (`prompt.submit` sent, no `message.start`).
 * [compressing]: the core is compressing the session at this chat's request; only `ChatSession.compress` sets and
 * clears it (invariant 6).
 * [runOwned]: the session is a job's run the gateway's scheduler still owns: it is read here, never written; only
 * `ChatSession` sets and clears it (/architect #4).
 * [nextId]: the id the next entry takes. It only grows: a rewind keeps it, a transcript the core answers is keyed on
 * from it, and a session switch carries it, so no id is given twice (/improve 20261113T221531Z #8).
 * [busyMode]: the core's `display.busy_input_mode` as last read in this session, once per turn; null: not read yet, or
 * the read failed (batch-3 F1).
 * [offer]: words another app shared, waiting for the composer to take them as its draft; the chat never sends them
 * (inv. 6, bet 8).
 * [wall]: the out-of-credits wall the chat's last failed turn hit, as the core described it; set by a completion
 * carrying `billing`, gone with the next turn that completes, a session switch or Dismiss (inv. 5, DC-M12).
 */
data class Chat(val entries: List<Entry> = emptyList(), val streaming: String? = null, val activity: String? = null,
                val prompts: List<Prompt> = emptyList(), val reasoning: String? = null, val model: String? = null,
                val todos: TodoList? = null, val meter: ContextMeter? = null, val submitted: Boolean = false,
                val compressing: Boolean = false, val runOwned: Boolean = false,
                val nextId: Long = entries.maxOfOrNull { it.stableId + 1 } ?: 0, val busyMode: BusyMode? = null,
                val offer: IncomingText? = null, val wall: BillingWall? = null) {
    val running: Boolean get() = streaming != null

    /** A turn runs or is about to: from the prompt's send to the turn's end (GoF-State; improve #4). */
    val busy: Boolean get() = running || submitted

    /**
     * What Send does now while a turn runs: the core's mode, once the turn has started (D1), while it waits on no
     * question (D8), is not a job's run, does not compress, and the mode is not the app's queue (D3); null otherwise:
     * Stop alone. The one rule the send and the composer both read.
     */
    val busySend: BusyMode? get() =
        busyMode.takeIf { running && it != BusyMode.QUEUE && prompts.isEmpty() && !compressing && !runOwned }

    /**
     * A message can go into the chat's queue now: a turn runs or is about to, in a session the chat may write to and
     * that does not compress. The one rule the Queue button and the queue both read (D12).
     */
    val queueable: Boolean get() = busy && !runOwned && !compressing

    /**
     * How Steer now corrects the running turn with a queued message: steering in the core's steer mode, else
     * redirecting (queue mode included: "redirect the live turn now"); null where a correction cannot land, as for
     * [busySend]: before the turn started, while a question waits, in a job's run, while compressing, mode unread (D3).
     */
    val steerMode: BusyMode? get() =
        busyMode?.takeIf { running && prompts.isEmpty() && !compressing && !runOwned }
            ?.let { if (it == BusyMode.STEER) BusyMode.STEER else BusyMode.INTERRUPT }

    fun sent(text: String): Chat = adding { Entry.User(text, stableId = it) }.copy(submitted = true)

    /** The entry [id] leaves the transcript (a correction now waiting in the queue, D10); none leaves the chat as it is. */
    fun without(id: Long): Chat = if (entries.none { it.stableId == id }) this else copy(entries = entries.filterNot { it.stableId == id })

    /** A correction sent by [mode] while the turn runs: the user's words at once, nothing submitted (inv. 2). */
    fun correcting(text: String, mode: BusyMode): Chat = adding { Entry.User(text, stableId = it, mode = mode) }

    /** The correction [id] takes the core's [outcome]; no such entry leaves the chat as it is. */
    fun corrected(id: Long, outcome: Correction): Chat =
        copy(entries = entries.map { if (it is Entry.User && it.stableId == id) it.copy(correction = outcome) else it })

    /**
     * The correction [id], which the core turned away because its turn had ended, goes out as a prompt: the newest
     * entry, after the turn it missed, keeping its id, and on its way; no such entry leaves the chat as it is.
     */
    fun prompted(id: Long): Chat {
        val user = entries.firstOrNull { it.stableId == id } as? Entry.User ?: return this
        return copy(entries = entries.filterNot { it.stableId == id } + user.copy(mode = null, correction = null), submitted = true)
    }

    /** The prompt did not go out (refused, or never reached the core): the chat is idle again. */
    fun unsent(): Chat = copy(submitted = false)

    /** Dismissed: the wall leaves; nothing else changes (EJ-17). */
    fun withoutWall(): Chat = if (wall == null) this else copy(wall = null)

    fun notice(text: String, opens: NoticeShortcut? = null): Chat = adding { Entry.Notice(text, opens, it) }

    fun asked(prompt: Prompt): Chat = copy(prompts = prompts + prompt)

    fun settled(id: String): Chat = copy(prompts = prompts.filterNot { it.id == id })

    /**
     * The newest prompt takes the row id the core named for it; one already named, or an id shown elsewhere, stays (D2).
     * A correction is not a prompt: the turn's row is its prompt's.
     */
    fun boundUser(rowId: Long): Chat {
        val i = entries.indexOfLast(::isPrompt)
        val user = entries.getOrNull(i) as? Entry.User ?: return this
        if (user.rowId != null || entries.any { it is Entry.User && it.rowId == rowId }) return this
        return copy(entries = entries.toMutableList().apply { set(i, user.copy(rowId = rowId)) })
    }

    /** The last prompt, when nothing runs, is about to, or compresses, and something answered it (D3); null otherwise. */
    fun retryTarget(): Retry? {
        if (busy || compressing || runOwned) return null
        val i = entries.indexOfLast(::isPrompt)
        val user = entries.getOrNull(i) as? Entry.User ?: return null
        return Retry(i, user.text, user.rowId).takeIf { i < entries.lastIndex && user.text.isNotBlank() }
    }

    /** The transcript before [target]'s prompt: what the core keeps when it cuts before that row (its ids, and the counter, stay). */
    fun rewound(target: Retry): Chat = copy(entries = entries.take(target.index))

    /** After the core compressed the session: its transcript and its fill as the core answered (invariant 5); a reply without them changes neither. */
    fun compressed(result: SessionCompressResult): Chat =
        (result.messages?.let(::restored) ?: this).measured(result.usage)

    /** The core's [messages] in place of the transcript shown, keyed on from [nextId]: no row takes an id shown before (V4). */
    fun restored(messages: List<TranscriptMessage>): Chat =
        transcriptOf(messages, nextId).let { copy(entries = it, nextId = nextId + it.size) }

    /** The chat after one event of its session. A payload the app cannot read leaves it unchanged. */
    fun reduce(event: CoreEvent): Chat = runCatching { apply(event) }.getOrDefault(this)

    private fun apply(event: CoreEvent): Chat = when (event.type) {
        Events.MESSAGE_START.name -> copy(streaming = "", submitted = false)
        Events.MESSAGE_DELTA.name -> copy(streaming = streaming.orEmpty() + event.decode(Events.MESSAGE_DELTA)!!.text)
        // Commentary beside tool calls: sealed with the core's words, the buffer emptied (never null: the turn runs on).
        Events.MESSAGE_INTERIM.name -> event.decode(Events.MESSAGE_INTERIM)!!.text.trim()
            .takeIf { running && it.isNotEmpty() }?.let { text -> adding { id -> Entry.Interim(text, id) }.copy(streaming = "") } ?: this
        Events.REASONING_DELTA.name -> copy(reasoning = reasoning.orEmpty() + event.decode(Events.REASONING_DELTA)!!.text)
        // A preview of reasoning the core will not stream: it stands in until the completion's own.
        Events.REASONING_AVAILABLE.name -> if (reasoning.isNullOrEmpty()) copy(reasoning = event.decode(Events.REASONING_AVAILABLE)!!.text) else this
        Events.MESSAGE_COMPLETE.name -> complete(event.decode(Events.MESSAGE_COMPLETE)!!)
        Events.TOOL_START.name -> event.decode(Events.TOOL_START)!!.let { start -> adding { Entry.Tool(start.toolId, start.name, stableId = it) } }
        Events.TOOL_COMPLETE.name -> event.decode(Events.TOOL_COMPLETE)!!.let { done ->
            copy(entries = entries.map { if (it is Entry.Tool && it.id == done.toolId) it.copy(summary = done.summary, done = true) else it })
                .planned(done.revision?.let { revision -> done.todos?.let { TodoList.of(it, revision) } })  // an unversioned list is not taken (D3)
        }
        Events.TODO_UPDATED.name -> event.decode(Events.TODO_UPDATED)!!.let { planned(TodoList.of(it.todos, it.revision)) }
        Events.SESSION_USAGE.name -> measured(event.decode(Events.SESSION_USAGE)!!.usage)
        // A status outside a turn (the core's idle "ready" after a compress, a background process note) is not what the agent is doing.
        Events.STATUS_UPDATE.name -> if (running) copy(activity = event.decode(Events.STATUS_UPDATE)!!.text) else this
        Events.SESSION_INFO.name -> event.decode(Events.SESSION_INFO)!!.let { info ->
            (info.model.takeIf { it.isNotBlank() }?.let { copy(model = it) } ?: this).measured(info.usage)
        }
        // The core may end a turn with `error` alone (a refused @-reference, an ownership refusal, a crash), and the
        // TUI treats every `error` as the end of the turn: so does the chat, keeping the message as a notice.
        Events.ERROR.name -> notice(event.decode(Events.ERROR)!!.message).copy(streaming = null, activity = null, reasoning = null, submitted = false)
        Events.REQUEST_CANCEL.name -> settled(event.decode(Events.REQUEST_CANCEL)!!.id)
        Events.APPROVAL_CANCELLED.name -> event.decode(Events.APPROVAL_CANCELLED)!!.requestIds.toSet().let { ids ->
            copy(prompts = prompts.filterNot { it is Prompt.Approval && it.requestId in ids })
        }
        else -> this
    }

    private fun complete(done: MessageCompletePayload): Chat {
        // The completed text is authoritative; a structured (parts) completion keeps what was streamed.
        val text = (done.text as? JsonPrimitive)?.takeIf { it.isString }?.content ?: streaming.orEmpty()
        val thought = done.reasoning?.takeIf { it.isNotBlank() } ?: reasoning?.takeIf { it.isNotBlank() }
        val status = done.status ?: TurnStatus.COMPLETE
        // A wall describes the last turn: a newer one replaces it, and a turn that completes takes it away; a stopped
        // or otherwise failed turn leaves it (inv. 5, D5).
        val ended = adding { Entry.Reply(text, status, done.error, thought, it) }
            .copy(streaming = null, activity = null, reasoning = null, submitted = false,
                  wall = done.billing?.let { BillingWall.of(it) } ?: wall.takeUnless { status == TurnStatus.COMPLETE })
            .measured(done.usage)
        return done.persistedTurn?.userRowId?.let(ended::boundUser) ?: ended
    }

    /** [entry] is a prompt the user sent, not a correction sent while a turn ran. */
    private fun isPrompt(entry: Entry): Boolean = entry is Entry.User && entry.mode == null

    /** This chat with one more entry, made with the next id: the one way the reducer adds a row (/improve 20261113T221531Z #8). */
    private inline fun adding(entry: (id: Long) -> Entry): Chat = copy(entries = entries + entry(nextId), nextId = nextId + 1)

    /** The core's newer plan, when [next] is one (invariant 2); an unreadable or absent one changes nothing. */
    private fun planned(next: TodoList?): Chat = if (next == null) this else copy(todos = todos?.after(next) ?: next)

    /** The core's usage reading replaces the meter, none included (D5); no usage at all leaves it. */
    private fun measured(usage: Usage?): Chat = if (usage == null) this else copy(meter = ContextMeter.of(usage))
}
