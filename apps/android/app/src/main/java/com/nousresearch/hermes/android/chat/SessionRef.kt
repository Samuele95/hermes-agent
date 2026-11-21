package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.SessionListRow

/** The id the core's frames carry for a session attached here (`session_id` of a frame or a create/resume reply); stable for one attach. */
@JvmInline value class RuntimeSessionId(val value: String)

/** The key a stored conversation goes by: a drawer row's `id`, what `session.resume`, `session.delete` and `session.most_recent` speak. A compression rotates it. */
@JvmInline value class StoredSessionId(val value: String)

/** The core session a chat follows: [runtime] is the id its frames carry, [stored] the key its stored row goes by. */
data class SessionRef(val runtime: RuntimeSessionId, val stored: StoredSessionId) {
    /** After [event] of this session: a `session.info` names the stored key, which a compression rotates. */
    fun seen(event: CoreEvent): SessionRef = if (event.type != Events.SESSION_INFO.name) this else
        runCatching { event.decode(Events.SESSION_INFO)!!.storedSessionId }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { copy(stored = StoredSessionId(it)) } ?: this
}

/** A drawer row's key as the chat's calls take it: the one place a listing's id is wrapped (V4). */
val SessionListRow.stored: StoredSessionId get() = StoredSessionId(id)
