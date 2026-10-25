package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.SaveCustomEndpointParams
import com.nousresearch.hermes.bridge.protocol.ConfigSetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelDisconnectParams
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
import com.nousresearch.hermes.bridge.protocol.ModelOptionsParams
import com.nousresearch.hermes.bridge.protocol.NousLoginParams
import com.nousresearch.hermes.bridge.protocol.NousLoginStartParams
import com.nousresearch.hermes.bridge.protocol.ProfileParams
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive

/**
 * A provider the app can set up: one that takes a pasted key ([keyEnv]), one signed in through the
 * core's device-code flow ([signIn]: Nous Portal), or one already signed in ([ready]).
 */
data class ProviderChoice(val slug: String, val name: String, val keyEnv: String?, val ready: Boolean, val models: List<String>,
                          val signIn: Boolean = false)

sealed interface SetupStep {
    data object Loading : SetupStep

    data class Providers(val choices: List<ProviderChoice>) : SetupStep

    data class Key(val provider: ProviderChoice) : SetupStep

    /** The provider's models to pick the default from; [keyHeld]: its key is on this phone and can be forgotten. */
    data class Models(val provider: ProviderChoice, val keyHeld: Boolean = false) : SetupStep

    /** A custom OpenAI-compatible endpoint: name, base URL, model and an optional key. */
    data object Endpoint : SetupStep

    /** A device-code sign-in waiting for the user to approve [userCode] at [url]. */
    data class SignIn(val provider: ProviderChoice, val loginId: String, val userCode: String, val url: String) : SetupStep
}

/** A model the core will only use once the user confirms (an expensive one), with the core's own wording. */
data class Confirm(val model: String, val message: String)

data class Setup(val step: SetupStep = SetupStep.Loading, val busy: Boolean = false, val error: String? = null,
                 val confirm: Confirm? = null)

/**
 * Provider setup driven by the core (D17): the core lists the providers and their models, a pasted key
 * goes to [secrets] and reaches the core only as an environment variable (re-read through [bridge]), and the
 * chosen model becomes the default through [session], a live session (the core refuses sessionless
 * defaults). That session then gives way to a new one ([reopenChat]): a session created before the switch
 * runs its first turn on the old default model. A custom endpoint becomes a `providers.<id>` entry through
 * [saveEndpoint] ([bridge]'s `save_custom_endpoint`, JSON in and out) before the same steps.
 * [state] is null while setup is closed.
 */
