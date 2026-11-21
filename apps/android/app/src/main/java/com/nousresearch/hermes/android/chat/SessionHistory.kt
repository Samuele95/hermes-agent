package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/**
 * The drawer's list: past sessions, newest first as the core lists them, and what went wrong if anything; the row
 * being renamed ([editing]) or awaiting a delete confirmation ([confirmDelete]).
 */
data class HistoryUi(val sessions: List<SessionListRow>, val busy: Boolean = false, val error: String? = null,
                     val editing: StoredSessionId? = null, val confirmDelete: StoredSessionId? = null)

/** Saving [next] on the row being renamed: busy, the field closed (unchanged, D6), or null when not admitted (busy, no field, blank: D5). */
private fun HistoryUi.renaming(next: String): HistoryUi? = when {
    busy || editing == null || next.isEmpty() -> null
    next == sessions.find { it.stored == editing }?.title?.trim() -> copy(editing = null, confirmDelete = null, error = null)
    else -> copy(busy = true, error = null)
}

/** Deleting the confirmed row: busy with the confirmation gone, or null when not admitted. */
private fun HistoryUi.deleting(): HistoryUi? = if (busy || confirmDelete == null) null else copy(confirmDelete = null, busy = true, error = null)

/**
 * Past conversations (spec §2.5): [list] them, [resume] the one the user picks, [rename] (sessions-05) or [delete]
 * (sessions-06) one. The core's rules for those live in [ChatSession]; this owns only the drawer's flow (SOLID-S), and
 * lists the rows again after each change. [state] is null while closed.
 */
class SessionHistory(
    private val list: suspend () -> List<SessionListRow>,
    private val resume: suspend (stored: StoredSessionId) -> Unit,
    private val rename: suspend (stored: StoredSessionId, title: String) -> Unit,
    private val delete: suspend (stored: StoredSessionId) -> Unit,
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

    /** Continues [stored] in the chat and returns to it. */
    suspend fun pick(stored: StoredSessionId) {
        screen.update { it?.copy(busy = true, error = null) }
        holdingBusy {
            try {
                resume(stored)
                screen.value = null
            } catch (failed: Exception) {
                screen.update { it?.copy(busy = false, error = forUser(failed)) }
            }
        }
    }

    /** Starts renaming row [stored] (null stops); not while a change runs. */
    fun edit(stored: StoredSessionId?) {
        screen.update { ui -> if (ui == null || ui.busy) ui else ui.copy(editing = stored, confirmDelete = null, error = null) }
    }

    /**
     * Saves [title] on the row being renamed and lists the rows again: the title shown is the core's (invariant 5). An
     * unchanged title closes the field (D6); a blank one is not sent (D5). A refusal keeps the field, with its words.
     */
    suspend fun saveTitle(title: String) {
        val next = title.trim()
        val before = screen.getAndUpdate { it?.renaming(next) ?: it }  // one atomic step: a double tap acts once (V4)
        val entered = before?.renaming(next) ?: return
        if (!entered.busy) return  // unchanged title: the field closed (D6)
        val id = checkNotNull(before.editing)
        holdingBusy {
            try {
                rename(id, next)
            } catch (failed: Exception) {
                screen.update { it?.copy(busy = false, error = forUser(failed)) }
                return
            }
            relisted()
        }
    }

    /** Asks to delete row [stored] (null: not now); nothing is deleted until [confirmDelete]. */
    fun askDelete(stored: StoredSessionId?) {
        screen.update { ui -> if (ui == null || ui.busy) ui else ui.copy(confirmDelete = stored, editing = null, error = null) }
    }

    /** Deletes the row the user confirmed, then lists the rows again; a refusal is shown and the list stays (D8). */
    suspend fun confirmDelete() {
        val before = screen.getAndUpdate { it?.deleting() ?: it }  // one atomic step: a double tap acts once (V4)
        val id = before?.takeIf { it.deleting() != null }?.confirmDelete ?: return
        holdingBusy {
            try {
                delete(id)
            } catch (failed: Exception) {
                screen.update { it?.copy(busy = false, error = forUser(failed)) }
                return
            }
            relisted()
        }
    }

    /** Runs a change this drawer entered busy for; a cancellation leaves it not busy (forUser rethrows it), and is rethrown. */
    private suspend inline fun holdingBusy(change: () -> Unit) {
        try {
            change()
        } catch (cancelled: CancellationException) {
            screen.update { it?.copy(busy = false) }
            throw cancelled
        }
    }

    /** After a change: the core's rows again, into an open drawer; a closed one stays closed. */
    private suspend fun relisted() {
        val rows = try {
            list()
        } catch (failed: Exception) {
            screen.update { it?.copy(busy = false, editing = null, error = forUser(failed)) }
            return
        }
        screen.update { it?.let { HistoryUi(rows) } }
    }
}
