package com.nousresearch.hermes.android.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * The Scheduled jobs page (automation-01, automation-10): why the jobs will not run, when they will not, then every
 * job the core holds with its schedule, state, next and last run and last error. [onOpenGateway] opens the Gateway
 * page over this one; the rows take no tap yet.
 */
@Composable
fun JobsScreen(state: JobsUi, onOpenGateway: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Page(stringResource(R.string.jobs_title), modifier.testTag("jobs"), onBack = onClose, backTag = "jobs-close", busy = state.busy) {
        state.warning?.let { Warning(it, onOpenGateway) }
        Hint(stringResource(R.string.jobs_explain))
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("jobs-error"), color = MaterialTheme.colorScheme.error) }
        if (!state.busy && state.jobs.isEmpty() && state.error == null) {
            Hint(stringResource(R.string.jobs_empty), Modifier.padding(top = 12.dp).testTag("jobs-empty"))
        }
        Column(Modifier.padding(top = 8.dp)) { state.jobs.forEach { JobRow(it) } }
    }
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

/** One job, as the core lists it; not tappable yet (its actions come with J7). */
@Composable
private fun JobRow(job: Job) {
    val id = job.id.value
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().testTag("job-$id")) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            job.last?.error?.let {
                Text(it, Modifier.testTag("job-error-$id"), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
}
