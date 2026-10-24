package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.MessageCompletePayload
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.serialization.json.JsonPrimitive

/** One item of a chat's transcript. */
sealed interface Entry {
    data class User(val text: String) : Entry

    /** The agent's answer; [reasoning] is the model's reasoning for it, when the core shows reasoning. */
    data class Reply(val text: String, val status: TurnStatus, val error: String? = null, val reasoning: String? = null) : Entry

    data class Tool(val id: String, val name: String, val summary: String? = null, val done: Boolean = false) : Entry

    data class Notice(val text: String) : Entry
}

/**
 * A chat's transcript. While a turn runs, [streaming] is the reply written so far, [reasoning] the model's
 * reasoning so far (when the core shows it), [activity] what the agent is doing, and [prompts] what it is
 * waiting on the user for.
 */
data class Chat(val entries: List<Entry> = emptyList(), val streaming: String? = null, val activity: String? = null,
                val prompts: List<Prompt> = emptyList(), val reasoning: String? = null) {
    val running: Boolean get() = streaming != null

    fun sent(text: String): Chat = copy(entries = entries + Entry.User(text))

    fun notice(text: String): Chat = copy(entries = entries + Entry.Notice(text))

    fun asked(prompt: Prompt): Chat = copy(prompts = prompts + prompt)

    fun settled(id: String): Chat = copy(prompts = prompts.filterNot { it.id == id })

    /** The chat after one event of its session. A payload the app cannot read leaves it unchanged. */
    fun reduce(event: CoreEvent): Chat = runCatching { apply(event) }.getOrDefault(this)

    private fun apply(event: CoreEvent): Chat = when (event.type) {
        Events.MESSAGE_START.name -> copy(streaming = "")
        Events.MESSAGE_DELTA.name -> copy(streaming = streaming.orEmpty() + event.decode(Events.MESSAGE_DELTA)!!.text)
        Events.REASONING_DELTA.name -> copy(reasoning = reasoning.orEmpty() + event.decode(Events.REASONING_DELTA)!!.text)
        // A preview of reasoning the core will not stream: it stands in until the completion's own.
        Events.REASONING_AVAILABLE.name -> if (reasoning.isNullOrEmpty()) copy(reasoning = event.decode(Events.REASONING_AVAILABLE)!!.text) else this
        Events.MESSAGE_COMPLETE.name -> complete(event.decode(Events.MESSAGE_COMPLETE)!!)
        Events.TOOL_START.name -> event.decode(Events.TOOL_START)!!.let { copy(entries = entries + Entry.Tool(it.toolId, it.name)) }
        Events.TOOL_COMPLETE.name -> event.decode(Events.TOOL_COMPLETE)!!.let { done ->
            copy(entries = entries.map { if (it is Entry.Tool && it.id == done.toolId) it.copy(summary = done.summary, done = true) else it })
        }
        Events.STATUS_UPDATE.name -> copy(activity = event.decode(Events.STATUS_UPDATE)!!.text)
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
        return copy(entries = entries + Entry.Reply(text, done.status ?: TurnStatus.COMPLETE, done.error, thought),
                    streaming = null, activity = null, reasoning = null)
    }
}
