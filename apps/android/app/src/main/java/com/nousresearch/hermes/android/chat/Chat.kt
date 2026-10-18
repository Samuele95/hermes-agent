package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.MessageCompletePayload
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.serialization.json.JsonPrimitive

/** One item of a chat's transcript. */
sealed interface Entry {
    data class User(val text: String) : Entry

    data class Reply(val text: String, val status: TurnStatus, val error: String? = null) : Entry

    data class Tool(val id: String, val name: String, val summary: String? = null, val done: Boolean = false) : Entry

    data class Notice(val text: String) : Entry
}

/** A chat's transcript. While a turn runs, [streaming] is the reply written so far and [activity] what the agent is doing. */
data class Chat(val entries: List<Entry> = emptyList(), val streaming: String? = null, val activity: String? = null) {
    val running: Boolean get() = streaming != null

    fun sent(text: String): Chat = copy(entries = entries + Entry.User(text))

    fun notice(text: String): Chat = copy(entries = entries + Entry.Notice(text))

    /** The chat after one event of its session. A payload the app cannot read leaves it unchanged. */
    fun reduce(event: CoreEvent): Chat = runCatching { apply(event) }.getOrDefault(this)

    private fun apply(event: CoreEvent): Chat = when (event.type) {
        Events.MESSAGE_START.name -> copy(streaming = "")
        Events.MESSAGE_DELTA.name -> copy(streaming = streaming.orEmpty() + event.decode(Events.MESSAGE_DELTA)!!.text)
        Events.MESSAGE_COMPLETE.name -> complete(event.decode(Events.MESSAGE_COMPLETE)!!)
        Events.TOOL_START.name -> event.decode(Events.TOOL_START)!!.let { copy(entries = entries + Entry.Tool(it.toolId, it.name)) }
        Events.TOOL_COMPLETE.name -> event.decode(Events.TOOL_COMPLETE)!!.let { done ->
            copy(entries = entries.map { if (it is Entry.Tool && it.id == done.toolId) it.copy(summary = done.summary, done = true) else it })
        }
        Events.STATUS_UPDATE.name -> copy(activity = event.decode(Events.STATUS_UPDATE)!!.text)
        Events.ERROR.name -> notice(event.decode(Events.ERROR)!!.message)
        else -> this
    }

    private fun complete(done: MessageCompletePayload): Chat {
        // The completed text is authoritative; a structured (parts) completion keeps what was streamed.
        val text = (done.text as? JsonPrimitive)?.takeIf { it.isString }?.content ?: streaming.orEmpty()
        return copy(entries = entries + Entry.Reply(text, done.status ?: TurnStatus.COMPLETE, done.error), streaming = null, activity = null)
    }
}
