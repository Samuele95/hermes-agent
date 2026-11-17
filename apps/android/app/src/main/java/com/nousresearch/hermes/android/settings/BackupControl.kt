package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/**
 * The process's one Backup page (settings-24; batch-4 bet 13, F3, F4, DC-P26, DC-P29); [state] is null while it is
 * closed. `backup_create` zips exactly D49's five [BackupPart]s (sessions, memories, skills, settings with every key
 * field blank, jobs) into the app's cache, never a key or a sign-in; the zip then waits here until the showing activity
 * has really handed it to the share sheet through M13's `FileProvider` path ([handOff], N6), and one the sheet can
 * never take waits no longer ([unshareable], N15). One call at a time, and none while a zip waits (EJ-78). A backup
 * may run while a turn or the always-on gateway runs (D11): the SQLite backup API snapshots under writers.
 */
class BackupControl(private val bridge: AppBridge) {
    private val screen = MutableStateFlow<BackupUi?>(null)
    val state: StateFlow<BackupUi?> = screen.asStateFlow()

    /** Shows the page; one already open is kept, so a waiting zip is not lost. Nothing is asked. */
    fun open() {
        screen.update { it ?: BackupUi() }
    }

    /** Closes the page, except while a call runs (it cannot be left meanwhile). A zip still waiting goes with it. */
    fun close() {
        screen.update { if (it?.busy == true) it else null }
    }

    /**
     * Writes a backup: admitted only on an open, idle page with no zip waiting, in one atomic step. The zip waits for
     * the share sheet and is shown as the last one made; a failure shows its words.
     */
    suspend fun create() {
        val before = screen.getAndUpdate { if (it?.admitsBackup() == true) it.copy(busy = true, error = null) else it }
        if (before?.admitsBackup() != true) return
        val shown: (BackupUi) -> BackupUi = try {
            val made = BackupFile.of(bridge.ask(BridgeCalls.BACKUP_CREATE))
            ({ it.copy(file = made, made = made, error = null) })
        } catch (cancelled: CancellationException) {
            screen.update { it?.copy(busy = false) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            ({ it.copy(file = null, error = words) })
        }
        screen.update { it?.let(shown)?.copy(busy = false) }
    }

    /**
     * Offers the waiting [file] to [share], the activity's share sheet: it waits no longer only when [share] says an
     * activity was started (N6); a file no longer waiting is not offered. [BackupUi.made] stays.
     */
    fun handOff(file: BackupFile, share: (BackupFile) -> Boolean) {
        if (screen.value?.file != file) return
        if (share(file)) screen.update { if (it?.file == file) it.copy(file = null) else it }
    }

    /** The share sheet can never take [file]: it waits no longer, if it is still the one waiting, so a new backup can start (N15). */
    fun unshareable(file: BackupFile) {
        screen.update { if (it?.file == file) it.copy(file = null) else it }
    }

    private fun BackupUi.admitsBackup() = !busy && file == null
}
