package com.nousresearch.hermes.android.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.SectionCaption
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.CodeBox
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size

/** The SSH host the terminal runs on (D22): host form, host-key fingerprint to confirm, public key to install. */
@Composable
fun SshScreen(state: SshState, onLook: (SshTarget) -> Unit, onTrust: () -> Unit, onCheck: () -> Unit,
              onCopy: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, onRunOnPhone: () -> Unit = {}) {
    Page(stringResource(R.string.ssh_title), modifier.testTag("ssh"), onBack = onClose, backTag = "ssh-close", busy = state.busy) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { Hint(it, Modifier.testTag("ssh-error"), color = MaterialTheme.colorScheme.error) }
            // Where the terminal runs now, and the way back to the phone from a host.
            val remote = state.remote
            if (remote == null) {
                Hint(stringResource(R.string.ssh_runs_here), Modifier.testTag("ssh-runs-here"))
            } else {
                Text(stringResource(R.string.ssh_runs_on, remote), Modifier.testTag("ssh-runs-on"), style = MaterialTheme.typography.bodyLarge)
                SecondaryButton(stringResource(R.string.ssh_run_on_phone), onRunOnPhone, Modifier.testTag("ssh-local"), enabled = !state.busy)
            }
            SectionCaption(stringResource(R.string.ssh_host_section))
            when (val step = state.step) {
                SshStep.Host -> HostForm(enabled = !state.busy, onLook)
                is SshStep.Fingerprint -> {
                    Hint(stringResource(R.string.ssh_fingerprint_intro, "${step.target.host}:${step.target.port}"))
                    CodeBox(step.fingerprint)
                    Hint(stringResource(R.string.ssh_fingerprint_compare))
                    PrimaryButton(stringResource(R.string.ssh_trust), onTrust, Modifier.testTag("ssh-trust"), enabled = !state.busy)
                }
                is SshStep.Ready -> {
                    Text(stringResource(R.string.ssh_ready, "${step.target.user}@${step.target.host}:${step.target.port}"),
                         style = MaterialTheme.typography.bodyLarge)
                    Hint(stringResource(R.string.ssh_public_key_hint))
                    CodeBox(step.publicKey, Modifier.testTag("ssh-public-key"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.ssh_copy), { onCopy(step.publicKey) }, Modifier.testTag("ssh-copy"))
                        PrimaryButton(stringResource(R.string.ssh_check), onCheck, Modifier.testTag("ssh-check"), enabled = !state.busy)
                    }
                    if (step.loginOk) {
                        Row(Modifier.testTag("ssh-login-ok"), verticalAlignment = Alignment.CenterVertically) {
                            Icon(HermesIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.ssh_login_ok), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
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
    PrimaryButton(stringResource(R.string.ssh_look), { onLook(SshTarget(host.trim(), user.trim(), portNumber!!)) }, Modifier.testTag("ssh-look"),
                  enabled = enabled && host.isNotBlank() && user.isNotBlank() && portNumber != null)
}
