package com.nousresearch.hermes.android.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Chevron
import com.nousresearch.hermes.android.ui.ListRow
import com.nousresearch.hermes.android.ui.SectionCaption
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Hermes

/** What the bot's switch, Remove and its confirmation hand over (D39 F8, D40); a null holder shows none of them. */
class TelegramActions(val setEnabled: (Boolean) -> Unit, val askRemove: (Boolean) -> Unit, val confirmRemove: () -> Unit)

/**
 * The optional always-on gateway (D12, D23): on/off from what runs, start at boot, and on Samsung phones
 * the system setting the app cannot change for the user ("Never sleeping apps"). [onJobs] leads to the jobs it
 * runs (none when null). [onTelegram] drives the bot's switch and removal (none when null).
 */
@Composable
fun GatewayScreen(state: GatewayUi, samsung: Boolean, onOn: () -> Unit, onOff: () -> Unit,
                  onStartAtBoot: (Boolean) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
                  telegram: TelegramState? = null, onTelegramSave: (ByteArray?, String) -> Unit = { _, _ -> },
                  onRestart: () -> Unit = {}, onJobs: (() -> Unit)? = null, onTelegram: TelegramActions? = null) {
    Page(stringResource(R.string.gateway_screen_title), modifier.testTag("gateway"), onBack = onClose, backTag = "gateway-close") {
        Hint(stringResource(R.string.gateway_explain))
        val on = state.status in setOf(GatewayStatus.STARTING, GatewayStatus.RUNNING, GatewayStatus.RESTARTING)
        SectionCaption(stringResource(R.string.gateway_section))
        Setting(stringResource(R.string.gateway_switch), on, "gateway-switch") { if (it) onOn() else onOff() }
        val status = when (state.status) {
            GatewayStatus.STOPPED -> R.string.gateway_status_stopped
            GatewayStatus.STARTING -> R.string.gateway_status_starting
            GatewayStatus.RUNNING -> R.string.gateway_status_running
            GatewayStatus.RESTARTING -> R.string.gateway_status_restarting
            GatewayStatus.STOPPING -> R.string.gateway_status_stopping
            GatewayStatus.FAILED -> R.string.gateway_status_failed
            GatewayStatus.DIED -> R.string.gateway_status_died
        }
        val tone = when (state.status) {
            GatewayStatus.RUNNING -> MaterialTheme.colorScheme.primary
            GatewayStatus.FAILED, GatewayStatus.DIED -> MaterialTheme.colorScheme.error
            GatewayStatus.STOPPED -> Hermes.colors.textTertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tone, CircleShape))
            Text(listOfNotNull(stringResource(status), state.reason).joinToString(" "),
                 Modifier.weight(1f).padding(start = 10.dp).testTag("gateway-status-${state.status.name.lowercase()}"),
                 style = MaterialTheme.typography.bodyMedium, color = if (state.status in setOf(GatewayStatus.FAILED, GatewayStatus.DIED)) tone else MaterialTheme.colorScheme.onSurface)
            // A running gateway reads its token, allowlist and settings only at its start.
            if (state.status == GatewayStatus.RUNNING) {
                TextButton(onRestart, Modifier.testTag("gateway-restart")) { Text(stringResource(R.string.gateway_restart)) }
            }
        }
        HorizontalDivider(color = Hermes.colors.stroke)
        Setting(stringResource(R.string.gateway_boot), state.startAtBoot, "gateway-boot", onChange = onStartAtBoot)
        Hint(stringResource(R.string.gateway_battery_hint), Modifier.padding(top = 12.dp))
        if (samsung) Hint(stringResource(R.string.gateway_samsung_hint), Modifier.padding(top = 8.dp).testTag("gateway-samsung-hint"))
        onJobs?.let {
            SectionCaption(stringResource(R.string.jobs_title), Modifier.padding(top = 12.dp))
            ListRow(stringResource(R.string.gateway_jobs), Modifier.testTag("gateway-jobs"), detail = stringResource(R.string.gateway_jobs_detail),
                    trailing = { Chevron() }, onClick = it)
        }
        telegram?.let { TelegramSection(it, onTelegramSave, onTelegram, onRestart) }
    }
}

/**
 * The phone's own bot (D11, D24): what it is doing (the core's own entry only from the running gateway, D39 F3), its
 * switch (on only while a token is held, D40, else a way to the token field), a change waiting for the gateway's next
 * start with Restart, its token (masked, kept only until saved), the one user allowed to talk to it, and Remove the
 * bot after a confirmation.
 */
