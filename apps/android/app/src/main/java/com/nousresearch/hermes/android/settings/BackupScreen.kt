package com.nousresearch.hermes.android.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SectionCaption

/** What the page hands over (settings-24): [create] writes a backup, [close] leaves. */
class BackupActions(val create: () -> Unit, val close: () -> Unit)

/**
 * The Backup page (batch-4 bet 13): what a backup holds (D49's five parts, settings with every key blank), what it
 * never holds (keys and sign-ins, entered again on a new phone), that sessions are saved as written (§8), "Create
 * backup", and the last backup made. While a call runs every button is off and the page cannot be left.
 */
@Composable
fun BackupScreen(state: BackupUi, actions: BackupActions, modifier: Modifier = Modifier) {
    BackHandler { if (!state.busy) actions.close() }  // always on: a busy page swallows Back
    Page(stringResource(R.string.backup_title), modifier.testTag("backup"), onBack = actions.close, backTag = "backup-close",
         backEnabled = !state.busy, busy = state.busy) {
        SectionCaption(stringResource(R.string.backup_section), Modifier.padding(top = 0.dp))
        Hint(stringResource(R.string.backup_holds), Modifier.testTag("backup-holds"))
        Hint(stringResource(R.string.backup_not_included), Modifier.padding(top = 8.dp).testTag("backup-not-included"))
        Hint(stringResource(R.string.backup_as_written), Modifier.padding(top = 8.dp).testTag("backup-as-written"))
        PrimaryButton(stringResource(R.string.backup_create), actions.create, Modifier.padding(top = 12.dp).testTag("backup-create"),
                      enabled = !state.busy && state.file == null)
        state.made?.let {
            Hint(stringResource(R.string.backup_made, it.name, Formatter.formatShortFileSize(LocalContext.current, it.bytes), parts(it.parts)),
                 Modifier.padding(top = 8.dp).testTag("backup-file"))
        }
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("backup-error"), color = MaterialTheme.colorScheme.error) }
    }
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
