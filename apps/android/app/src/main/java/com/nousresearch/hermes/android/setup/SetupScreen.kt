package com.nousresearch.hermes.android.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.SectionCaption
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.ListRow
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.Chevron

/** Provider setup: pick a provider, paste its key when it needs one, pick a model. Stateless but for the fields. */
@Composable
fun SetupScreen(setup: Setup, onPick: (String) -> Unit, onKey: (ByteArray) -> Unit, onModel: (String, Boolean) -> Unit,
                onBack: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, onAddEndpoint: () -> Unit = {},
                onEndpoint: (name: String, baseUrl: String, model: String, key: ByteArray?) -> Unit = { _, _, _, _ -> },
                onOpenUrl: (String) -> Unit = {}, onForget: () -> Unit = {}) {
    val step = setup.step
    // The provider list is the first page (the brand mark heads it, as the desktop's first run); later steps go back.
    val first = step is SetupStep.Loading || step is SetupStep.Providers
    // A page holding a lazy list scrolls itself.
    val lists = step is SetupStep.Providers || (step is SetupStep.Models && step.provider.models.isNotEmpty())
    Page(stringResource(R.string.setup_title), modifier.testTag("setup"), onBack = if (first) null else onBack, backTag = "setup-back",
         backEnabled = !setup.busy, busy = setup.busy, scroll = !lists,
         action = { TextButton(onClose, Modifier.testTag("setup-close")) { Text(stringResource(R.string.setup_later)) } }) {
        setup.error?.let { Hint(it, Modifier.padding(bottom = 8.dp).testTag("setup-error"), color = MaterialTheme.colorScheme.error) }
        when (step) {
            SetupStep.Loading -> Unit
            is SetupStep.Providers -> LazyColumn(Modifier.weight(1f)) {
                item {
                    ListRow(stringResource(R.string.setup_add_endpoint), Modifier.testTag("add-endpoint"),
                            detail = stringResource(R.string.setup_add_endpoint_detail), trailing = { Chevron() }, onClick = onAddEndpoint)
                    SectionCaption(stringResource(R.string.setup_providers))
                }
                items(step.choices, key = { it.slug }) { provider ->
                    ListRow(provider.name, Modifier.testTag("provider-${provider.slug}"),
                            detail = if (provider.ready) stringResource(R.string.setup_signed_in) else provider.keyEnv,
                            trailing = { Chevron() }) { onPick(provider.slug) }
                }
            }
            is SetupStep.Key -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { KeyField(step.provider, enabled = !setup.busy, onKey) }
            is SetupStep.Models -> {
                ModelPicker(step.provider, setup, onModel, Modifier.weight(1f))
                if (step.keyHeld) {
                    TextButton(onForget, Modifier.testTag("forget-key"), enabled = !setup.busy) {
                        Text(stringResource(R.string.setup_forget_key, step.provider.name), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            SetupStep.Endpoint -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { EndpointForm(enabled = !setup.busy, onEndpoint) }
            is SetupStep.SignIn -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { SignIn(step, onOpenUrl) }
        }
    }
}

@Composable
private fun KeyField(provider: ProviderChoice, enabled: Boolean, onKey: (ByteArray) -> Unit) {
    // remember, not rememberSaveable: saved state is written out with the task, and the key must not be.
    var key by remember(provider.slug) { mutableStateOf("") }
    Hint(stringResource(R.string.setup_key_hint, provider.name, provider.keyEnv.orEmpty()))
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("key"), singleLine = true,
                      visualTransformation = PasswordVisualTransformation(),
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
    PrimaryButton(stringResource(R.string.setup_save_key), { onKey(key.trim().toByteArray()); key = "" }, Modifier.testTag("key-save"),
                  enabled = enabled && key.isNotBlank())
}

@Composable
private fun SignIn(step: SetupStep.SignIn, onOpenUrl: (String) -> Unit) {
    Text(stringResource(R.string.setup_sign_in, step.provider.name), style = MaterialTheme.typography.titleMedium)
    Hint(stringResource(R.string.setup_sign_in_code))
    Text(step.userCode, Modifier.testTag("signin-code"), style = MaterialTheme.typography.headlineMedium, fontFamily = CodeFont,
         color = MaterialTheme.colorScheme.primary)
    PrimaryButton(stringResource(R.string.setup_sign_in_open), { onOpenUrl(step.url) }, Modifier.testTag("signin-open"))
    Hint(stringResource(R.string.setup_sign_in_waiting))
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
    PrimaryButton(stringResource(R.string.setup_use_model),
                  { onEndpoint(name.trim(), url.trim(), model.trim(), key.trim().takeIf { it.isNotEmpty() }?.toByteArray()); key = "" },
                  Modifier.testTag("endpoint-save"), enabled = enabled && listOf(name, url, model).all { it.isNotBlank() })
}

@Composable
private fun ModelPicker(provider: ProviderChoice, setup: Setup, onModel: (String, Boolean) -> Unit, modifier: Modifier) {
    setup.confirm?.let { confirm ->
        Hint(confirm.message, Modifier.testTag("setup-confirm-message"))
        PrimaryButton(stringResource(R.string.setup_use_anyway), { onModel(confirm.model, true) }, Modifier.padding(vertical = 8.dp).testTag("setup-confirm"),
                      enabled = !setup.busy)
    }
    if (provider.models.isEmpty()) {
        // The core lists no models for this provider: the user names one.
        var typed by rememberSaveable(provider.slug) { mutableStateOf("") }
        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().testTag("model-name"), singleLine = true,
                          label = { Text(stringResource(R.string.setup_model_name)) })
        PrimaryButton(stringResource(R.string.setup_use_model), { onModel(typed.trim(), false) }, Modifier.padding(top = 12.dp).testTag("model-use"),
                      enabled = !setup.busy && typed.isNotBlank())
    } else {
        LazyColumn(modifier) {
            item { SectionCaption(provider.name, Modifier.padding(top = 0.dp)) }
            items(provider.models, key = { it }) { model ->
                ListRow(model, Modifier.testTag("model-$model"), enabled = !setup.busy) { onModel(model, false) }
            }
        }
    }
}