class ProviderSetup(
    private val core: CoreGateway,
    private val secrets: SecretStore,
    private val bridge: AppBridge,
    private val session: suspend () -> String,
    private val reopenChat: suspend () -> Unit,
    private val keyNames: KeyNames = KeyNames.InMemory(),
) {
    private companion object {
        /** `model.disconnect`'s answer when the provider holds no credential. */
        const val NOTHING_TO_DISCONNECT = 4005

        /** How often a waiting sign-in asks the core; the core polls the portal on its own schedule. */
        val POLL_INTERVAL = 1.seconds
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

    suspend fun pick(slug: String) {
        val provider = choices.first { it.slug == slug }
        when {
            provider.ready -> setup.update { it?.copy(step = models(provider), error = null) }
            provider.signIn -> signIn(provider)
            else -> setup.update { it?.copy(step = SetupStep.Key(provider), error = null) }
        }
    }

    /**
     * Runs the core's Nous Portal sign-in: shows the code, polls until the user approved it in the browser,
     * then lists the account's models. Leaving the step (back, close) cancels the sign-in at the core.
     */
    private suspend fun signIn(provider: ProviderChoice) {
        step {
            val started = core.call(Methods.NOUS_LOGIN_START, NousLoginStartParams())
            SetupStep.SignIn(provider, started.loginId, started.userCode, started.verificationUrl)
        }
        val waiting = setup.value?.step as? SetupStep.SignIn ?: return
        while (true) {
            delay(POLL_INTERVAL)
            if ((setup.value?.step as? SetupStep.SignIn)?.loginId != waiting.loginId) {
                try {
                    core.call(Methods.NOUS_LOGIN_CANCEL, NousLoginParams(loginId = waiting.loginId))
                } catch (_: CoreError) {
                    // nothing left to cancel
                }
                return
            }
            val polled = try {
                core.call(Methods.NOUS_LOGIN_POLL, NousLoginParams(loginId = waiting.loginId))
            } catch (refused: CoreError) {
                setup.update { it?.copy(step = SetupStep.Providers(choices), error = refused.message) }
                return
            }
            when (polled.status) {
                "pending" -> continue
                "approved" -> step {
                    val signedIn = listed(refresh = true).firstOrNull { it.slug == provider.slug && it.ready }
                        ?: throw CoreError(0, "${provider.name} did not list the account.")
                    models(signedIn)
                }
                else -> setup.update {
                    it?.copy(step = SetupStep.Providers(choices), error = polled.errorMessage ?: "${provider.name} sign-in ${polled.status}.")
                }
            }
            return
        }
    }

    /** Stores [key] under the provider's variable and wipes the caller's copy; a key the core does not take is forgotten. */
    suspend fun submitKey(key: ByteArray) {
        val provider = (setup.value?.step as? SetupStep.Key)?.provider ?: return
        val name = checkNotNull(provider.keyEnv)
        step {
            store(name, key)
            bridge.secretsChanged()
            val signedIn = listed(refresh = true).firstOrNull { it.slug == provider.slug && it.ready }
            if (signedIn == null) {
                forget(provider.slug, name)
                throw CoreError(0, "${provider.name} did not accept the key.")
            }
            keyNames[provider.slug] = name
            models(signedIn)
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
                return@step models(provider)
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
            val saved = try {
                bridge.ask(BridgeCalls.SAVE_CUSTOM_ENDPOINT, SaveCustomEndpointParams(name, baseUrl, model, hasKey))
            } catch (refused: CoreError) {
                key?.fill(0)
                throw refused
            } catch (failed: RuntimeException) {  // a bridge failure carries a traceback, not words for the user
                key?.fill(0)
                throw CoreError(0, "Could not save the endpoint (${failed.javaClass.simpleName}).")
            }
            val keyEnv = saved.keyEnv
            if (hasKey) {
                store(keyEnv, key!!)
                bridge.secretsChanged()
                keyNames[saved.id] = keyEnv
            }
            models(ProviderChoice(saved.id, name, keyEnv.ifEmpty { null }, true, listOf(model)))
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
        keyNames.remove(slug)
        bridge.secretsChanged()
        try {
            core.call(Methods.MODEL_DISCONNECT, ModelDisconnectParams(slug))
        } catch (nothingLeft: CoreError) {
            if (nothingLeft.code != NOTHING_TO_DISCONNECT) throw nothingLeft
        }
    }

    /** Takes the shown provider's key off the phone (keystore and core); the list then shows the provider as it now is. */
    suspend fun forgetKey() {
        val shown = setup.value?.step as? SetupStep.Models ?: return
        val name = keyName(shown.provider)?.takeIf { shown.keyHeld } ?: return
        step {
            forget(shown.provider.slug, name)
            SetupStep.Providers(listed(refresh = false).also { choices = it })
        }
    }

    private fun models(provider: ProviderChoice) =
        SetupStep.Models(provider, keyHeld = keyName(provider)?.let(secrets::contains) == true)

    /** The variable the provider's key is under: the core names it for an unconfigured provider, else the app recorded it. */
    private fun keyName(provider: ProviderChoice): String? = provider.keyEnv ?: keyNames[provider.slug]

    private suspend fun listed(refresh: Boolean): List<ProviderChoice> =
        core.call(Methods.MODEL_OPTIONS, ModelOptionsParams(includeUnconfigured = true, refresh = refresh)).providers.mapNotNull(::offered)

    /** Runs one exchange with the core: busy meanwhile, then its next step, or the step stays and the core's error shows. */
    private inline fun step(exchange: () -> SetupStep) {
        setup.update { it?.copy(busy = true, error = null, confirm = null) }
        try {
            val next = exchange()
            setup.update { it?.copy(step = next, busy = false) }
        } catch (failed: Exception) {
            setup.update { it?.copy(busy = false, error = forUser(failed)) }
        }
    }
}

/**
 * Offered: providers already signed in, those that take a pasted key, and Nous Portal, whose device-code
 * sign-in the core runs (`nous.login.*`). Other sign-in flows (OAuth elsewhere, SDKs) come later.
 */
internal fun offered(row: ModelOptionProvider): ProviderChoice? {
    val ready = row.authenticated == true
    val keyEnv = row.keyEnv?.takeIf { row.authType == "api_key" && it.isNotEmpty() }
    val signIn = !ready && row.slug == "nous" && row.authType == "oauth_device_code"
    if (!ready && keyEnv == null && !signIn) return null
    return ProviderChoice(row.slug, row.name, keyEnv, ready, row.models.orEmpty(), signIn)
}
