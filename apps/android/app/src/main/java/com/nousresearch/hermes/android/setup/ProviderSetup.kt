package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.BridgeSecrets
import com.nousresearch.hermes.bridge.contract.DeleteCustomEndpointParams
import com.nousresearch.hermes.bridge.contract.DiscoverEndpointsParams
import com.nousresearch.hermes.bridge.contract.EndpointProbe
import com.nousresearch.hermes.bridge.contract.FoundServer
import com.nousresearch.hermes.bridge.contract.ProbeEndpointParams
import com.nousresearch.hermes.bridge.contract.SavedEndpoint
import com.nousresearch.hermes.bridge.contract.SaveCustomEndpointParams
import com.nousresearch.hermes.bridge.protocol.ConfigSetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelDisconnectParams
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
import com.nousresearch.hermes.bridge.protocol.ModelOptionsParams
import com.nousresearch.hermes.bridge.protocol.NousLoginParams
import com.nousresearch.hermes.bridge.protocol.NousLoginStartParams
import com.nousresearch.hermes.bridge.protocol.ProfileParams
import kotlin.coroutines.cancellation.CancellationException
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

    /**
     * The providers to choose from, and the custom endpoints saved on this phone ([saved]: to edit or delete);
     * [defaultGone]: the endpoint deleted since setup opened was the default model, so the chat has none until one is picked.
     */
    data class Providers(val choices: List<ProviderChoice>, val saved: List<SavedEndpoint> = emptyList(),
                         val defaultGone: Boolean = false) : SetupStep

    data class Key(val provider: ProviderChoice) : SetupStep

    /** The provider's models to pick the default from; [keyHeld]: its key is on this phone and can be forgotten. */
    data class Models(val provider: ProviderChoice, val keyHeld: Boolean = false) : SetupStep

    /**
     * A custom OpenAI-compatible endpoint (name, base URL, model, optional key): [editing] a saved one (else a new one),
     * the server [found] at the typed address (never a failure: that is the step's error), the network search's results
     * ([scan]) and whether one runs ([scanning]).
     */
    data class Endpoint(val editing: SavedEndpoint? = null, val found: FoundServer? = null,
                        val scan: List<FoundServer>? = null, val scanning: Boolean = false) : SetupStep

    /** A device-code sign-in waiting for the user to approve [userCode] at [url]. */
    data class SignIn(val provider: ProviderChoice, val loginId: String, val userCode: String, val url: String) : SetupStep
}

/** A model the core will only use once the user confirms (an expensive one), with the core's own wording. */
data class Confirm(val model: String, val message: String)

/**
 * What setup shows. [list]: the provider list Back returns to: the core's choices, the endpoints saved on this phone,
 * and whether the default model's endpoint was deleted since setup opened. Setup keeps no list outside this value.
 */
