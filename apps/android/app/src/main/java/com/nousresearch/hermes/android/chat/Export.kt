package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.SessionExportParams
import com.nousresearch.hermes.bridge.contract.SessionExportReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/**
 * A session's export file in the app's cache (sessions-12, §2.3): written by the app's `session_export`, handed to the
 * share sheet, replaced by the next export. [bytes] is the file's size.
 */
data class ExportFile(val path: String, val name: String, val bytes: Long) {
    companion object {
        /** The bridge's reply, read here only (§5); its `integer` size is already a [Long]. */
        fun of(reply: SessionExportReply): ExportFile = ExportFile(reply.path, reply.name, reply.bytes)
    }
}

/** Asks the app's `session_export` to write stored session [stored]'s file: the session's row and every message (F8, bet 9). */
suspend fun AppBridge.exportSession(stored: StoredSessionId): ExportFile =
    ExportFile.of(ask(BridgeCalls.SESSION_EXPORT, SessionExportParams(stored.value)))

/** An export: on its way ([busy]), its finished [file] until the activity has shared it, or the words of a failure ([error]). */
data class ExportUi(val busy: Boolean = false, val file: ExportFile? = null, val error: String? = null)

/**
 * Exports a session (a drawer row's, or the chat's own when null) through [export]. A finished file waits here until
 * the showing activity has really handed it to the share sheet ([handOff]), so neither a rotation nor a stopped activity
 * loses it, as the chat's offer waits (D8); one export at a time, and none while a file waits.
 */
class SessionExport(private val export: suspend (StoredSessionId?) -> ExportFile) {
    private val screen = MutableStateFlow(ExportUi())
    val state: StateFlow<ExportUi> = screen.asStateFlow()

    /** Exports [stored] (null: the chat's own session); a tap while one runs or a file waits does nothing (EJ-78, V4). */
    suspend fun export(stored: StoredSessionId?) {
        val before = screen.getAndUpdate { if (it.admits()) ExportUi(busy = true) else it }  // one atomic step
        if (!before.admits()) return
        val next = try {
            ExportUi(file = export.invoke(stored))
        } catch (cancelled: CancellationException) {
            screen.value = ExportUi()
            throw cancelled
        } catch (failed: Exception) {
            ExportUi(error = forUser(failed))
        }
        screen.value = next
    }

    /** The activity handed [file] on to the share sheet: it waits no longer, if it is still the one waiting. */
    fun shared(file: ExportFile) {
        screen.update { if (it.file == file) ExportUi() else it }
    }

    /**
     * Offers the waiting [file] to [share], the activity's share sheet: it waits no longer only when [share] says an
     * activity was started; a stopped activity's or a refused handoff keeps it for the next start (architect #2). A file no
     * longer waiting is not offered again (D4).
     */
    fun handOff(file: ExportFile, share: (ExportFile) -> Boolean) {
        if (screen.value.file != file) return
        if (share(file)) shared(file)
    }

    /** The words of a failed export were read. */
    fun dismiss() {
        screen.update { it.copy(error = null) }
    }

    private fun ExportUi.admits() = !busy && file == null
}
