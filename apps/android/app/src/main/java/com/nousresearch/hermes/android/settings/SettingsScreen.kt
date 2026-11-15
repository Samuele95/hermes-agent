package com.nousresearch.hermes.android.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Chevron
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.ListRow
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.SectionCaption
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Hermes
import androidx.compose.material3.HorizontalDivider

/** What the page hands over; each is one write or one ask (§2.4). */
class SettingsActions(val approvals: (ApprovalMode) -> Unit, val personality: (String) -> Unit, val edit: (SettingsEdit) -> Unit,
                      val askRemove: (String?) -> Unit, val confirmRemove: () -> Unit, val restartGateway: () -> Unit,
                      val askRemoveProvider: (String?) -> Unit, val confirmRemoveProvider: () -> Unit)

/**
 * The app's settings kept in the core's config, in the order of §4: Chat (the personality), Command approvals (the
 * mode, the timeout, the always-allowed commands), Memory, and Providers (each connected one with Remove, which asks
 * first). It shows only what the core last answered: a part the
 * core could not read is absent (the mode keeps "none selected, still choosable"), and while one write runs every
 * control is off.
 */
@Composable
fun SettingsScreen(state: SettingsUi, actions: SettingsActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Page(stringResource(R.string.settings_title), modifier.testTag("settings"), onBack = onClose, backTag = "settings-close", busy = state.busy) {
        val config = state.config
        val enabled = !state.busy
        if (config != null && state.personality != null) {
            SectionCaption(stringResource(R.string.settings_chat), Modifier.padding(top = 0.dp))
            PersonalityRow(state.personality, config.personalities, enabled, actions.personality)
        }
        SectionCaption(stringResource(R.string.approvals_title), Modifier.padding(top = 0.dp))
        Hint(stringResource(R.string.approvals_explain))
        ApprovalMode.entries.forEach { mode ->
            val (label, detail) = when (mode) {
                ApprovalMode.MANUAL -> R.string.approvals_manual to R.string.approvals_manual_detail
                ApprovalMode.SMART -> R.string.approvals_smart to R.string.approvals_smart_detail
                ApprovalMode.OFF -> R.string.approvals_off to R.string.approvals_off_detail
            }
            Row(Modifier.fillMaxWidth().testTag("approvals-${mode.wire}")
                    .selectable(mode == state.approvals, enabled = !state.busy, role = Role.RadioButton) { actions.approvals(mode) }
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
        if (config != null) {
            NumberSetting(stringResource(R.string.approvals_timeout), stringResource(R.string.approvals_timeout_explain),
                          stringResource(R.string.unit_seconds), config.approvalTimeoutS, "approvals-timeout", enabled) {
                actions.edit(SettingsEdit(approvalTimeoutS = it))
            }
            Allowlist(config.allowlist, state.nextStart, enabled, actions)
            SectionCaption(stringResource(R.string.memory_title))
            SwitchSetting(stringResource(R.string.memory_enabled), stringResource(R.string.memory_enabled_detail), config.memoryEnabled,
                          "memory-switch", enabled) { actions.edit(SettingsEdit(memoryEnabled = it)) }
            SwitchSetting(stringResource(R.string.profile_enabled), stringResource(R.string.profile_enabled_detail), config.profileEnabled,
                          "profile-switch", enabled) { actions.edit(SettingsEdit(profileEnabled = it)) }
            NumberSetting(stringResource(R.string.memory_chars), null, stringResource(R.string.unit_characters), config.memoryChars,
                          "memory-chars", enabled) { actions.edit(SettingsEdit(memoryChars = it)) }
            NumberSetting(stringResource(R.string.profile_chars), null, stringResource(R.string.unit_characters), config.profileChars,
                          "profile-chars", enabled) { actions.edit(SettingsEdit(profileChars = it)) }
            Hint(stringResource(R.string.settings_new_chats), Modifier.padding(top = 12.dp).testTag("settings-new-chats"))
        }
        state.connected?.let { Providers(it, enabled, actions) }
        state.error?.let { Hint(it, Modifier.padding(top = 12.dp).testTag("settings-error"), color = MaterialTheme.colorScheme.error) }
    }
    state.confirmRemove?.let { RemoveDialog(it, enabled = !state.busy, actions) }
    state.confirmProvider?.let { slug ->
        state.connected?.firstOrNull { it.slug == slug }?.let { ProviderRemoveDialog(it, enabled = !state.busy, actions) }
    }
}

/** The core's word for a personality as the page shows it: [NO_PERSONALITY] reads "None", a name its first letter upper-cased (Desktop's `prettyName`). */
@Composable
private fun personalityLabel(name: String): String =
    if (name == NO_PERSONALITY) stringResource(R.string.personality_none) else name.replaceFirstChar { it.uppercaseChar() }

/** The personality row (D7): the core's choice, and a tap opens "None" then the core's list, each with its preview line. */
@Composable
private fun PersonalityRow(current: String, offered: List<Personality>, enabled: Boolean, pick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ListRow(stringResource(R.string.personality_label), Modifier.testTag("personality"),
                detail = stringResource(R.string.personality_explain), enabled = enabled,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(personalityLabel(current), Modifier.padding(end = 4.dp), style = MaterialTheme.typography.bodyMedium,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Chevron()
                    }
                }) { open = true }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            val chosen = { name: String -> open = false; pick(name) }
            val mark: @Composable (String) -> Unit = { name ->
                if (name == current) Icon(HermesIcons.Check, null, Modifier.size(16.dp))
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.personality_none)) }, onClick = { chosen(NO_PERSONALITY) },
                             Modifier.testTag("personality-none"), trailingIcon = { mark(NO_PERSONALITY) })
            offered.forEach { p ->
                DropdownMenuItem(text = {
                                     Column {
                                         Text(personalityLabel(p.name))
                                         Text(p.description, style = MaterialTheme.typography.bodySmall,
                                              color = MaterialTheme.colorScheme.onSurfaceVariant)
                                     }
                                 }, onClick = { chosen(p.name) }, Modifier.testTag("personality-${p.name}"),
                                 trailingIcon = { mark(p.name) })
            }
        }
    }
}

