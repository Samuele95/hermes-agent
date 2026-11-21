package com.nousresearch.hermes.android.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.SectionCaption

/** What the page hands over (settings-24): [create] writes a backup, [close] leaves; with [restore], the Restore section. */
class BackupActions(val create: () -> Unit, val close: () -> Unit, val restore: RestoreActions? = null)

/** A restore's acts: [pick] opens the system document picker, [confirm] and [cancel] answer the confirm, [restart] restarts the app. */
class RestoreActions(val pick: () -> Unit, val confirm: () -> Unit, val cancel: () -> Unit, val restart: () -> Unit)

/**
 * The Backup page (batch-4 bet 13): what a backup holds (D49's five parts, settings with every key blank), what it
 * never holds (keys and sign-ins, entered again on a new phone), that sessions are saved as written (§8), "Create
 * backup", and the last backup made. With [BackupActions.restore], the Restore section: what a restore replaces and
 * keeps, "Restore from backup…", the confirm showing what the picked archive holds, and after a restore its report
 * with "Restart now", the page's only way on (D7). While a call runs every button is off and the page cannot be left.
 */
@Composable
fun BackupScreen(state: BackupUi, actions: BackupActions, modifier: Modifier = Modifier) {
    val restore = actions.restore
    val leavable = state.leavable()
    BackHandler { if (state.leavable()) actions.close() }  // always on: a busy or restored page swallows Back
    Page(stringResource(R.string.backup_title), modifier.testTag("backup"), onBack = actions.close, backTag = "backup-close",
         backEnabled = leavable, busy = state.busy) {
        SectionCaption(stringResource(R.string.backup_section), Modifier.padding(top = 0.dp))
        Hint(stringResource(R.string.backup_holds), Modifier.testTag("backup-holds"))
        Hint(stringResource(R.string.backup_not_included), Modifier.padding(top = 8.dp).testTag("backup-not-included"))
        Hint(stringResource(R.string.backup_as_written), Modifier.padding(top = 8.dp).testTag("backup-as-written"))
        if (state.report == null) {
            PrimaryButton(stringResource(R.string.backup_create), actions.create, Modifier.padding(top = 12.dp).testTag("backup-create"),
                          enabled = leavable && state.file == null && state.picked == null)
        }
        state.made?.let {
            Hint(stringResource(R.string.backup_made, it.name, Formatter.formatShortFileSize(LocalContext.current, it.bytes), parts(it.parts)),
                 Modifier.padding(top = 8.dp).testTag("backup-file"))
        }
        restore?.let { RestoreSection(state, it) }
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("backup-error"), color = MaterialTheme.colorScheme.error) }
    }
    if (restore != null) state.picked?.let { picked -> picked.holds?.let { RestoreDialog(picked.name, it, state.busy, restore) } }
}

/** What a restore replaces and keeps and "Restore from backup…"; once restored, the report and "Restart now" instead. */
@Composable
private fun RestoreSection(state: BackupUi, restore: RestoreActions) {
    SectionCaption(stringResource(R.string.restore_section))
    Hint(stringResource(R.string.backup_restore_explain), Modifier.testTag("backup-restore-explain"))
    val report = state.report
    if (report == null) {
        SecondaryButton(stringResource(R.string.backup_restore), restore.pick, Modifier.padding(top = 12.dp).testTag("backup-restore"),
                        enabled = state.leavable() && state.picked == null)
        return
    }
    val skipped = report.skipped.toInt()
    Hint(stringResource(R.string.backup_restored, restoredParts(report)) +
             if (skipped > 0) pluralStringResource(R.plurals.backup_restored_skipped, skipped, skipped) else "",
         Modifier.padding(top = 12.dp).testTag("backup-report"), color = MaterialTheme.colorScheme.onSurface)
    Hint(stringResource(R.string.backup_restart_explain), Modifier.padding(top = 8.dp).testTag("backup-restart-explain"))
    PrimaryButton(stringResource(R.string.backup_restart), restore.restart, Modifier.padding(top = 12.dp).testTag("backup-restart"))
}

/** Asks before the picked archive [name] replaces anything, naming what it [holds]; both buttons are off while it runs. */
@Composable
private fun RestoreDialog(name: String, holds: BackupContents, busy: Boolean, restore: RestoreActions) {
    val skipped = holds.skipped.toInt()
    AlertDialog(onDismissRequest = { if (!busy) restore.cancel() }, modifier = Modifier.testTag("backup-restore-dialog"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                title = { Text(stringResource(R.string.backup_restore_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.backup_restore_body))
                        Text(stringResource(R.string.backup_restore_holds, name, heldParts(holds)),
                             Modifier.padding(top = 8.dp).testTag("backup-restore-holds"))
                        if (skipped > 0) {
                            Text(pluralStringResource(R.plurals.backup_skipped, skipped, skipped),
                                 Modifier.padding(top = 8.dp).testTag("backup-restore-skipped"))
                        }
                        Text(stringResource(R.string.backup_restore_undo), Modifier.padding(top = 8.dp))
                    }
                },
                confirmButton = { PrimaryButton(stringResource(R.string.backup_restore_confirm), restore.confirm,
                                                Modifier.testTag("backup-restore-confirm"), enabled = !busy) },
                dismissButton = { SecondaryButton(stringResource(R.string.job_form_cancel), restore.cancel,
                                                  Modifier.testTag("backup-restore-cancel"), enabled = !busy) })
}

/** What an archive holds, sessions with their count: "12 sessions, memories, skills, settings, jobs". */
@Composable
private fun heldParts(holds: BackupContents): String {
    val sessions = holds.sessions.toInt()
    val rest = parts(holds.parts - BackupPart.SESSIONS)
    if (BackupPart.SESSIONS !in holds.parts) return rest
    return listOf(pluralStringResource(R.plurals.backup_sessions, sessions, sessions), rest).filter { it.isNotEmpty() }.joinToString(", ")
}

/** What a restore replaced, sessions with their count: "sessions (12), memories, skills, settings, jobs". */
@Composable
private fun restoredParts(report: RestoreReport): String {
    val rest = parts(report.restored - BackupPart.SESSIONS)
    if (BackupPart.SESSIONS !in report.restored) return rest
    val sessions = "${stringResource(R.string.backup_part_sessions)} (${report.sessions})"
    return listOf(sessions, rest).filter { it.isNotEmpty() }.joinToString(", ")
}

/** [parts] by name, joined with ", " in [BackupPart] order. */
@Composable
internal fun parts(parts: Set<BackupPart>): String = BackupPart.entries.filter { it in parts }.map {
    stringResource(when (it) {
        BackupPart.SESSIONS -> R.string.backup_part_sessions
        BackupPart.MEMORIES -> R.string.backup_part_memories
        BackupPart.SKILLS -> R.string.backup_part_skills
        BackupPart.CONFIG -> R.string.backup_part_config
        BackupPart.JOBS -> R.string.backup_part_jobs
    })
}.joinToString(", ")
