package com.nousresearch.hermes.android.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R

/** The SSH host the terminal runs on (D22): host form, host-key fingerprint to confirm, public key to install. */
@Composable
fun SshScreen(state: SshState, onLook: (SshTarget) -> Unit, onTrust: () -> Unit, onCheck: () -> Unit,
              onCopy: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("ssh"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ssh_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClose, Modifier.testTag("ssh-close")) { Text(stringResource(R.string.ssh_done)) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, Modifier.testTag("ssh-error"), color = MaterialTheme.colorScheme.error) }
        when (val step = state.step) {
            SshStep.Host -> HostForm(enabled = !state.busy, onLook)
            is SshStep.Fingerprint -> {
                Text(stringResource(R.string.ssh_fingerprint_intro, "${step.target.host}:${step.target.port}"))
                Text(step.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.ssh_fingerprint_compare), style = MaterialTheme.typography.bodySmall)
                Button(onTrust, Modifier.testTag("ssh-trust"), enabled = !state.busy) { Text(stringResource(R.string.ssh_trust)) }
            }
            is SshStep.Ready -> {
                Text(stringResource(R.string.ssh_ready, "${step.target.user}@${step.target.host}:${step.target.port}"))
                Text(stringResource(R.string.ssh_public_key_hint))
                Text(step.publicKey, Modifier.testTag("ssh-public-key"), fontFamily = FontFamily.Monospace,
                     style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onCopy(step.publicKey) }, Modifier.testTag("ssh-copy")) { Text(stringResource(R.string.ssh_copy)) }
                    Button(onCheck, Modifier.testTag("ssh-check"), enabled = !state.busy) { Text(stringResource(R.string.ssh_check)) }
                }
                if (step.loginOk) Text(stringResource(R.string.ssh_login_ok), Modifier.testTag("ssh-login-ok"))
            }
        }
    }
}

@Composable
private fun HostForm(enabled: Boolean, onLook: (SshTarget) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    val portNumber = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth().testTag("ssh-host"), singleLine = true,
                      label = { Text(stringResource(R.string.ssh_host)) },
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
    OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth().testTag("ssh-user"), singleLine = true,
                      label = { Text(stringResource(R.string.ssh_user)) },
                      keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
    OutlinedTextField(port, { port = it }, Modifier.fillMaxWidth().testTag("ssh-port"), singleLine = true,
                      label = { Text(stringResource(R.string.ssh_port)) },
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    Button({ onLook(SshTarget(host.trim(), user.trim(), portNumber!!)) }, Modifier.testTag("ssh-look"),
           enabled = enabled && host.isNotBlank() && user.isNotBlank() && portNumber != null) { Text(stringResource(R.string.ssh_look)) }
}