/** The always-allowed commands, each with Remove (asks first), and the gateway's note while a removal waits for its next start. */
@Composable
private fun Allowlist(allowed: List<String>, nextStart: Boolean, enabled: Boolean, actions: SettingsActions) {
    Text(stringResource(R.string.allowed_title), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
    Hint(stringResource(R.string.allowed_explain))
    if (allowed.isEmpty()) Hint(stringResource(R.string.allowed_none), Modifier.padding(top = 8.dp).testTag("allowed-none"))
    allowed.forEachIndexed { i, pattern ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(pattern, Modifier.weight(1f).testTag("allowed-$i"), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CodeFont))
            TextButton({ actions.askRemove(pattern) }, Modifier.testTag("allowed-remove-$i"), enabled = enabled) {
                Text(stringResource(R.string.allowed_remove))
            }
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
    if (nextStart) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Hint(stringResource(R.string.allowed_next_start), Modifier.weight(1f).testTag("settings-next-start"))
            TextButton(actions.restartGateway, Modifier.testTag("settings-restart")) { Text(stringResource(R.string.gateway_restart)) }
        }
    }
}

/**
 * The providers Hermes holds a key or sign-in for, in the core's order: the one in use marked, each with Remove (asks
 * first), except a saved custom endpoint, which is deleted in provider setup (D3).
 */
