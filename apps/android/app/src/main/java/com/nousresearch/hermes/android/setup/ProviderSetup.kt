package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.protocol.ConfigSetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelDisconnectParams
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
import com.nousresearch.hermes.bridge.protocol.ModelOptionsParams
import com.nousresearch.hermes.bridge.protocol.ProfileParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A provider the app can set up: one that takes a pasted key ([keyEnv]), or one already signed in ([ready]). */
data class ProviderChoice(val slug: String, val name: String, val keyEnv: String?, val ready: Boolean, val models: List<String>)

sealed interface SetupStep {
    data object Loading : SetupStep

    data class Providers(val choices: List<ProviderChoice>) : SetupStep

    data class Key(val provider: ProviderChoice) : SetupStep

    data class Models(val provider: ProviderChoice) : SetupStep

    /** A custom OpenAI-compatible endpoint: name, base URL, model and an optional key. */
    data object Endpoint : SetupStep
}

/** A model the core will only use once the user confirms (an expensive one), with the core's own wording. */
data class Confirm(val model: String, val message: String)

data class Setup(val step: SetupStep = SetupStep.Loading, val busy: Boolean = false, val error: String? = null,
                 val confirm: Confirm? = null)

/**
 * Provider setup driven by the core (D17): the core lists the providers and their models, a pasted key
 * goes to [secrets] and reaches the core only as an environment variable ([secretsChanged]), and the
 * chosen model becomes the default through [session], a live session (the core refuses sessionless
 * defaults). That session then gives way to a new one ([reopenChat]): a session created before the switch
 * runs its first turn on the old default model. A custom endpoint becomes a `providers.<id>` entry through
 * [saveEndpoint] (the bridge's `save_custom_endpoint`, JSON in and out) before the same steps.
 * [state] is null while setup is closed.
 */
