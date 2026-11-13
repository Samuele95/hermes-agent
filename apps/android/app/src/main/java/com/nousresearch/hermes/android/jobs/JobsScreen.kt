package com.nousresearch.hermes.android.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton

/**
 * What the page does with a job (automation-02, -06): make a [newJob], [edit] the one tapped, the [form] sheet's
 * actions, and with [row] each row's menu.
 */
class JobActions(val newJob: () -> Unit, val edit: (JobId) -> Unit, val form: JobFormActions, val row: JobRowActions? = null)

/** What a job row's menu does (automation-03, -04, -05); [askDelete] takes null to stop asking. */
class JobRowActions(val pause: (JobId) -> Unit, val resume: (JobId) -> Unit, val runNow: (JobId) -> Unit,
                    val askDelete: (JobId?) -> Unit, val confirmDelete: () -> Unit)

/**
 * The Scheduled jobs page (automation-01, automation-10): why the jobs will not run, when they will not, then every
 * job the core holds with its schedule, state, next and last run and last error. [onOpenGateway] opens the Gateway
 * page over this one. With [actions], a tap opens the job's form and New job makes one, in a sheet over the page.
 * With [JobActions.row], a row's ⋮ menu runs, pauses, resumes or deletes it; Delete asks first.
 */
@Composable
fun JobsScreen(state: JobsUi, onOpenGateway: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
               actions: JobActions? = null) {
    var menuFor by remember { mutableStateOf<JobId?>(null) }  // the row whose menu is open: transient UI (D7)
    Page(stringResource(R.string.jobs_title), modifier.testTag("jobs"), onBack = onClose, backTag = "jobs-close", busy = state.busy,
         action = actions?.let { a ->
             {
                 TextButton(a.newJob, Modifier.testTag("jobs-new"), enabled = !state.busy) { Text(stringResource(R.string.jobs_new)) }
             }
         }) {
        state.warning?.let { Warning(it, onOpenGateway) }
        Hint(stringResource(R.string.jobs_explain))
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("jobs-error"), color = MaterialTheme.colorScheme.error) }
        if (!state.busy && state.jobs.isEmpty() && state.error == null) {
            Hint(stringResource(R.string.jobs_empty), Modifier.padding(top = 12.dp).testTag("jobs-empty"))
        }
        Column(Modifier.padding(top = 8.dp)) {
            state.jobs.forEach { job ->
                JobRow(job, state, onEdit = actions?.let { a -> { a.edit(job.id) } },
                       menu = actions?.row?.let { a ->
                           { JobMenu(job, state, open = menuFor == job.id, onOpen = { menuFor = job.id }, onDismiss = { menuFor = null }, a) }
                       })
            }
        }
    }
    if (actions != null) state.form?.let { JobFormSheet(state, it, actions.form) }
    actions?.row?.let { a -> state.confirmDelete?.let { id -> DeleteDialog(state.jobs.find { it.id == id }?.name ?: id.value, state.busy, a) } }
}

/** The banner: the gateway is off (with the way to turn it on), starting, or its ticker has stopped stamping. */
@Composable
private fun Warning(warning: JobsWarning, onOpenGateway: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("jobs-warning").background(Hermes.colors.widget, shape)
               .border(1.dp, error, shape).padding(12.dp),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val starting = warning is JobsWarning.GatewayOff &&
            warning.status in setOf(GatewayStatus.STARTING, GatewayStatus.RESTARTING)
        val (text, tag) = when (warning) {
            is JobsWarning.GatewayOff ->
                if (starting) stringResource(R.string.jobs_gateway_starting) to "jobs-warning-starting"
                else stringResource(R.string.jobs_gateway_off) to "jobs-warning-gateway-off"
            is JobsWarning.NotTicking -> stringResource(R.string.jobs_not_ticking, elapsed(warning.minutes)) to "jobs-warning-not-ticking"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HermesIcons.Alert, null, Modifier.size(20.dp), tint = error)
            Text(text, Modifier.weight(1f).padding(start = 10.dp).testTag(tag), style = MaterialTheme.typography.bodyMedium)
        }
        // A gateway on its way up needs no shortcut (V7).
        if (warning is JobsWarning.GatewayOff && !starting) {
            PrimaryButton(stringResource(R.string.jobs_turn_on), onOpenGateway, Modifier.testTag("jobs-open-gateway"))
        }
        if (warning is JobsWarning.NotTicking && warning.error != null) {
            Hint(stringResource(R.string.jobs_ticker_error, warning.error), Modifier.testTag("jobs-ticker-error"), color = error)
        }
    }
}

/**
 * One job, as the core lists it, and whether Run now was asked for it; with [onEdit], a tap opens its form (V10), and
 * with [menu], its ⋮ button sits at the row's end.
 */
