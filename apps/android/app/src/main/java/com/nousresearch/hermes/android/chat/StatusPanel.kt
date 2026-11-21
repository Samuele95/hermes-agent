package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.forUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The Status sheet: the core's `/status` text for the chat's session ([text], exactly as `session.status` wrote it:
 * never split, trimmed or rewritten, D10), or what went wrong ([error]). Loading while both are null.
 */
data class StatusUi(val text: String? = null, val error: String? = null)

/** The Status sheet's controller (protocol-21, merged into sessions-12): [open] reads the core's [status] text into the sheet. */
class StatusPanel(private val status: suspend () -> String) {
    private val screen = MutableStateFlow<StatusUi?>(null)
    val state: StateFlow<StatusUi?> = screen.asStateFlow()

    /** Opens the sheet loading, then shows the core's text or its words; a sheet closed meanwhile stays closed. */
    suspend fun open() {
        screen.value = StatusUi()
        val read = try {
            StatusUi(text = status())
        } catch (failed: Exception) {
            StatusUi(error = forUser(failed))
        }
        screen.update { if (it == null) null else read }
    }

    fun close() {
        screen.value = null
    }
}
