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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.SectionCaption
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Hermes

/**
 * The optional always-on gateway (D12, D23): on/off from what runs, start at boot, and on Samsung phones
 * the system setting the app cannot change for the user ("Never sleeping apps").
 */
@Composable
fun GatewayScreen(state: GatewayUi, samsung: Boolean, onOn: () -> Unit, onOff: () -> Unit,
                  onStartAtBoot: (Boolean) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
                  telegram: TelegramState? = null, onTelegramSave: (ByteArray?, String) -> Unit = { _, _ -> },
                  onRestart: () -> Unit = {}) {
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
        }
        val tone = when (state.status) {
            GatewayStatus.RUNNING -> MaterialTheme.colorScheme.primary
            GatewayStatus.FAILED -> MaterialTheme.colorScheme.error
            GatewayStatus.STOPPED -> Hermes.colors.textTertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(tone, CircleShape))
            Text(listOfNotNull(stringResource(status), state.reason).joinToString(" "),
                 Modifier.weight(1f).padding(start = 10.dp).testTag("gateway-status-${state.status.name.lowercase()}"),
                 style = MaterialTheme.typography.bodyMedium, color = if (state.status == GatewayStatus.FAILED) tone else MaterialTheme.colorScheme.onSurface)
            // A running gateway reads its token, allowlist and settings only at its start.
            if (state.status == GatewayStatus.RUNNING) {
                TextButton(onRestart, Modifier.testTag("gateway-restart")) { Text(stringResource(R.string.gateway_restart)) }
            }
        }
        HorizontalDivider(color = Hermes.colors.stroke)
        Setting(stringResource(R.string.gateway_boot), state.startAtBoot, "gateway-boot", onStartAtBoot)
        Hint(stringResource(R.string.gateway_battery_hint), Modifier.padding(top = 12.dp))
        if (samsung) Hint(stringResource(R.string.gateway_samsung_hint), Modifier.padding(top = 8.dp).testTag("gateway-samsung-hint"))
        telegram?.let { TelegramSection(it, onTelegramSave) }
    }
}

/** The phone's own bot (D11, D24): its token (masked, kept only until saved) and the one user allowed to talk to it. */
@Composable
private fun TelegramSection(state: TelegramState, onSave: (ByteArray?, String) -> Unit) {
    SectionCaption(stringResource(R.string.telegram_title), Modifier.padding(top = 12.dp))
    if (!state.sdk) {
        Hint(stringResource(R.string.telegram_no_sdk), Modifier.testTag("telegram-no-sdk"), color = MaterialTheme.colorScheme.error)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.bot != null) {
            Text(stringResource(R.string.telegram_connected, state.bot, state.allowedUser.orEmpty()), Modifier.testTag("telegram-bot"),
                 style = MaterialTheme.typography.bodyLarge)
        } else {
            Hint(stringResource(R.string.telegram_hint))
        }
        var token by remember { mutableStateOf("") }  // never saved state: a secret
        var user by rememberSaveable(state.allowedUser) { mutableStateOf(state.allowedUser.orEmpty()) }
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().testTag("telegram-token"), singleLine = true,
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
    }
}

@Composable
private fun Setting(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange, Modifier.testTag(tag))
    }
}
