package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.TranscriptMessage
import com.nousresearch.hermes.bridge.protocol.TurnStatus

/**
 * A stored conversation (`session.resume`'s messages, the core's display projection) as the chat's transcript:
 * the user's words, the agent's replies with their reasoning, and the tools it used. System rows (model switches
 * and the like) are timeline metadata the chat does not show.
 */
fun transcriptOf(messages: List<TranscriptMessage>): List<Entry> = messages.mapIndexedNotNull { i, message ->
    when (message.role) {
        "user" -> Entry.User(message.text.orEmpty())
        "assistant" -> Entry.Reply(message.text.orEmpty(), TurnStatus.COMPLETE, reasoning = message.reasoning?.takeIf { it.isNotBlank() })
        "tool" -> Entry.Tool(message.toolCallId ?: "history-$i", message.name ?: "tool", message.context, done = true)
        else -> null
    }
}
