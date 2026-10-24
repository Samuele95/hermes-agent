package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The history screen: past sessions, newest first as the core lists them, and what went wrong if anything. */
data class HistoryUi(val sessions: List<SessionListRow>, val busy: Boolean = false, val error: String? = null)

/** Past conversations (spec §2.5): [list] them, and [resume] the one the user picks. [state] is null while closed. */
class SessionHistory(
    private val list: suspend () -> List<SessionListRow>,
    private val resume: suspend (sessionId: String) -> Unit,
) {
    private val screen = MutableStateFlow<HistoryUi?>(null)
    val state: StateFlow<HistoryUi?> = screen.asStateFlow()

    suspend fun open() {
        screen.value = HistoryUi(emptyList(), busy = true)
        screen.value = try {
            HistoryUi(list())
        } catch (failed: Exception) {
            HistoryUi(emptyList(), error = forUser(failed))
        }
    }

    fun close() {
        screen.value = null
    }

    /** Continues [sessionId] in the chat and returns to it. */
    suspend fun pick(sessionId: String) {
        screen.update { it?.copy(busy = true, error = null) }
        try {
            resume(sessionId)
            screen.value = null
        } catch (failed: Exception) {
            screen.update { it?.copy(busy = false, error = forUser(failed)) }
        }
    }
}
