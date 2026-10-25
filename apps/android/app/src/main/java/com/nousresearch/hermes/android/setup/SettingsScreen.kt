package com.nousresearch.hermes.android.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.SectionCaption
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Hermes
import androidx.compose.material3.HorizontalDivider

/** The app's settings kept in the core's config; today, who approves the agent's risky commands. */
@Composable
fun SettingsScreen(state: SettingsState, onApprovals: (ApprovalMode) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Page(stringResource(R.string.settings_title), modifier.testTag("settings"), onBack = onClose, backTag = "settings-close", busy = state.busy) {
        SectionCaption(stringResource(R.string.approvals_title), Modifier.padding(top = 0.dp))
        Hint(stringResource(R.string.approvals_explain))
        ApprovalMode.entries.forEach { mode ->
            val (label, detail) = when (mode) {
                ApprovalMode.MANUAL -> R.string.approvals_manual to R.string.approvals_manual_detail
                ApprovalMode.SMART -> R.string.approvals_smart to R.string.approvals_smart_detail
                ApprovalMode.OFF -> R.string.approvals_off to R.string.approvals_off_detail
            }
            Row(Modifier.fillMaxWidth().testTag("approvals-${mode.wire}")
                    .selectable(mode == state.approvals, enabled = !state.busy, role = Role.RadioButton) { onApprovals(mode) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(mode == state.approvals, onClick = null, enabled = !state.busy)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(detail), style = MaterialTheme.typography.bodySmall,
                         color = if (mode == ApprovalMode.OFF) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = Hermes.colors.stroke)
        }
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("settings-error"), color = MaterialTheme.colorScheme.error) }
    }
}