class ProviderSetup(
    private val core: CoreGateway,
    private val secrets: SecretStore,
    private val secretsChanged: suspend () -> Unit,
    private val session: suspend () -> String,
    private val reopenChat: suspend () -> Unit,
    private val saveEndpoint: suspend (String) -> String,
) {
    private companion object {
        /** `model.disconnect`'s answer when the provider holds no credential. */
        const val NOTHING_TO_DISCONNECT = 4005
    }

    private val setup = MutableStateFlow<Setup?>(null)
    val state: StateFlow<Setup?> = setup.asStateFlow()

    private var choices = emptyList<ProviderChoice>()

    /** True when the core has no provider it could answer with. */
    suspend fun needed(): Boolean = core.call(Methods.SETUP_STATUS, ProfileParams()).providerConfigured != true

    suspend fun open() {
        setup.value = Setup()
        step { SetupStep.Providers(listed(refresh = false).also { choices = it }) }
    }

    fun close() {
        setup.value = null
    }

    fun pick(slug: String) {
        val provider = choices.first { it.slug == slug }
        setup.update { it?.copy(step = if (provider.ready) SetupStep.Models(provider) else SetupStep.Key(provider), error = null) }
    }

    /** Stores [key] under the provider's variable and wipes the caller's copy; a key the core does not take is forgotten. */
    suspend fun submitKey(key: ByteArray) {
        val provider = (setup.value?.step as? SetupStep.Key)?.provider ?: return
        val name = checkNotNull(provider.keyEnv)
        step {
            store(name, key)
            secretsChanged()
            val signedIn = listed(refresh = true).firstOrNull { it.slug == provider.slug && it.ready }
            if (signedIn == null) {
                forget(provider.slug, name)
                throw CoreError(0, "${provider.name} did not accept the key.")
            }
            SetupStep.Models(signedIn)
        }
    }

    /** Makes [model] the default; setup closes once the core took it. */
    suspend fun choose(model: String, confirmed: Boolean = false) {
        val provider = (setup.value?.step as? SetupStep.Models)?.provider ?: return
        step {
            val set = core.call(Methods.CONFIG_SET, ConfigSetParams(
                sessionId = session(), key = "model", value = JsonPrimitive("$model --provider ${provider.slug} --global"),
                confirmExpensiveModel = confirmed))
            if (set.confirmRequired == true) {
                setup.update { it?.copy(confirm = Confirm(model, set.confirmMessage.orEmpty())) }
                return@step SetupStep.Models(provider)
            }
            reopenChat()
            setup.value = null
            return
        }
    }

    fun addEndpoint() {
        setup.update { it?.copy(step = SetupStep.Endpoint, error = null) }
    }

    /** Saves the endpoint, keeps its [key] (when given; the caller's copy is wiped), then makes [model] the default. */
    suspend fun saveEndpoint(name: String, baseUrl: String, model: String, key: ByteArray?) {
        if (setup.value?.step != SetupStep.Endpoint) return
        val hasKey = key != null && key.isNotEmpty()
        step {
            val request = JsonObject(mapOf("name" to JsonPrimitive(name), "base_url" to JsonPrimitive(baseUrl),
                                           "model" to JsonPrimitive(model), "has_key" to JsonPrimitive(hasKey)))
            val saved = try {
                Json.parseToJsonElement(saveEndpoint(request.toString())).jsonObject
            } catch (failed: RuntimeException) {  // a bridge failure carries a traceback, not words for the user
                key?.fill(0)
                throw CoreError(0, "Could not save the endpoint (${failed.javaClass.simpleName}).")
            }
            saved["error"]?.let { key?.fill(0); throw CoreError(0, it.jsonPrimitive.content) }
            val keyEnv = saved.getValue("key_env").jsonPrimitive.content
            if (hasKey) {
                store(keyEnv, key!!)
                secretsChanged()
            }
            SetupStep.Models(ProviderChoice(saved.getValue("id").jsonPrimitive.content, name, keyEnv.ifEmpty { null }, true, listOf(model)))
        }
        if (setup.value?.error == null) choose(model)
    }

    fun back() {
        setup.update { it?.copy(step = SetupStep.Providers(choices), error = null, confirm = null) }
    }

    private fun store(name: String, key: ByteArray) {
        try {
            secrets.put(name, key)
        } catch (failed: Exception) {  // the keystore's own failure; its message is not shown
            throw CoreError(0, "Could not store the key (${failed.javaClass.simpleName}).")
        } finally {
            key.fill(0)
        }
    }

    /**
     * Takes a key back from the keystore and from the core. The core keeps env-backed credential rows
     * (without their value) across restarts, so the provider would still list as signed in until disconnected.
     */
    private suspend fun forget(slug: String, name: String) {
        secrets.delete(name)
        secretsChanged()
        try {
            core.call(Methods.MODEL_DISCONNECT, ModelDisconnectParams(slug))
        } catch (nothingLeft: CoreError) {
            if (nothingLeft.code != NOTHING_TO_DISCONNECT) throw nothingLeft
        }
    }

    private suspend fun listed(refresh: Boolean): List<ProviderChoice> =
        core.call(Methods.MODEL_OPTIONS, ModelOptionsParams(includeUnconfigured = true, refresh = refresh)).providers.mapNotNull(::offered)

    /** Runs one exchange with the core: busy meanwhile, then its next step, or the step stays and the core's error shows. */
    private inline fun step(exchange: () -> SetupStep) {
        setup.update { it?.copy(busy = true, error = null, confirm = null) }
        try {
            val next = exchange()
            setup.update { it?.copy(step = next, busy = false) }
        } catch (refused: CoreError) {
            setup.update { it?.copy(busy = false, error = refused.message) }
        }
    }
}

/** Offered: providers already signed in, and those that take a pasted key. Sign-in flows (OAuth, SDKs) come later. */
internal fun offered(row: ModelOptionProvider): ProviderChoice? {
    val keyEnv = row.keyEnv?.takeIf { row.authType == "api_key" && it.isNotEmpty() }
    if (row.authenticated != true && keyEnv == null) return null
    return ProviderChoice(row.slug, row.name, keyEnv, row.authenticated == true, row.models.orEmpty())
}
