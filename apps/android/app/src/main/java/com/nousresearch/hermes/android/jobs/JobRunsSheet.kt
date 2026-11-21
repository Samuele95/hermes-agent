package com.nousresearch.hermes.android.jobs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.SecondaryButton

/**
 * A job's past runs as a bottom sheet over the Scheduled jobs page (automation-07): newest first, as the core lists
 * them; each run's start, whether it still runs, how many messages it holds and its first prompt. A tap continues
 * the run in the chat. Nothing can be tapped, and the sheet cannot be dismissed, while a run is being opened. It is
 * tagged `job-runs-sheet`, apart from the ⋮ item `job-runs` that opens it (/improve 20261113T221531Z #3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobRunsSheet(ui: JobsUi, view: RunsView, actions: JobRunsActions) {
    val name = ui.jobs.find { it.id == view.job }?.name ?: view.job.value
    val enabled = !ui.busy
    val busy by rememberUpdatedState(ui.busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy })
    ModalBottomSheet(onDismissRequest = { if (!busy) actions.show(null) }, modifier = Modifier.testTag("job-runs-sheet"), sheetState = sheet,
                     containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
               verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.job_runs_title, name), style = MaterialTheme.typography.titleMedium)
            Hint(stringResource(R.string.job_runs_explain))
            view.error?.let { Hint(it, Modifier.testTag("job-runs-error"), color = MaterialTheme.colorScheme.error) }
            if (!view.loaded) {
                Hint(stringResource(R.string.job_runs_reading), Modifier.testTag("job-runs-reading"))
            } else if (view.runs.isEmpty() && view.error == null) {
                Hint(stringResource(R.string.job_runs_empty), Modifier.testTag("job-runs-empty"))
            }
            Column {
                view.runs.forEach { run -> RunRow(run, enabled = enabled, onOpen = { actions.open(run.sessionId) }) }
            }
            SecondaryButton(stringResource(R.string.job_runs_close), { actions.show(null) }, Modifier.testTag("job-runs-close"),
                            enabled = enabled)
        }
    }
}

/** One run: its start and whether it still runs or never closed (V9), then its messages, then its first prompt, quiet (V7). */
@Composable
private fun RunRow(run: JobRun, enabled: Boolean, onOpen: () -> Unit) {
    val id = run.sessionId.value
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().testTag("job-run-$id")
                   .clickable(enabled = enabled, onClickLabel = stringResource(R.string.job_run_open), onClick = onOpen)
                   .padding(vertical = 12.dp),
               verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(runTime(run.startedAt), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                when (run.phase) {
                    RunPhase.RUNNING -> Text(stringResource(R.string.job_run_running), Modifier.testTag("job-run-running-$id"),
                                             style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    RunPhase.UNFINISHED -> Text(stringResource(R.string.job_run_unfinished), Modifier.testTag("job-run-unfinished-$id"),
                                                style = MaterialTheme.typography.bodySmall, color = Hermes.colors.textTertiary)
                    RunPhase.ENDED -> Unit
                }
            }
            Text(pluralStringResource(R.plurals.job_run_messages, run.messages, run.messages),
                 style = MaterialTheme.typography.bodySmall, color = quiet)
            run.preview?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
}
