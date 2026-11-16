package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.setup.CLOCK_TICK_MS
import com.nousresearch.hermes.android.setup.ModelRow
import com.nousresearch.hermes.android.setup.asOf
import com.nousresearch.hermes.android.setup.detailOf
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.ListRow
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.SectionCaption
import kotlinx.coroutines.delay

/**
 * The chat's model picker as a bottom sheet over the chat (models-08, Desktop's "Switch model"): one list of every
 * signed-in provider's models under each provider's name, each with what Setup's picker shows (price, "PRO", its reset),
 * the current model checked (D9); the field narrows it across providers, or takes a model id after "Add custom model…".
 * A pick hands its row to [actions]; a locked row explains itself and picks nothing. The core's answer shows here: a
 * pick kept for the next message with Done (D7), a confirm in the core's words, a refusal with the list kept. "Add
 * provider" opens Setup, where the default is set. [chatModel] is the pill's model, the heading while no row is
 * current; reset times are read against [now], again for each list and every minute (N12). Closed by Back or a swipe
 * (D10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSwitchSheet(ui: PickerUi, actions: ModelActions, chatModel: String?, now: () -> Long = System::currentTimeMillis) {
    val clock by rememberUpdatedState(now)
    var nowMs by remember(ui.catalog) { mutableLongStateOf(clock()) }
    LaunchedEffect(ui.catalog) {
        while (true) {
            delay(CLOCK_TICK_MS)
            nowMs = clock()
        }
    }
    var explained by remember(ui.catalog) { mutableStateOf<String?>(null) }  // a locked row the user tapped
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = actions.close, modifier = Modifier.testTag("models-sheet"), sheetState = sheet,
                     containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.models_title), style = MaterialTheme.typography.titleMedium)
            val current = ui.catalog?.rows?.firstOrNull { it.current }
            Text(current?.let { stringResource(R.string.models_current_on, it.model, it.providerName) }
                     ?: stringResource(R.string.models_current, chatModel ?: stringResource(R.string.models_unknown)),
                 Modifier.padding(top = 4.dp, bottom = 12.dp).testTag("models-current"),
                 style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFamily = CodeFont)),
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(ui.query, actions.query, Modifier.fillMaxWidth().testTag("models-search"), singleLine = true,
                              label = { Text(stringResource(if (ui.adding) R.string.setup_custom_model_hint else R.string.models_search)) },
                              keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
            if (ui.switching != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("models-switching"))
            ui.failed?.let { words ->
                Column(Modifier.padding(top = 8.dp).testTag("models-failed")) {
                    Text(stringResource(R.string.models_switch_failed), style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.error)
                    if (words.isNotBlank()) Hint(words)
                }
            }
            ui.next?.let {
                Hint(stringResource(R.string.models_deferred, it), Modifier.padding(top = 8.dp).testTag("models-deferred"))
                PrimaryButton(stringResource(R.string.setup_done), actions.close, Modifier.padding(vertical = 8.dp).testTag("models-done"))
            }
            val catalog = ui.catalog
            when {
                catalog == null && ui.loadFailed == null ->
                    Hint(stringResource(R.string.models_loading), Modifier.padding(vertical = 12.dp).testTag("models-loading"))
                catalog == null -> Column(Modifier.padding(vertical = 12.dp).testTag("models-load-failed")) {
                    Text(stringResource(R.string.models_load_failed), style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.error)
                    ui.loadFailed?.let { Hint(it) }
                }
                catalog.rows.isEmpty() ->
                    Hint(stringResource(R.string.models_no_providers), Modifier.padding(vertical = 12.dp).testTag("models-no-providers"))
                else -> {
                    val shown = catalog.filtered(ui.query)
                    val typed = if (ui.adding || shown.isEmpty()) catalog.typed(ui.query) else emptyList()
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        // One section per provider, in the list's order (the chat's provider first).
                        shown.groupBy { it.provider }.forEach { (slug, rows) ->
                            item(key = "provider-$slug") { SectionCaption(rows.first().providerName) }
                            items(rows, key = { "model-${it.key}" }) { row ->
                                ModelLine(row, nowMs, onPick = { if (row.facts.locked) explained = row.key else actions.pick(row) })
                                if (row.key == explained || row.key == ui.locked) {
                                    Hint(stringResource(R.string.setup_pro_needs), Modifier.padding(bottom = 8.dp).testTag("pro-explained"))
                                }
                            }
                        }
                        if (typed.isNotEmpty()) {
                            item(key = "typed") { SectionCaption(stringResource(R.string.setup_custom_model)) }
                            items(typed, key = { "typed-${it.key}" }) { row ->
                                ListRow(row.model, Modifier.testTag("typed-${row.key}"), detail = row.providerName) { actions.pick(row) }
                            }
                        }
                        if (shown.isEmpty() && typed.isEmpty()) {
                            item(key = "none") {
                                Hint(stringResource(R.string.models_none), Modifier.padding(vertical = 12.dp).testTag("models-none"))
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                TextButton(actions.addCustom, Modifier.testTag("models-add-custom")) { Text(stringResource(R.string.setup_add_custom_model)) }
                TextButton(actions.addProvider, Modifier.testTag("models-add-provider")) { Text(stringResource(R.string.models_add_provider)) }
            }
        }
    }
    ui.confirm?.let { asked ->
        AlertDialog(onDismissRequest = actions.keep, modifier = Modifier.testTag("models-confirm"),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    title = { Text(stringResource(R.string.models_confirm_title, asked.row.model)) },
                    text = { Text(asked.message.ifBlank { stringResource(R.string.models_confirm_body) }) },
                    confirmButton = { PrimaryButton(stringResource(R.string.models_confirm), actions.confirm, Modifier.testTag("models-confirm-yes")) },
                    dismissButton = { SecondaryButton(stringResource(R.string.models_keep), actions.keep, Modifier.testTag("models-keep")) })
    }
}

/**
 * One model of the list: its id, then Setup's detail (its price, its reset as of [nowMs]) or "Custom model"; "PRO" when
 * the account cannot use it, else a check when the chat runs it. [onPick] hears a tap, a locked row's included (the
 * caller decides).
 */
@Composable
private fun ModelLine(row: ModelRow, nowMs: Long, onPick: () -> Unit) {
    ListRow(row.model, Modifier.testTag("model-${row.key}"),
            detail = if (row.custom) stringResource(R.string.setup_custom_model) else detailOf(row.facts.asOf(nowMs), nowMs),
            trailing = when {
                row.facts.locked -> {
                    {
                        Text(stringResource(R.string.setup_pro).uppercase(), Modifier.testTag("pro-${row.key}"),
                             style = MaterialTheme.typography.labelSmall, color = Hermes.colors.textTertiary)
                    }
                }
                row.current -> {
                    {
                        Icon(HermesIcons.Check, stringResource(R.string.models_current_mark), Modifier.size(16.dp).testTag("current-${row.key}"),
                             tint = MaterialTheme.colorScheme.primary)
                    }
                }
                else -> null
            },
            onClick = onPick)
}
