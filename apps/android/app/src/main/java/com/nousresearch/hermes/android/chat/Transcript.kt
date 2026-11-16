package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.TranscriptMessage
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

/**
 * A stored conversation (`session.resume`'s messages, the core's display projection) as the chat's transcript:
 * the user's words with the core's row ids and times, the agent's replies with their row ids, times, reasoning and the
 * user's own reactions, and the tools it used with what the history kept of them (args, diff; output only for the
 * file-edit and skill tools). System rows (model switches and the like) are timeline metadata the chat does not show.
 * Its rows are keyed on from [from], one id per row it keeps.
 */
fun transcriptOf(messages: List<TranscriptMessage>, from: Long): List<Entry> {
    var next = from
    return messages.mapIndexedNotNull { i, message ->
        when (message.role) {
            "user" -> Entry.User(message.text.orEmpty(), message.rowId, next++, at = msOf(message.timestamp))
            "assistant" -> Entry.Reply(message.text.orEmpty(), TurnStatus.COMPLETE, reasoning = message.reasoning?.takeIf { it.isNotBlank() },
                                       stableId = next++, rowId = message.rowId, at = msOf(message.timestamp),
                                       reactions = reactionsOf(message.displayMetadata))
            // The history keeps no duration; its raw content only for write_file / patch / skill_manage.
            "tool" -> Entry.Tool(message.toolCallId ?: "history-$i", message.name ?: "tool", message.context, done = true, stableId = next++,
                                 args = message.args, result = Entry.Tool.resultOf(message.content), diff = diffOf(message.displayMetadata),
                                 failed = Entry.Tool.failedOf(message.content))
            else -> null
        }
    }
}

/** A stored row's time, the core's Unix seconds, in epoch ms: the one place seconds become milliseconds (EJ-62); none (D9): null. */
private fun msOf(seconds: Double?): Long? = seconds?.takeIf { it > 0 }?.let { (it * 1000).roundToLong() }

/** A stored tool row's diff, `display_metadata.tool_result_metadata.inline_diff`, read totally; none or blank: null. */
private fun diffOf(meta: JsonElement?): String? =
    (((meta as? JsonObject)?.get("tool_result_metadata") as? JsonObject)?.get("inline_diff") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
