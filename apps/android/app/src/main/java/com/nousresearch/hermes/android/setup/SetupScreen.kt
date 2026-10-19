package com.nousresearch.hermes.android.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R

/** Provider setup: pick a provider, paste its key when it needs one, pick a model. Stateless but for the fields. */
@Composable
fun SetupScreen(setup: Setup, onPick: (String) -> Unit, onKey: (ByteArray) -> Unit, onModel: (String, Boolean) -> Unit,
                onBack: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, onAddEndpoint: () -> Unit = {},
                onEndpoint: (name: String, baseUrl: String, model: String, key: ByteArray?) -> Unit = { _, _, _, _ -> },
                onOpenUrl: (String) -> Unit = {}) {
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("setup"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (setup.step !is SetupStep.Loading && setup.step !is SetupStep.Providers) {
                TextButton(onBack, Modifier.testTag("setup-back"), enabled = !setup.busy) { Text(stringResource(R.string.setup_back)) }
            }
            Text(stringResource(R.string.setup_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClose, Modifier.testTag("setup-close")) { Text(stringResource(R.string.setup_later)) }
        }
        if (setup.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        setup.error?.let { Text(it, Modifier.testTag("setup-error"), color = MaterialTheme.colorScheme.error) }
        when (val step = setup.step) {
            SetupStep.Loading -> Unit
            is SetupStep.Providers -> LazyColumn(Modifier.weight(1f)) {
                item {
                    Choice(stringResource(R.string.setup_add_endpoint), stringResource(R.string.setup_add_endpoint_detail),
                           Modifier.testTag("add-endpoint"), onClick = onAddEndpoint)
                }
                items(step.choices, key = { it.slug }) { provider ->
                    Choice(provider.name, if (provider.ready) stringResource(R.string.setup_signed_in) else provider.keyEnv,
                           Modifier.testTag("provider-${provider.slug}")) { onPick(provider.slug) }
                }
            }
            is SetupStep.Key -> KeyField(step.provider, enabled = !setup.busy, onKey)
            is SetupStep.Models -> ModelPicker(step.provider, setup, onModel, Modifier.weight(1f))
            SetupStep.Endpoint -> EndpointForm(enabled = !setup.busy, onEndpoint)
            is SetupStep.SignIn -> SignIn(step, onOpenUrl)
        }
    }
}

@Composable
private fun KeyField(provider: ProviderChoice, enabled: Boolean, onKey: (ByteArray) -> Unit) {
    // remember, not rememberSaveable: saved state is written out with the task, and the key must not be.
    var key by remember(provider.slug) { mutableStateOf("") }
    Text(stringResource(R.string.setup_key_hint, provider.name, provider.keyEnv.orEmpty()))
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("key"), singleLine = true,
                      visualTransformation = PasswordVisualTransformation(),
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
    Button({ onKey(key.trim().toByteArray()); key = "" }, Modifier.testTag("key-save"), enabled = enabled && key.isNotBlank()) {
        Text(stringResource(R.string.setup_save_key))
    }
}

@Composable
private fun SignIn(step: SetupStep.SignIn, onOpenUrl: (String) -> Unit) {
    Text(stringResource(R.string.setup_sign_in, step.provider.name), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.setup_sign_in_code))
    Text(step.userCode, Modifier.testTag("signin-code"), style = MaterialTheme.typography.headlineMedium,
         fontFamily = FontFamily.Monospace)
    Button({ onOpenUrl(step.url) }, Modifier.testTag("signin-open")) { Text(stringResource(R.string.setup_sign_in_open)) }
    Text(stringResource(R.string.setup_sign_in_waiting), style = MaterialTheme.typography.bodySmall)
    LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun EndpointForm(enabled: Boolean, onEndpoint: (String, String, String, ByteArray?) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }  // never saved state, as in KeyField
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("endpoint-name"), singleLine = true,
                      label = { Text(stringResource(R.string.setup_endpoint_name)) })
    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("endpoint-url"), singleLine = true,
                      label = { Text(stringResource(R.string.setup_endpoint_url)) },
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth().testTag("endpoint-model"), singleLine = true,
                      label = { Text(stringResource(R.string.setup_model_name)) })
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("endpoint-key"), singleLine = true,
                      label = { Text(stringResource(R.string.setup_endpoint_key)) }, visualTransformation = PasswordVisualTransformation(),
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
    Button({ onEndpoint(name.trim(), url.trim(), model.trim(), key.trim().takeIf { it.isNotEmpty() }?.toByteArray()); key = "" },
           Modifier.testTag("endpoint-save"), enabled = enabled && listOf(name, url, model).all { it.isNotBlank() }) {
        Text(stringResource(R.string.setup_use_model))
    }
}

@Composable
private fun ModelPicker(provider: ProviderChoice, setup: Setup, onModel: (String, Boolean) -> Unit, modifier: Modifier) {
    setup.confirm?.let { confirm ->
        Text(confirm.message, Modifier.testTag("setup-confirm-message"))
        Button({ onModel(confirm.model, true) }, Modifier.testTag("setup-confirm"), enabled = !setup.busy) {
            Text(stringResource(R.string.setup_use_anyway))
        }
    }
    if (provider.models.isEmpty()) {
        // The core lists no models for this provider: the user names one.
        var typed by rememberSaveable(provider.slug) { mutableStateOf("") }
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().testTag("model-name"), singleLine = true,
                          label = { Text(stringResource(R.string.setup_model_name)) })
        Button({ onModel(typed.trim(), false) }, Modifier.testTag("model-use"), enabled = !setup.busy && typed.isNotBlank()) {
            Text(stringResource(R.string.setup_use_model))
        }
    } else {
        LazyColumn(modifier) {
            items(provider.models, key = { it }) { model ->
                Choice(model, null, Modifier.testTag("model-$model"), enabled = !setup.busy) { onModel(model, false) }
            }
        }
    }
}

@Composable
private fun Choice(title: String, detail: String?, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
    }
}