@Composable
private fun JobRow(job: Job, state: JobsUi, onEdit: (() -> Unit)?, menu: (@Composable () -> Unit)?) {
    val id = job.id.value
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val tap = onEdit?.let { Modifier.clickable(enabled = !state.busy, onClickLabel = stringResource(R.string.job_form_edit), onClick = it) } ?: Modifier
    Column(Modifier.fillMaxWidth().testTag("job-$id")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).then(tap).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val (label, tone) = when (job.state) {
                        JobState.SCHEDULED -> R.string.job_state_scheduled to MaterialTheme.colorScheme.primary
                        JobState.PAUSED -> R.string.job_state_paused to Hermes.colors.textTertiary
                        JobState.ERROR -> R.string.job_state_error to MaterialTheme.colorScheme.error
                        JobState.COMPLETED -> R.string.job_state_completed to quiet
                        JobState.UNKNOWN -> R.string.job_state_unknown to quiet
                    }
                    Text(stringResource(label), Modifier.padding(start = 8.dp).testTag("job-state-$id"),
                         style = MaterialTheme.typography.bodySmall, color = tone)
                }
                if (job.prompt.isNotBlank() && job.prompt != job.name) {
                    Text(job.prompt, style = MaterialTheme.typography.bodySmall, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val schedule = listOfNotNull(job.schedule, job.repeat?.let { stringResource(R.string.job_repeat, it) }).joinToString(" · ")
                Text(schedule, style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont), color = quiet)
                val outcome = when (job.last?.outcome) {
                    RunOutcome.OK -> stringResource(R.string.job_outcome_ok)
                    RunOutcome.FAILED -> stringResource(R.string.job_outcome_failed)
                    RunOutcome.DELIVERY_FAILED -> stringResource(R.string.job_outcome_not_delivered)
                    RunOutcome.OTHER -> job.last?.word  // the core's own word, e.g. blocked_config
                    null -> null
                }
                val times = listOfNotNull(
                    job.nextRunAt?.takeIf { job.state == JobState.SCHEDULED }?.let { stringResource(R.string.job_next, wallTime(it)) },
                    job.last?.at?.let { at -> listOfNotNull(stringResource(R.string.job_last, wallTime(at)), outcome).joinToString(" · ") },
                )
                if (times.isNotEmpty()) Text(times.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = quiet)
                if (job.id in state.queued) {
                    Text(stringResource(R.string.job_queued), Modifier.testTag("job-queued-$id"), style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.primary)
                }
                job.last?.error?.let {
                    Text(it, Modifier.testTag("job-error-$id"), style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            menu?.invoke()
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
}

/**
 * A row's ⋮ button and its menu, as the drawer's row menu and Desktop's sidebar: Run now while offered (F1), Pause for
 * a job that could still fire, Resume for a paused one (V5), Delete always. The controller checks each again.
 */
@Composable
private fun JobMenu(job: Job, state: JobsUi, open: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, actions: JobRowActions) {
    val id = job.id.value
    Box(Modifier.padding(top = 4.dp)) {
        IconButton(onOpen, Modifier.testTag("job-menu-$id"), enabled = !state.busy) {
            Icon(HermesIcons.More, stringResource(R.string.job_actions), Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
            // Each item closes the menu first, then acts.
            if (state.offersRunNow(job)) MenuItem("job-run-now", R.string.job_run_now, HermesIcons.Bolt) { onDismiss(); actions.runNow(job.id) }
            if (job.firing) MenuItem("job-pause", R.string.job_pause, HermesIcons.Pause) { onDismiss(); actions.pause(job.id) }
            if (job.state == JobState.PAUSED) MenuItem("job-resume", R.string.job_resume, HermesIcons.Play) { onDismiss(); actions.resume(job.id) }
            MenuItem("job-delete", R.string.job_delete, HermesIcons.Trash, MaterialTheme.colorScheme.error) { onDismiss(); actions.askDelete(job.id) }
        }
    }
}

@Composable
private fun MenuItem(tag: String, label: Int, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label), color = tint) }, onClick = onClick, Modifier.testTag(tag),
                     leadingIcon = { Icon(icon, null, Modifier.size(16.dp), tint = tint) })
}

/** Asks before a job is deleted, in Desktop's words and the job's name; only its Delete removes the job (invariant 4). */
@Composable
private fun DeleteDialog(name: String, busy: Boolean, actions: JobRowActions) {
    AlertDialog(onDismissRequest = { actions.askDelete(null) }, modifier = Modifier.testTag("job-delete-dialog"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                title = { Text(stringResource(R.string.job_delete_title)) },
                text = { Text(stringResource(R.string.job_delete_body, name)) },
                confirmButton = { PrimaryButton(stringResource(R.string.job_delete), actions.confirmDelete,
                                                Modifier.testTag("job-delete-confirm"), enabled = !busy) },
                dismissButton = { SecondaryButton(stringResource(R.string.job_form_cancel), { actions.askDelete(null) },
                                                  Modifier.testTag("job-delete-cancel"), enabled = !busy) })
}
