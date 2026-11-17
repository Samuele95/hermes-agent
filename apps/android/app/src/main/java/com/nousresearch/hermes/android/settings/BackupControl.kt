package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BackupInspectParams
import com.nousresearch.hermes.bridge.contract.BackupRestoreParams
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/**
 * The process's one Backup page (settings-24; batch-4 bet 13, F3, F4, DC-P26, DC-P28, DC-P29); [state] is null while
 * it is closed. `backup_create` zips exactly D49's five [BackupPart]s (sessions, memories, skills, settings with every
 * key field blank, jobs) into the app's cache, never a key or a sign-in; the zip then waits here until the showing
 * activity has really handed it to the share sheet through M13's `FileProvider` path ([handOff], N6), and one the
 * sheet can never take waits no longer ([unshareable], N15). A backup may run while a turn or the always-on gateway
 * runs (D11): the SQLite backup API snapshots under writers.
 *
 * A restore (DC-P28, EJ-78): an archive the user picked ([picked]) is read by `backup_inspect`, which writes nothing,
 * and shown with what it holds; it is admitted only when no turn runs ([idle]) and the gateway is not alive ([seen],
 * D2), checked at the pick and again at the confirm, and the Python half checks last (P2 D1), so a refusal is the same
 * sentence from either ([BUSY], [GATEWAY], D3). Only after the user confirms does `backup_restore` replace the five
 * parts. On success nothing but [restartNow] leaves the page (D7): the chat process's in-memory sessions, caches and
 * config are older than the restored files (§2.4), and the interpreter cannot start twice in one process, so [restart]
 * starts a new one. Every picked copy is removed: by the Python half after a restore, here otherwise (§2.2, D10).
 * One call at a time (EJ-78); the platform's acts (the picker, the restart, the gateway's state) arrive as function
 * values and activity callbacks (HEX).
 */
class BackupControl(private val bridge: AppBridge, private val idle: () -> Boolean, private val seen: () -> GatewaySeen,
                    private val restart: () -> Unit) {
    companion object {
        /** The Python half's refusal while a turn runs (`backup.py`'s BUSY; a host test holds them equal, D3). */
        const val BUSY = "Hermes is still answering. Stop the reply or let it finish, then restore the backup."

        /** The Python half's refusal while the always-on gateway runs (`backup.py`'s GATEWAY, D3). */
        const val GATEWAY = "The always-on gateway is running. Turn it off, then restore the backup."

        /** A picked file the app could not copy into its cache. */
        const val UNREADABLE = "The picked file could not be read."
    }

    private val screen = MutableStateFlow<BackupUi?>(null)
    val state: StateFlow<BackupUi?> = screen.asStateFlow()

    /** Shows the page; one already open is kept, so a waiting zip or a report is not lost. Nothing is asked. */
    fun open() {
        screen.update { it ?: BackupUi() }
    }

    /**
     * Closes the page, except while a call runs or after a restore (only the restart leaves it then, D7). A zip still
     * waiting goes with it, and a picked copy is deleted.
     */
    fun close() {
        val before = screen.getAndUpdate { if (it?.leavable() == true) null else it }
        if (before?.leavable() == true) before.picked?.let { File(it.path).delete() }
    }

    /**
     * Writes a backup: admitted only on an open, leavable page with nothing picked and no zip waiting, in one atomic
     * step. The zip waits for the share sheet and is shown as the last one made; a failure shows its words.
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

    /**
     * The user picked an archive, copied to [path] under its [name]: admitted only on an open, leavable page with nothing
     * picked, and only when [refusal] has nothing to say (else its words show). Then `backup_inspect` reads what it
     * holds, writing nothing, and the confirm shows. Anything not admitted, refused or unreadable deletes the copy.
     */
    suspend fun picked(path: String, name: String) {
        val refused = refusal()
        val pick = PickedBackup(path, name)
        val before = screen.getAndUpdate { ui ->
            when {
                ui?.admitsPick() != true -> ui
                refused != null -> ui.copy(error = refused)
                else -> ui.copy(busy = true, picked = pick, error = null)
            }
        }
        if (before?.admitsPick() != true || refused != null) {
            File(path).delete()
            return
        }
        try {
            val holds = BackupContents.of(bridge.ask(BridgeCalls.BACKUP_INSPECT, BackupInspectParams(path)))
            screen.update { it?.copy(busy = false, picked = pick.copy(holds = holds)) }
        } catch (cancelled: CancellationException) {
            File(path).delete()
            screen.update { it?.copy(busy = false, picked = null) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            File(path).delete()
            screen.update { it?.copy(busy = false, picked = null, error = words) }
        }
    }

    /** The picked file could not be copied into the app's cache: the page says so. */
    fun unreadable() {
        screen.update { it?.copy(error = UNREADABLE) }
    }

    /** The user declined the confirm: the picked copy is deleted, nothing is sent. Not while a call runs. */
    fun cancel() {
        val before = screen.getAndUpdate { if (it == null || it.busy) it else it.copy(picked = null) }
        if (before != null && !before.busy) before.picked?.let { File(it.path).delete() }
    }

    /**
     * The user confirmed: admitted only with a read pick on an idle page. [refusal] is asked again (the state may have
     * changed since the pick): its words show, the copy is deleted and nothing is sent. Else `backup_restore` replaces
     * the five parts and its report shows, with the restart; a refusal or failure shows its words. [restart] is never
     * called here: the user reads the report first.
     */
    suspend fun confirmRestore() {
        val refused = refusal()
        val before = screen.getAndUpdate { ui ->
            when {
                ui?.admitsRestore() != true -> ui
                refused != null -> ui.copy(picked = null, error = refused)
                else -> ui.copy(busy = true, error = null)
            }
        }
        if (before?.admitsRestore() != true) return
        val picked = checkNotNull(before.picked)
        if (refused != null) {
            File(picked.path).delete()
            return
        }
        try {
            val report = RestoreReport.of(bridge.ask(BridgeCalls.BACKUP_RESTORE, BackupRestoreParams(picked.path)))
            screen.update { it?.copy(busy = false, picked = null, report = report) }
        } catch (cancelled: CancellationException) {
            screen.update { it?.copy(busy = false, picked = null) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            File(picked.path).delete()  // the Python half deletes it too; a failure before it ran would leave it
            screen.update { it?.copy(busy = false, picked = null, report = null, error = words) }
        }
    }

    /** Restarts the app, only once a restore has replaced this phone's data; anything else does nothing. */
    fun restartNow() {
        if (screen.value?.report != null) restart()
    }

    /** The app's first gate (F4): a turn running in the chat, or the always-on gateway alive; null when neither. */
    private fun refusal(): String? = when {
        !idle() -> BUSY
        seen().alive() -> GATEWAY
        else -> null
    }

    private fun BackupUi.admitsBackup() = leavable() && picked == null && file == null

    private fun BackupUi.admitsPick() = leavable() && picked == null

    private fun BackupUi.admitsRestore() = !busy && report == null && picked?.holds != null
}