@Composable
private fun Providers(connected: List<ConnectedProvider>, enabled: Boolean, actions: SettingsActions) {
    SectionCaption(stringResource(R.string.providers_title), Modifier.testTag("providers"))
    Hint(stringResource(R.string.providers_explain))
    if (connected.isEmpty()) Hint(stringResource(R.string.providers_none), Modifier.padding(top = 8.dp).testTag("providers-none"))
    connected.forEach { p ->
        Row(Modifier.fillMaxWidth().testTag("provider-${p.slug}"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                if (p.current) {
                    Text(stringResource(R.string.providers_in_use), Modifier.testTag("provider-current-${p.slug}"),
                         style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (!p.removable) Hint(stringResource(R.string.providers_managed), Modifier.testTag("provider-managed-${p.slug}"))
            }
            if (p.removable) {
                val label = stringResource(R.string.providers_remove_label, p.name)
                TextButton({ actions.askRemoveProvider(p.slug) },
                           Modifier.testTag("provider-remove-${p.slug}").semantics { contentDescription = label }, enabled = enabled) {
                    Text(stringResource(R.string.providers_remove))
                }
            }
        }
        HorizontalDivider(color = Hermes.colors.stroke)
    }
}

/** A switch with its quieter line beneath the label. */
@Composable
private fun SwitchSetting(label: String, detail: String, checked: Boolean, tag: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, Modifier.testTag(tag), enabled = enabled)
    }
}

/**
 * A whole number the core holds ([value]): the field keeps every digit typed (nothing cut, no bound: the bridge judges
 * it, D5), and Done or the check saves a whole number other than [value]. A blank or overflowing text shows red and
 * cannot be saved. The text starts again from the core's value whenever the re-read changes it.
 */
@Composable
private fun NumberSetting(label: String, hint: String?, unit: String, value: Int, tag: String, enabled: Boolean, save: (Int) -> Unit) {
    var typed by remember(value) { mutableStateOf(value.toString()) }
    val number = typed.toIntOrNull()
    val ok = enabled && number != null && number != value
    val send = { if (ok) save(number!!) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        hint?.let { Hint(it) }
        OutlinedTextField(typed, { typed = it.filter(Char::isDigit) }, Modifier.fillMaxWidth().padding(top = 4.dp).testTag(tag),
                          enabled = enabled, singleLine = true, isError = number == null, suffix = { Text(unit) },
                          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                          keyboardActions = KeyboardActions(onDone = { send() }),
                          trailingIcon = {
                              IconButton(send, Modifier.testTag("$tag-save"), enabled = ok) {
                                  Icon(HermesIcons.Check, stringResource(R.string.settings_save))
                              }
                          })
    }
}

/** Asks before an always-allowed command is removed, naming it; both buttons are off while a write runs. */
@Composable
private fun RemoveDialog(pattern: String, enabled: Boolean, actions: SettingsActions) {
    AlertDialog(onDismissRequest = { actions.askRemove(null) }, modifier = Modifier.testTag("allowed-remove-dialog"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                title = { Text(stringResource(R.string.allowed_remove_title)) },
                text = { Text(stringResource(R.string.allowed_remove_body, pattern)) },
                confirmButton = { PrimaryButton(stringResource(R.string.allowed_remove_confirm), actions.confirmRemove,
                                                Modifier.testTag("allowed-remove-confirm"), enabled = enabled) },
                dismissButton = { SecondaryButton(stringResource(R.string.job_form_cancel), { actions.askRemove(null) },
                                                  Modifier.testTag("allowed-remove-cancel"), enabled = enabled) })
}

/** Asks before a provider is removed, naming it, and says what removing the one in use does (D9); both buttons are off while it runs. */
@Composable
private fun ProviderRemoveDialog(provider: ConnectedProvider, enabled: Boolean, actions: SettingsActions) {
    AlertDialog(onDismissRequest = { actions.askRemoveProvider(null) }, modifier = Modifier.testTag("provider-remove-dialog"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                title = { Text(stringResource(R.string.providers_remove_title, provider.name)) },
                text = {
                    Column {
                        Text(stringResource(R.string.providers_remove_body))
                        if (provider.current) {
                            Text(stringResource(R.string.providers_remove_current), Modifier.padding(top = 8.dp).testTag("provider-remove-current"))
                        }
                    }
                },
                confirmButton = { PrimaryButton(stringResource(R.string.providers_remove_confirm), actions.confirmRemoveProvider,
                                                Modifier.testTag("provider-remove-confirm"), enabled = enabled) },
                dismissButton = { SecondaryButton(stringResource(R.string.job_form_cancel), { actions.askRemoveProvider(null) },
                                                  Modifier.testTag("provider-remove-cancel"), enabled = enabled) })
}
