package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.InTurnPrompt
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.protocol.ClarifyQuestion
import com.nousresearch.hermes.bridge.protocol.Requests
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** A question the agent asks in the middle of a turn; the turn waits until the user answers it. */
sealed interface Prompt {
    /** The core's request id, which its answer and its withdrawal (`request.cancel`) carry. */
    val id: String

    /** A command the agent wants to run; [requestId] is the approval's own id (`approval.cancelled`). */
    data class Approval(override val id: String, val requestId: String, val command: String, val description: String,
                        val choices: List<ApprovalChoice>) : Prompt

    data class Clarify(override val id: String, val questions: List<ClarifyQuestion>) : Prompt

    /** A variable a skill needs ([envVar]); the value goes to the keystore, never into the chat's state. */
    data class Secret(override val id: String, val envVar: String, val prompt: String) : Prompt
}

/** The prompt [raw] asks for, or null when it is a request the app does not show (or [session]'s is another). */
internal fun prompt(raw: InTurnPrompt, session: String?): Prompt? {
    raw.decode(Requests.APPROVAL)?.takeIf { it.sessionId == session }?.let {
        return Prompt.Approval(raw.id, it.requestId, it.command, it.description, it.choices ?: listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY))
    }
    raw.decode(Requests.CLARIFY)?.takeIf { it.sessionId == session }?.let { return Prompt.Clarify(raw.id, it.questions) }
    raw.decode(Requests.SECRET)?.takeIf { it.sessionId == session }?.let { return Prompt.Secret(raw.id, it.envVar, it.prompt) }
    return null
}

/** A multi-select clarify answer as the core reads it: a JSON list in the answer's string. */
fun multiSelect(picked: List<String>): String = JsonArray(picked.map(::JsonPrimitive)).toString()