data class Setup(val step: SetupStep = SetupStep.Loading, val busy: Boolean = false, val error: String? = null,
                 val confirm: Confirm? = null, val list: SetupStep.Providers = SetupStep.Providers(emptyList()))

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
    private val subnet: suspend () -> String? = { null },
) {
    private companion object {
        /** `model.disconnect`'s answer when the provider holds no credential. */
        const val NOTHING_TO_DISCONNECT = 4005

        /** How often a waiting sign-in asks the core; the core polls the portal on its own schedule. */
        val POLL_INTERVAL = 1.seconds

        /** What an endpoint's base URL starts with; anything else is a host for Find server. */
        val WEB_URL = Regex("^https?://", RegexOption.IGNORE_CASE)
    }

    private val setup = MutableStateFlow<Setup?>(null)
    val state: StateFlow<Setup?> = setup.asStateFlow()

    /** True when the core has no provider it could answer with. */
    suspend fun needed(): Boolean = core.call(Methods.SETUP_STATUS, ProfileParams()).providerConfigured != true

    suspend fun open() {
        setup.value = Setup()
        step { providers(refresh = false) }
    }

    fun close() {
        setup.value = null
    }

    suspend fun pick(slug: String) {
        val provider = setup.value?.list?.choices?.firstOrNull { it.slug == slug } ?: return
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
                setup.update { it?.copy(step = it.list, error = refused.message) }
                return
            }
            when (polled.status) {
                "pending" -> continue
                "approved" -> step {
                    val signedIn = listed(refresh = true).firstOrNull { it.slug == provider.slug && it.ready }
                        ?: throw SetupRefusal("${provider.name} did not list the account.")
                    models(signedIn)
                }
                else -> setup.update {
                    it?.copy(step = it.list, error = polled.errorMessage ?: "${provider.name} sign-in ${polled.status}.")
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
                throw SetupRefusal("${provider.name} did not accept the key.")
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
        setup.update { it?.copy(step = SetupStep.Endpoint(), error = null) }
    }

    /** Opens the form on the saved endpoint [id]; saving it keeps its id (and its key unless a new one is typed). */
    fun editEndpoint(id: String) {
        val endpoint = setup.value?.list?.saved?.firstOrNull { it.id == id } ?: return
        setup.update { it?.copy(step = SetupStep.Endpoint(editing = endpoint), error = null) }
    }

    /**
     * Asks for the model server behind [address] (a host, host:port or URL) and its models. [key] is used for this
     * probe only and wiped after; nothing is stored until [saveEndpoint].
     */
    suspend fun findServer(address: String, key: ByteArray?) {
        val form = setup.value?.step as? SetupStep.Endpoint ?: return run { key?.fill(0) }
        step {
            // What an earlier probe found belongs to another address: saved with this one, it would name a wrong model.
            setup.update { it?.copy(step = form.copy(found = null)) }
            val reply = try {
                bridge.ask(BridgeCalls.PROBE_ENDPOINT,
                           ProbeEndpointParams(address.trim(), key?.takeIf { it.isNotEmpty() }?.decodeToString()))
            } finally {
                key?.fill(0)
            }
            when (val probe = serverProbe(reply)) {
                is ServerProbe.Found -> form.copy(found = probe.server, scan = null)
                is ServerProbe.Failed -> throw SetupRefusal(problem(probe.failure))
            }
        }
    }

    /** Looks for model servers on the phone's Wi-Fi network (only when the user asks: it knocks on every host). */
    suspend fun searchNetwork() {
        val form = setup.value?.step as? SetupStep.Endpoint ?: return
        setup.update { it?.copy(step = form.copy(scanning = true), error = null) }
        try {
            step {
                val cidr = subnet() ?: throw SetupRefusal("Connect to a Wi-Fi network to search it.")
                val found = bridge.ask(BridgeCalls.DISCOVER_ENDPOINTS, DiscoverEndpointsParams(cidr)).found
                if (found.isEmpty()) throw SetupRefusal("No model server answered on this network.")
                form.copy(scan = found)
            }
        } finally {  // found, failed or cancelled: the search is over
            setup.update { s -> (s?.step as? SetupStep.Endpoint)?.let { s.copy(step = it.copy(scanning = false)) } ?: s }
        }
    }

    /**
     * Deletes the saved endpoint [id] and forgets its key, on the phone and at the core (L10). When it was the default
     * model, the chat starts afresh: its live session would keep the model it was opened with (L11).
     */
    suspend fun deleteEndpoint(id: String) {
        step {
            val gone = bridge.ask(BridgeCalls.DELETE_CUSTOM_ENDPOINT, DeleteCustomEndpointParams(id))
            val notForgotten = try {
                forget(id, gone.keyEnv)  // always the endpoint's own slot, held or not
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                failed
            }
            val defaultGone = gone.wasDefault || setup.value?.list?.defaultGone == true
            if (gone.wasDefault) reopenChat()
            if (notForgotten != null) {  // the endpoint is gone all the same: the list must not offer it
                val list = (setup.value?.list ?: SetupStep.Providers(emptyList()))
                    .copy(saved = savedEndpoints(), defaultGone = defaultGone)
                setup.update { it?.copy(step = list, list = list) }
                throw notForgotten
            }
            providers(refresh = true, defaultGone = defaultGone)
        }
    }

    /**
     * Saves the endpoint (the one being edited keeps its id), keeps a newly typed [key] (the caller's copy is wiped;
     * with none, an edited endpoint keeps the key it had, and a slot the save left unnamed is forgotten), then makes
     * [model] the default.
     */
    suspend fun saveEndpoint(name: String, baseUrl: String, model: String, key: ByteArray?) {
        val form = setup.value?.step as? SetupStep.Endpoint ?: return run { key?.fill(0) }
        if (!WEB_URL.containsMatchIn(baseUrl.trim())) {  // a host (spark-98d1) is not yet a server's URL: Find server makes one
            key?.fill(0)
            setup.update { it?.copy(error = "That is a host, not a server's URL: tap Find server to look it up.") }
            return
        }
        // has_key: a NEW key goes to the keystore. Without one, the Python half keeps an edited entry's key_env.
        val newKey = key != null && key.isNotEmpty()
        if (step {
            val entry = try {
                bridge.ask(BridgeCalls.SAVE_CUSTOM_ENDPOINT, SaveCustomEndpointParams(form.editing?.id, name, baseUrl, model, newKey))
            } catch (refused: CoreError) {
                key?.fill(0)
                throw refused
            } catch (failed: RuntimeException) {  // a bridge failure carries a traceback, not words for the user
                key?.fill(0)
                throw SetupRefusal("Could not save the endpoint (${failed.javaClass.simpleName}).")
            }
            val keyEnv = entry.keyEnv
            if (newKey) {
                store(keyEnv, key!!)
                bridge.secretsChanged()
                keyNames[entry.id] = keyEnv
            } else {
                entry.forget?.let { forget(entry.id, it) }  // a keyless re-save: its old key must not outlive the entry
            }
            models(ProviderChoice(entry.id, name, keyEnv.ifEmpty { null }, true, listOf(model)))
        }) choose(model)
    }

    fun back() {
        setup.update { it?.copy(step = it.list, error = null, confirm = null) }
    }

    private fun store(name: String, key: ByteArray) {
        try {
            secrets.put(name, key)
        } catch (failed: Exception) {  // the keystore's own failure; its message is not shown
            throw SetupRefusal("Could not store the key (${failed.javaClass.simpleName}).")
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
            providers(refresh = false)
        }
    }

    private fun models(provider: ProviderChoice) =
        SetupStep.Models(provider, keyHeld = keyName(provider)?.let(secrets::contains) == true)

    /** The variable the provider's key is under: the core names it for an unconfigured provider, else the app recorded it. */
    private fun keyName(provider: ProviderChoice): String? = provider.keyEnv ?: keyNames[provider.slug]

    /** The provider list as the core now has it, with the endpoints saved on this phone. */
    private suspend fun providers(refresh: Boolean, defaultGone: Boolean = setup.value?.list?.defaultGone == true) =
        SetupStep.Providers(listed(refresh), savedEndpoints(), defaultGone)

    /** The saved endpoints; a list that cannot be read is shown empty (rather than blocking setup) and forgets nothing. */
    private suspend fun savedEndpoints(): List<SavedEndpoint> {
        val held = runCatching { secrets.names() }.getOrDefault(emptySet())  // before the listing: a slot stored after it is not judged by an older config
        val listed = try {
            bridge.ask(BridgeCalls.LIST_CUSTOM_ENDPOINTS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return emptyList()
        }
        forgetUnnamed(held, listed.keyEnvs)
        return listed.endpoints
    }

    /**
     * Takes back every custom-endpoint slot ([held] by the keystore) that config.yaml no longer names: a keyless
     * re-save or a forget that failed after a delete can leave one, exported to the core by the bulk keystore source
     * (Dv17). Other secrets are never touched. Best effort: what fails here is retried at the next listing.
     */
    private suspend fun forgetUnnamed(held: Set<String>, named: List<String>) {
        val gone = held.filter {
            it.startsWith(BridgeSecrets.CUSTOM_ENDPOINT_SLOT_PREFIX) && it.endsWith(BridgeSecrets.CUSTOM_ENDPOINT_SLOT_SUFFIX) &&
                it !in named && runCatching { secrets.delete(it) }.isSuccess  // one that fails does not keep the others
        }
        // No model.disconnect: a slot's name does not give back its endpoint's slug (the id rule is lossy).
        if (gone.isEmpty()) return
        try {
            bridge.secretsChanged()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {  // the next reload revokes them; the list still shows
        }
    }

    private suspend fun listed(refresh: Boolean): List<ProviderChoice> =
        core.call(Methods.MODEL_OPTIONS, ModelOptionsParams(includeUnconfigured = true, refresh = refresh)).providers.mapNotNull(::offered)

    /**
     * Runs one exchange with the core: busy meanwhile, then its next step, or the step stays and the error shows.
     * Answers whether the exchange's step was applied; callers decide from this, never from `Setup.error`.
     */
    private inline fun step(exchange: () -> SetupStep): Boolean {
        setup.update { it?.copy(busy = true, error = null, confirm = null) }
        try {
            val next = exchange()
            // A provider list that is shown is also the one Back returns to.
            setup.update { it?.copy(step = next, list = next as? SetupStep.Providers ?: it.list, busy = false) }
            return true
        } catch (failed: Exception) {
            setup.update { it?.copy(busy = false, error = forUser(failed)) }
            return false
        }
    }
}

/** Why the server at the typed address cannot be set up as it is, in words for the user. */
internal fun problem(outcome: EndpointProbe): String = when (outcome) {
    EndpointProbe.UNREACHABLE -> "Nothing answered at that address. Check the address and that the server is running."
    EndpointProbe.NEEDS_KEY -> "This server needs an API key."
    EndpointProbe.REFUSED ->
        "The server refused this address. Ollama answers only to its own machine's names: use its short name, e.g. spark-98d1."
    EndpointProbe.NO_MODELS -> "The server answered but lists no models. Type the model name instead."
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
