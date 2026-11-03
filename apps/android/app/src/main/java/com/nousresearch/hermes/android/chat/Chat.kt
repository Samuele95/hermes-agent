package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.MessageCompletePayload
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import com.nousresearch.hermes.bridge.protocol.Usage
import kotlinx.serialization.json.JsonPrimitive

/** One item of a chat's transcript. */
sealed interface Entry {
    /** The user's words; [rowId] is the core's durable row for them once the core names it (null: unproven, never "none"). */
    data class User(val text: String, val rowId: Long? = null) : Entry

    /** The agent's commentary between tool calls, sealed as its own segment while its turn runs on (not its answer). */
    data class Interim(val text: String) : Entry

    /** The agent's answer; [reasoning] is the model's reasoning for it, when the core shows reasoning. */
    data class Reply(val text: String, val status: TurnStatus, val error: String? = null, val reasoning: String? = null) : Entry

    data class Tool(val id: String, val name: String, val summary: String? = null, val done: Boolean = false) : Entry

    data class Notice(val text: String) : Entry
}

/** The last prompt, to run again: its place in the transcript, its words, and the core's row id when known. */
data class Retry(val index: Int, val text: String, val rowId: Long?)

/**
 * A chat's transcript. While a turn runs, [streaming] is the reply written since the turn began or since its last
 * sealed commentary, [reasoning] the model's reasoning so far (when the core shows it), [activity] what the agent is
 * doing, and [prompts] what it is waiting on the user for. [model] is the model the session runs, as the core last named it.
 * [todos] is the agent's plan and [meter] how full the context is, both as the core last sent them.
 */
data class Chat(val entries: List<Entry> = emptyList(), val streaming: String? = null, val activity: String? = null,
                val prompts: List<Prompt> = emptyList(), val reasoning: String? = null, val model: String? = null,
                val todos: TodoList? = null, val meter: ContextMeter? = null) {
    val running: Boolean get() = streaming != null

    fun sent(text: String): Chat = copy(entries = entries + Entry.User(text))

    fun notice(text: String): Chat = copy(entries = entries + Entry.Notice(text))

    fun asked(prompt: Prompt): Chat = copy(prompts = prompts + prompt)

    fun settled(id: String): Chat = copy(prompts = prompts.filterNot { it.id == id })

    /** The newest prompt takes the row id the core named for it; one already named, or an id shown elsewhere, stays (D2). */
    fun boundUser(rowId: Long): Chat {
        val i = entries.indexOfLast { it is Entry.User }
        val user = entries.getOrNull(i) as? Entry.User ?: return this
        if (user.rowId != null || entries.any { it is Entry.User && it.rowId == rowId }) return this
        return copy(entries = entries.toMutableList().apply { set(i, user.copy(rowId = rowId)) })
    }

    /** The last prompt, when nothing runs and something answered it (D3); null otherwise. */
    fun retryTarget(): Retry? {
        if (running) return null
        val i = entries.indexOfLast { it is Entry.User }
        val user = entries.getOrNull(i) as? Entry.User ?: return null
        return Retry(i, user.text, user.rowId).takeIf { i < entries.lastIndex && user.text.isNotBlank() }
    }

    /** The transcript before [target]'s prompt: what the core keeps when it cuts before that row. */
    fun rewound(target: Retry): Chat = copy(entries = entries.take(target.index))

    /** The chat after one event of its session. A payload the app cannot read leaves it unchanged. */
    fun reduce(event: CoreEvent): Chat = runCatching { apply(event) }.getOrDefault(this)

    private fun apply(event: CoreEvent): Chat = when (event.type) {
        Events.MESSAGE_START.name -> copy(streaming = "")
        Events.MESSAGE_DELTA.name -> copy(streaming = streaming.orEmpty() + event.decode(Events.MESSAGE_DELTA)!!.text)
        // Commentary beside tool calls: sealed with the core's words, the buffer emptied (never null: the turn runs on).
        Events.MESSAGE_INTERIM.name -> event.decode(Events.MESSAGE_INTERIM)!!.text.trim()
            .takeIf { running && it.isNotEmpty() }?.let { copy(entries = entries + Entry.Interim(it), streaming = "") } ?: this
        Events.REASONING_DELTA.name -> copy(reasoning = reasoning.orEmpty() + event.decode(Events.REASONING_DELTA)!!.text)
        // A preview of reasoning the core will not stream: it stands in until the completion's own.
        Events.REASONING_AVAILABLE.name -> if (reasoning.isNullOrEmpty()) copy(reasoning = event.decode(Events.REASONING_AVAILABLE)!!.text) else this
        Events.MESSAGE_COMPLETE.name -> complete(event.decode(Events.MESSAGE_COMPLETE)!!)
        Events.TOOL_START.name -> event.decode(Events.TOOL_START)!!.let { copy(entries = entries + Entry.Tool(it.toolId, it.name)) }
        Events.TOOL_COMPLETE.name -> event.decode(Events.TOOL_COMPLETE)!!.let { done ->
            copy(entries = entries.map { if (it is Entry.Tool && it.id == done.toolId) it.copy(summary = done.summary, done = true) else it })
                .planned(done.revision?.let { revision -> done.todos?.let { TodoList.of(it, revision) } })  // an unversioned list is not taken (D3)
        }
        Events.TODO_UPDATED.name -> event.decode(Events.TODO_UPDATED)!!.let { planned(TodoList.of(it.todos, it.revision)) }
        Events.SESSION_USAGE.name -> measured(event.decode(Events.SESSION_USAGE)!!.usage)
        Events.STATUS_UPDATE.name -> copy(activity = event.decode(Events.STATUS_UPDATE)!!.text)
        Events.SESSION_INFO.name -> event.decode(Events.SESSION_INFO)!!.let { info ->
            (info.model.takeIf { it.isNotBlank() }?.let { copy(model = it) } ?: this).measured(info.usage)
        }
        // The core may end a turn with `error` alone (a refused @-reference, an ownership refusal, a crash), and the
        // TUI treats every `error` as the end of the turn: so does the chat, keeping the message as a notice.
        Events.ERROR.name -> notice(event.decode(Events.ERROR)!!.message).copy(streaming = null, activity = null, reasoning = null)
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
        val ended = copy(entries = entries + Entry.Reply(text, done.status ?: TurnStatus.COMPLETE, done.error, thought),
                         streaming = null, activity = null, reasoning = null).measured(done.usage)
        return done.persistedTurn?.userRowId?.let(ended::boundUser) ?: ended
    }

    /** The core's newer plan, when [next] is one (invariant 2); an unreadable or absent one changes nothing. */
    private fun planned(next: TodoList?): Chat = if (next == null) this else copy(todos = todos?.after(next) ?: next)

    /** The core's usage reading replaces the meter, none included (D5); no usage at all leaves it. */
    private fun measured(usage: Usage?): Chat = if (usage == null) this else copy(meter = ContextMeter.of(usage))
}
