package com.nousresearch.hermes.android.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import android.net.Uri
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.SectionCaption
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.Page
import com.nousresearch.hermes.android.ui.ListRow
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.Chevron

/**
 * Provider setup: pick a provider, paste its key when it needs one, pick a model; or find a model server by its address
 * (or on the Wi-Fi network), pick one of its models, and edit or delete the endpoints saved before. Stateless but for
 * the fields.
 */
@Composable
fun SetupScreen(setup: Setup, onPick: (String) -> Unit, onKey: (ByteArray) -> Unit, onModel: (String, Boolean) -> Unit,
                onBack: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, onAddEndpoint: () -> Unit = {},
                onEndpoint: (name: String, baseUrl: String, model: String, key: ByteArray?) -> Unit = { _, _, _, _ -> },
                onOpenUrl: (String) -> Unit = {}, onForget: () -> Unit = {},
                onFindServer: (address: String, key: ByteArray?) -> Unit = { _, _ -> }, onSearchNetwork: () -> Unit = {},
                onEditEndpoint: (String) -> Unit = {}, onDeleteEndpoint: (String) -> Unit = {}) {
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
                    if (step.defaultGone) {
                        Hint(stringResource(R.string.setup_no_default), Modifier.padding(bottom = 8.dp).testTag("setup-no-default"))
                    }
                    if (step.saved.isNotEmpty()) SectionCaption(stringResource(R.string.setup_your_endpoints), Modifier.padding(top = 0.dp))
                }
                items(step.saved, key = { "saved-${it.id}" }) { endpoint ->
                    ListRow(endpoint.name, Modifier.testTag("saved-${endpoint.id}"), detail = "${endpoint.model} · ${endpoint.baseUrl}",
                            trailing = { Chevron() }) { onEditEndpoint(endpoint.id) }
                }
                item {
                    if (step.saved.isNotEmpty()) SectionCaption(stringResource(R.string.setup_add))
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
            is SetupStep.Endpoint -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EndpointForm(step, enabled = !setup.busy, onEndpoint, onFindServer, onSearchNetwork, onDeleteEndpoint)
            }
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

/**
 * The endpoint form: an address to find a server at (or a server found on the network), the model picked from what the
 * server lists (or typed), an optional key; an edited endpoint keeps its values and can be deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndpointForm(step: SetupStep.Endpoint, enabled: Boolean, onEndpoint: (String, String, String, ByteArray?) -> Unit,
                         onFindServer: (String, ByteArray?) -> Unit, onSearchNetwork: () -> Unit, onDeleteEndpoint: (String) -> Unit) {
    val editing = step.editing
    var address by rememberSaveable(editing?.id) { mutableStateOf(editing?.baseUrl ?: "") }
    var model by rememberSaveable(editing?.id) { mutableStateOf(editing?.model ?: "") }
    var name by rememberSaveable(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var key by remember(editing?.id) { mutableStateOf("") }  // never saved state, as in KeyField
    val models = step.probe?.models.orEmpty()
    LaunchedEffect(step.probe) {
        // What the server answered at becomes the address; its first model is the choice until the user picks another.
        step.probe?.baseUrl?.let { address = it }
        if (model.isBlank()) models.firstOrNull()?.let { model = it }
    }
    fun typedKey() = key.trim().takeIf { it.isNotEmpty() }?.toByteArray()

    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth().testTag("endpoint-address"), singleLine = true,
                      label = { Text(stringResource(R.string.setup_endpoint_address)) },
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth().testTag("endpoint-key"), singleLine = true,
                      label = { Text(stringResource(if (editing?.hasKey == true) R.string.setup_key_keep else R.string.setup_endpoint_key)) },
                      visualTransformation = PasswordVisualTransformation(),
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(stringResource(R.string.setup_find_server), { onFindServer(address.trim(), typedKey()) },
                        Modifier.testTag("find-server"), enabled = enabled && address.isNotBlank())
        SecondaryButton(stringResource(R.string.setup_search_network), onSearchNetwork, Modifier.testTag("search-network"),
                        enabled = enabled && !step.scanning)
    }
    if (step.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
    step.probe?.let { Hint(pluralStringResource(R.plurals.setup_found_models, it.models.size, it.kind.orEmpty(), it.models.size), Modifier.testTag("endpoint-found")) }
    step.scan?.let { found ->
        Column {
            SectionCaption(stringResource(R.string.setup_found_on_network), Modifier.padding(top = 0.dp))
            found.forEachIndexed { i, server ->
                ListRow(server.baseUrl, Modifier.testTag("found-$i"), detail = pluralStringResource(R.plurals.setup_found_models, server.models.size, server.kind, server.models.size),
                        enabled = enabled, trailing = { Chevron() }) { onFindServer(server.baseUrl, null) }
            }
        }
    }
    var listOpen by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(listOpen && models.isNotEmpty(), { listOpen = it }) {
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth().testTag("endpoint-model")
                              .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = models.isNotEmpty()),
                          singleLine = true, label = { Text(stringResource(R.string.setup_model_name)) },
                          trailingIcon = { if (models.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(listOpen) })
        ExposedDropdownMenu(listOpen && models.isNotEmpty(), { listOpen = false }) {
            models.forEach { offered ->
                DropdownMenuItem({ Text(offered) }, { model = offered; listOpen = false }, Modifier.testTag("endpoint-model-$offered"))
            }
        }
    }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("endpoint-name"), singleLine = true,
                      label = { Text(stringResource(R.string.setup_endpoint_name)) })
    PrimaryButton(stringResource(if (editing != null) R.string.setup_save else R.string.setup_use_model), {
        val url = address.trim()
        // Unnamed: the server's host names it (spark-98d1), as the user would.
        val label = name.trim().ifEmpty { Uri.parse(url).host ?: url }
        onEndpoint(label, url, model.trim(), typedKey())
        key = ""
    }, Modifier.testTag("endpoint-save"), enabled = enabled && address.isNotBlank() && model.isNotBlank())
    if (editing != null) {
        // A delete also forgets the endpoint's key: one tap asks, a second one deletes.
        var confirming by remember(editing.id) { mutableStateOf(false) }
        if (!confirming) {
            SecondaryButton(stringResource(R.string.setup_delete_endpoint), { confirming = true }, Modifier.testTag("endpoint-delete"),
                            enabled = enabled, color = MaterialTheme.colorScheme.error)
        } else {
            Hint(stringResource(R.string.setup_delete_endpoint_confirm, editing.name), color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.setup_delete), { confirming = false; onDeleteEndpoint(editing.id) },
                              Modifier.testTag("endpoint-delete-confirm"), enabled = enabled)
                SecondaryButton(stringResource(R.string.setup_cancel), { confirming = false }, Modifier.testTag("endpoint-delete-cancel"))
            }
        }
    }
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