@Composable
private fun TelegramSection(state: TelegramState, onSave: (ByteArray?, String) -> Unit, onTelegram: TelegramActions?,
                            onRestart: () -> Unit) {
    SectionCaption(stringResource(R.string.telegram_title), Modifier.padding(top = 12.dp))
    if (!state.sdk) {
        Hint(stringResource(R.string.telegram_no_sdk), Modifier.testTag("telegram-no-sdk"), color = MaterialTheme.colorScheme.error)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.live?.let { LiveLine(it) }
        val tokenFocus = remember { FocusRequester() }
        if (onTelegram != null) {
            Setting(stringResource(R.string.telegram_switch), checked = state.enabled && state.tokenHeld, tag = "telegram-switch",
                    enabled = state.tokenHeld && !state.busy) { onTelegram.setEnabled(it) }
            if (!state.tokenHeld) {
                Hint(stringResource(R.string.telegram_needs_token), Modifier.testTag("telegram-needs-token"))
                TextButton(onClick = { tokenFocus.requestFocus() }, Modifier.testTag("telegram-add-token")) {
                    Text(stringResource(R.string.telegram_add_token))
                }
            }
        }
        if (state.nextStart) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Hint(stringResource(R.string.telegram_next_start), Modifier.weight(1f).testTag("telegram-next-start"))
                TextButton(onRestart, Modifier.testTag("telegram-restart")) { Text(stringResource(R.string.gateway_restart)) }
            }
        }
        if (state.bot != null) {
            Text(stringResource(R.string.telegram_connected, state.bot, state.allowedUser.orEmpty()), Modifier.testTag("telegram-bot"),
                 style = MaterialTheme.typography.bodyLarge)
        } else {
            Hint(stringResource(R.string.telegram_hint))
        }
        var token by remember { mutableStateOf("") }  // never saved state: a secret
        var user by rememberSaveable(state.allowedUser) { mutableStateOf(state.allowedUser.orEmpty()) }
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().focusRequester(tokenFocus).testTag("telegram-token"), singleLine = true,
                          label = { Text(stringResource(if (state.tokenHeld) R.string.telegram_token_replace else R.string.telegram_token)) },
                          visualTransformation = PasswordVisualTransformation(),
                          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        OutlinedTextField(user, { user = it.filter(Char::isDigit) }, Modifier.fillMaxWidth().testTag("telegram-user"), singleLine = true,
                          label = { Text(stringResource(R.string.telegram_user)) },
                          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        state.error?.let { Hint(it, Modifier.testTag("telegram-error"), color = MaterialTheme.colorScheme.error) }
        val ready = !state.busy && user.isNotBlank() && (token.isNotBlank() || state.tokenHeld)
        PrimaryButton(stringResource(R.string.telegram_save), { onSave(token.trim().takeIf { it.isNotEmpty() }?.toByteArray(), user); token = "" },
                      Modifier.testTag("telegram-save"), enabled = ready)
        Hint(stringResource(R.string.telegram_applies))
        if (onTelegram != null && state.tokenHeld) {
            SecondaryButton(stringResource(R.string.telegram_remove), { onTelegram.askRemove(true) }, Modifier.testTag("telegram-remove"),
                            enabled = !state.busy, color = MaterialTheme.colorScheme.error)
        }
    }
    if (onTelegram != null && state.confirmRemove) RemoveBotDialog(state.busy, onTelegram)
}

/** What the bot is doing: a dot and Desktop's word for its state, then the core's own words when its entry has some. */
@Composable
private fun LiveLine(live: PlatformLive) {
    val word = when (live.state) {
        PlatformState.CONNECTED -> R.string.telegram_live_connected
        PlatformState.CONNECTING -> R.string.telegram_live_connecting
        PlatformState.RETRYING -> R.string.telegram_live_retrying
        PlatformState.FATAL -> R.string.telegram_live_fatal
        PlatformState.PAUSED -> R.string.telegram_live_paused
        PlatformState.DISABLED -> R.string.telegram_live_disabled
        PlatformState.NOT_CONFIGURED -> R.string.telegram_live_not_configured
        PlatformState.GATEWAY_OFF -> R.string.telegram_live_gateway_off
        PlatformState.PENDING_RESTART -> R.string.telegram_live_pending_restart
        PlatformState.UNKNOWN -> R.string.telegram_live_unknown
    }
    val tone = when (live.state) {
        PlatformState.CONNECTED -> MaterialTheme.colorScheme.primary
        PlatformState.FATAL -> MaterialTheme.colorScheme.error
        PlatformState.DISABLED, PlatformState.NOT_CONFIGURED, PlatformState.GATEWAY_OFF -> Hermes.colors.textTertiary
        PlatformState.CONNECTING, PlatformState.RETRYING, PlatformState.PAUSED, PlatformState.PENDING_RESTART,
        PlatformState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(tone, CircleShape))
        Text(listOfNotNull(stringResource(word), live.message).joinToString(" "),
             Modifier.weight(1f).padding(start = 10.dp).testTag("telegram-live-${live.state.name.lowercase()}"),
             style = MaterialTheme.typography.bodyMedium,
             color = if (live.state == PlatformState.FATAL) tone else MaterialTheme.colorScheme.onSurface)
    }
}

/** Asks before the bot is removed; only its Remove switches the bot off and deletes the token (D40). */
@Composable
private fun RemoveBotDialog(busy: Boolean, actions: TelegramActions) {
    AlertDialog(onDismissRequest = { actions.askRemove(false) }, modifier = Modifier.testTag("telegram-remove-dialog"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                title = { Text(stringResource(R.string.telegram_remove_title)) },
                text = { Text(stringResource(R.string.telegram_remove_body)) },
                confirmButton = { PrimaryButton(stringResource(R.string.telegram_remove_confirm), actions.confirmRemove,
                                                Modifier.testTag("telegram-remove-confirm"), enabled = !busy) },
                dismissButton = { SecondaryButton(stringResource(R.string.telegram_cancel), { actions.askRemove(false) },
                                                  Modifier.testTag("telegram-remove-cancel"), enabled = !busy) })
}

@Composable
private fun Setting(label: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, Modifier.testTag(tag), enabled = enabled)
    }
}
