package com.nousresearch.hermes.android.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R

/** The app's settings kept in the core's config; today, who approves the agent's risky commands. */
@Composable
fun SettingsScreen(state: SettingsState, onApprovals: (ApprovalMode) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("settings"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClose, Modifier.testTag("settings-close")) { Text(stringResource(R.string.ssh_done)) }
        }
        Text(stringResource(R.string.approvals_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.approvals_explain), style = MaterialTheme.typography.bodySmall)
        ApprovalMode.entries.forEach { mode ->
            val (label, detail) = when (mode) {
                ApprovalMode.MANUAL -> R.string.approvals_manual to R.string.approvals_manual_detail
                ApprovalMode.SMART -> R.string.approvals_smart to R.string.approvals_smart_detail
                ApprovalMode.OFF -> R.string.approvals_off to R.string.approvals_off_detail
            }
            Row(Modifier.fillMaxWidth().testTag("approvals-${mode.wire}")
                    .selectable(mode == state.approvals, enabled = !state.busy, role = Role.RadioButton) { onApprovals(mode) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == state.approvals, onClick = null, enabled = !state.busy)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(stringResource(label))
                    Text(stringResource(detail), style = MaterialTheme.typography.bodySmall,
                         color = if (mode == ApprovalMode.OFF) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.error?.let { Text(it, Modifier.testTag("settings-error"), color = MaterialTheme.colorScheme.error) }
    }
}
