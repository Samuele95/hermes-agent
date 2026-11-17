package com.nousresearch.hermes.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Page

/** What the page hands over (capabilities-10): [query] narrows the list, [set] turns toolsets on or off, [close] leaves. */
class ToolsetsActions(val query: (String) -> Unit, val set: (names: List<String>, on: Boolean) -> Unit, val close: () -> Unit)

/**
 * The toolsets the phone can switch (batch-4 bet 12, F2): each with its title, description, "N tools", "Needs keys"
 * when the core lacks its keys, and its switch, which says what a flip does. It shows only what the core last
 * answered; while a switch is written, every switch is off. Changes reach new chats only, and the page says so.
 */
@Composable
fun ToolsetsScreen(state: ToolsetsUi, actions: ToolsetsActions, modifier: Modifier = Modifier) {
    BackHandler(onBack = actions.close)
    Page(stringResource(R.string.toolsets_title), modifier.testTag("toolsets"), onBack = actions.close, backTag = "toolsets-close",
         busy = state.busy) {
        Hint(stringResource(R.string.toolsets_explain))
        Hint(stringResource(R.string.settings_new_chats), Modifier.padding(top = 8.dp).testTag("toolsets-new-chats"))
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("toolsets-error"), color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(state.query, actions.query, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("toolsets-search"),
                          singleLine = true, label = { Text(stringResource(R.string.toolsets_search)) },
                          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        state.shown()?.let { shown ->
            when {
                state.rows.isNullOrEmpty() -> Hint(stringResource(R.string.toolsets_none), Modifier.padding(top = 12.dp).testTag("toolsets-none"))
                shown.isEmpty() -> Hint(stringResource(R.string.skills_no_match, state.query.trim()),
                                        Modifier.padding(top = 12.dp).testTag("toolsets-no-match"))
                else -> Column(Modifier.padding(top = 8.dp)) {
                    shown.forEach { ToolsetLine(it, enabled = !state.busy, onSet = actions.set, onOpen = null) }
                }
            }
        }
    }
}

/** One toolset's row: its text (which opens it, with [onOpen]) and its switch, labelled with what a flip does (Desktop's `toggleToolset`). */
@Composable
private fun ToolsetLine(row: ToolsetRow, enabled: Boolean, onSet: (List<String>, Boolean) -> Unit, onOpen: (() -> Unit)?) {
    val switchLabel = stringResource(if (row.enabled) R.string.toolsets_turn_off else R.string.toolsets_turn_on, row.label)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("toolset-${row.name}"), verticalAlignment = Alignment.CenterVertically) {
        val text = Modifier.weight(1f)
        Column(if (onOpen == null) text else text.testTag("toolset-open-${row.name}").clickable(enabled = enabled, onClick = onOpen)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge)
            if (row.description.isNotBlank()) {
                Text(row.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                     maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.toolsets_count, row.toolCount.toInt(), row.toolCount.toInt()),
                     Modifier.testTag("toolset-count-${row.name}"), style = MaterialTheme.typography.labelSmall, color = Hermes.colors.textTertiary)
                if (row.needsKeys) Pill(stringResource(R.string.toolsets_needs_keys), "toolset-needs-keys-${row.name}", MaterialTheme.colorScheme.error)
            }
        }
        Switch(row.enabled, { on -> onSet(listOf(row.name), on) },
               Modifier.testTag("toolset-switch-${row.name}").semantics { contentDescription = switchLabel }, enabled = enabled)
    }
    HorizontalDivider(color = Hermes.colors.stroke)
}
