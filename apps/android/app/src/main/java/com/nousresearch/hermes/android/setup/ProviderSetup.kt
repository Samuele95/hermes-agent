package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.contract.FoundServer
import com.nousresearch.hermes.bridge.contract.SavedEndpoint
import com.nousresearch.hermes.bridge.protocol.ConfigSetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelDisconnectParams
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
import com.nousresearch.hermes.bridge.protocol.ModelOptionsParams
import com.nousresearch.hermes.bridge.protocol.NousLoginParams
import com.nousresearch.hermes.bridge.protocol.NousLoginStartParams
import com.nousresearch.hermes.bridge.protocol.NousLoginStartResult
import com.nousresearch.hermes.bridge.protocol.ProfileParams
import com.nousresearch.hermes.bridge.protocol.SetupRuntimeCheckParams
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

/**
 * A provider the app can set up: one that takes a pasted key ([keyEnv]), one signed in through the
 * core's device-code flow ([signIn]: Nous Portal), or one already signed in ([ready]). [facts]: what the core's list
 * says about it and its models (F5). [custom]: ids the user added for it that its catalog does not list (DC-M9).
 */
data class ProviderChoice(val slug: String, val name: String, val keyEnv: String?, val ready: Boolean, val models: List<String>,
                          val signIn: Boolean = false, val facts: ProviderFacts = ProviderFacts.NONE,
                          val custom: List<String> = emptyList())

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
     * ([scan]) and whether one runs ([scanning]). [generation] counts the servers found on this form; each time it grows
     * the form's address and model start again from [found] and [suggestedModel], and only then (a rotation keeps what
     * the user typed since).
     */
    data class Endpoint(val editing: SavedEndpoint? = null, val found: FoundServer? = null,
                        val scan: List<FoundServer>? = null, val scanning: Boolean = false,
                        val suggestedModel: String? = null, val generation: Int = 0) : SetupStep

    /** A device-code sign-in waiting for the user to approve [userCode] at [url]. */
    data class SignIn(val provider: ProviderChoice, val loginId: String, val userCode: String, val url: String) : SetupStep
}

/** A model the core will only use once the user confirms (an expensive one), with the core's own wording. */
data class Confirm(val model: String, val message: String)

/**
 * One showing of a setup screen. A step's answer, its error and its busy flag land only on the showing that asked
 * for it: a screen left (Back, Later, another choice) or opened again is a new showing. Compared by identity.
 */
class Showing

/**
 * What setup shows. [list]: the provider list Back returns to: the core's choices, the endpoints saved on this phone,
 * and whether the default model's endpoint was deleted since setup opened. Setup keeps no list outside this value.
 * [showing]: which showing this is (see [Showing]). [notice]: the core's words on a typed id it took with a warning; that
 * id is already the default (D10).
 */
data class Setup(val step: SetupStep = SetupStep.Loading, val busy: Boolean = false, val error: String? = null,
                 val confirm: Confirm? = null, val notice: String? = null, val list: SetupStep.Providers = SetupStep.Providers(emptyList()),
                 val showing: Showing = Showing())

/**
 * Provider setup driven by the core (D17): the core lists the providers and their models, a pasted key
 * goes to [secrets] and reaches the core only as an environment variable (re-read through [bridge]), and the
 * chosen model becomes the default through [session], a live session (the core refuses sessionless
 * defaults). That session then gives way to a new one ([reopenChat]): a session created before the switch
 * runs its first turn on the old default model. Custom endpoints are [EndpointSetup]'s; this class keeps provider
 * auth and the default model, and delegates the endpoint actions to it. [state] is null while setup is closed.
 * A step's answer lands only on the showing that asked for it ([Showing]). [customModels]: the ids the user typed, kept
 * per provider once the core took one. [now] is the wall clock the core's reset times are read against.
 * [readiness]: whether the core's set-up provider will answer (F6).
 */
class ProviderSetup(
    private val core: CoreGateway,
    private val secrets: SecretStore,
    private val bridge: AppBridge,
    private val session: suspend () -> String,
    private val reopenChat: suspend () -> Unit,
    private val keyNames: KeyNames = KeyNames.InMemory(),
    private val subnet: suspend () -> String? = { null },
    private val customModels: CustomModels = CustomModels.InMemory(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        /** `model.disconnect`'s answer when the provider holds no credential. */
        const val NOTHING_TO_DISCONNECT = 4005

        /** How often a waiting sign-in asks the core; the core polls the portal on its own schedule. */
        val POLL_INTERVAL = 1.seconds
    }

    private val setup = MutableStateFlow<Setup?>(null)
    val state: StateFlow<Setup?> = setup.asStateFlow()

    private val endpoints = EndpointSetup(bridge, secrets, subnet, keep = ::keep, forget = ::forget, onDefaultGone = reopenChat)

    private val readinessNow = MutableStateFlow<Readiness>(Readiness.Checking)

    /** Whether the provider the core is set up for will answer, as its last `setup.runtime_check` said (F6); Checking until the first answers. */
    val readiness: StateFlow<Readiness> = readinessNow.asStateFlow()

    /** Orders the checks (D4). */
    private val checking = Mutex()

    /**
     * `setup.runtime_check {}` (the launch profile, no inference call) → [Readiness.of]; `CoreError` 5097 or any failure →
     * [Readiness.Unknown] (never "unavailable": the check did not finish); `setup.status` is asked only after a refusal,
     * to tell needs setup from unavailable. Called after `composeCore`'s start-up calls, each time setup closes, and from
     * the chat after a turn failed on its provider's side. Under a lock: an overlapping call would join the core's probe
     * for 0.5 s and read 5097 (D4). No app-side timeout: the core bounds the check at 4 s.
     */
    suspend fun checkReadiness() {
        checking.withLock {
            readinessNow.value = Readiness.Checking
            readinessNow.value = try {
                val check = core.call(Methods.SETUP_RUNTIME_CHECK, SetupRuntimeCheckParams())
                Readiness.of(check, configured = check.ok || configured())
            } catch (cancelled: CancellationException) {
                throw cancelled  // not a failed check: nothing to show
            } catch (_: Exception) {
                Readiness.Unknown  // 5097 (its 4 s budget passed), any refusal, an unreadable answer: the check did not finish (EJ-55)
            }
        }
    }

    /** `setup.status`'s loose answer; a failure reads as none, so needs setup (Desktop's fallback). */
    private suspend fun configured(): Boolean = try {
        core.call(Methods.SETUP_STATUS, ProfileParams()).providerConfigured == true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /** Checks readiness each time setup closes, whatever closed it: Back, Later, a model the core took. Never returns. */
    suspend fun followReadiness() {
        coroutineScope {
            // drop(1): the state found at the start, open or not; launched, so a 4 s check never conflates the next close away.
            setup.map { it != null }.distinctUntilChanged().drop(1).filter { open -> !open }.collect { launch { checkReadiness() } }
        }
    }

    /** True when the core has no provider it could answer with. */
    suspend fun needed(): Boolean = core.call(Methods.SETUP_STATUS, ProfileParams()).providerConfigured != true

    suspend fun open() {
        val opened = Setup()
        setup.value = opened
        step(opened.showing) { providers(refresh = false) }
    }

    fun close() {
        setup.value = null
    }

    suspend fun pick(slug: String) {
        val shown = setup.value ?: return
        val provider = shown.list.choices.firstOrNull { it.slug == slug } ?: return
        when {
            provider.ready -> show(models(provider))
            provider.signIn -> signIn(provider, shown.showing)
            else -> show(SetupStep.Key(provider))
        }
    }

    /**
     * Runs the core's Nous Portal sign-in: shows the code, polls until the user approved it in the browser,
     * then lists the account's models. Leaving the step (back, close) cancels the sign-in at the core.
     */
    private suspend fun signIn(provider: ProviderChoice, origin: Showing) {
        var started: NousLoginStartResult? = null
        val waiting = step(origin) {
            core.call(Methods.NOUS_LOGIN_START, NousLoginStartParams()).also { started = it }
                .let { SetupStep.SignIn(provider, it.loginId, it.userCode, it.verificationUrl) }
        }
        val loginId = started?.loginId ?: return
        if (waiting == null) {  // started after the user left: nobody will approve it
            cancelLogin(loginId)
            return
        }
        while (true) {
            delay(POLL_INTERVAL)
            if (setup.value?.showing !== waiting) {
                cancelLogin(loginId)
                return
            }
            val polled = try {
                core.call(Methods.NOUS_LOGIN_POLL, NousLoginParams(loginId = loginId))
            } catch (refused: CoreError) {
                on(waiting) { it.copy(step = it.list, error = refused.message, showing = Showing()) }
                return
            }
            when (polled.status) {
                "pending" -> continue
                "approved" -> step(waiting) {
                    val signedIn = listed(refresh = true).firstOrNull { it.slug == provider.slug && it.ready }
                        ?: throw SetupRefusal("${provider.name} did not list the account.")
                    models(signedIn)
                }
                else -> on(waiting) {
                    it.copy(step = it.list, error = polled.errorMessage ?: "${provider.name} sign-in ${polled.status}.",
                            showing = Showing())
                }
            }
            return
        }
    }

    private suspend fun cancelLogin(loginId: String) {
        try {
            core.call(Methods.NOUS_LOGIN_CANCEL, NousLoginParams(loginId = loginId))
        } catch (_: CoreError) {
            // nothing left to cancel
        }
    }

    /** Stores [key] under the provider's variable and wipes the caller's copy; a key the core does not take is forgotten. */
    suspend fun submitKey(key: ByteArray) {
        val shown = setup.value ?: return
        val provider = (shown.step as? SetupStep.Key)?.provider ?: return
        val name = checkNotNull(provider.keyEnv)
        step(shown.showing) {
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

    /**
     * Makes [model] the default; setup closes once the core took it. A Pro-locked model is never sent (the core's
     * `unavailable_models`). A model the provider's catalog does not list is kept for it only once the core took it
     * (DC-M9); a warning the core answers on one stays shown until Done (D10).
     */
    suspend fun choose(model: String, confirmed: Boolean = false) {
        val shown = setup.value ?: return
        val provider = (shown.step as? SetupStep.Models)?.provider ?: return
        if (provider.facts.models[model]?.locked == true) return
        val origin = shown.showing
        step(origin) {
            val set = core.call(Methods.CONFIG_SET, ConfigSetParams(
                sessionId = session(), key = "model", value = JsonPrimitive("$model --provider ${provider.slug} --global"),
                confirmExpensiveModel = confirmed))
            if (set.confirmRequired == true) {
                on(origin) { it.copy(confirm = Confirm(model, set.confirmMessage.orEmpty())) }
                return@step models(provider)
            }
            val custom = model !in provider.models
            if (custom) customModels.add(provider.slug, model)  // only now: the core took it (DC-M9)
            reopenChat()  // the core's default changed whoever is looking (L11)
            val note = set.warning?.takeIf { custom && it.isNotBlank() }
            if (note != null) {
                on(origin) { it.copy(notice = note) }
                return@step models(provider.copy(custom = (provider.custom + model).distinct()))
            }
            on(origin) { null }
            return
        }
    }

    /** The typed id, kept in prefs only once the core took it: one with whitespace is refused here, a blank one ignored. */
    suspend fun chooseCustom(model: String) {
        val shown = setup.value ?: return
        val id = customModelId(model)
        if (id == null) {
            if (model.isNotBlank()) on(shown.showing) { it.copy(error = "A model id has no spaces.") }
            return
        }
        choose(id)
    }

    fun addEndpoint() {
        show(SetupStep.Endpoint())
    }

    /** Opens the form on the saved endpoint [id]; saving it keeps its id (and its key unless a new one is typed). */
    fun editEndpoint(id: String) {
        val form = setup.value?.let { endpoints.edit(it.list, id) } ?: return
        show(form)
    }

    /**
     * Asks for the model server behind [address] (a host, host:port or URL) and its models. [key] is used for this
     * probe only and wiped after; nothing is stored until [saveEndpoint]. [model]: what the form's model field holds;
     * kept when the server lists it.
     */
    suspend fun findServer(address: String, key: ByteArray?, model: String = "") {
        val shown = setup.value
        val form = shown?.step as? SetupStep.Endpoint ?: return run { key?.fill(0) }
        step(shown.showing) {
            // What an earlier probe found belongs to another address: saved with this one, it would name a wrong model.
            on(shown.showing) { it.copy(step = form.copy(found = null)) }
            endpoints.find(form, address, key, model)
        }
    }

    /** Looks for model servers on the phone's Wi-Fi network (only when the user asks: it knocks on every host). */
    suspend fun searchNetwork() {
        val shown = setup.value
        val form = shown?.step as? SetupStep.Endpoint ?: return
        val origin = shown.showing
        on(origin) { it.copy(step = form.copy(scanning = true), error = null) }
        try {
            step(origin) { endpoints.search(form) }
        } finally {  // found, failed or cancelled: the search is over
            on(origin) { s -> (s.step as? SetupStep.Endpoint)?.let { s.copy(step = it.copy(scanning = false)) } ?: s }
        }
    }

    /**
     * Deletes the saved endpoint [id] and forgets its key, on the phone and at the core (L10). When it was the default
     * model, the chat starts afresh: its live session would keep the model it was opened with (L11).
     */
    suspend fun deleteEndpoint(id: String) {
        val origin = setup.value?.showing
        step(origin) {
            val deleted = endpoints.delete(id)
            val defaultGone = deleted.wasDefault || setup.value?.list?.defaultGone == true
            deleted.notForgotten?.let { notForgotten ->  // the endpoint is gone all the same: the list must not offer it
                val list = (setup.value?.list ?: SetupStep.Providers(emptyList()))
                    .copy(saved = endpoints.saved(), defaultGone = defaultGone)
                on(origin) { it.copy(step = list, list = list) }
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
        val shown = setup.value
        val form = shown?.step as? SetupStep.Endpoint ?: return run { key?.fill(0) }
        // Saved even when the user left meanwhile; only a save whose step landed makes its model the default.
        if (step(shown.showing) { models(endpoints.save(form, name, baseUrl, model, key)) } != null) choose(model)
    }

    fun back() {
        setup.update { it?.copy(step = it.list, busy = false, error = null, confirm = null, notice = null, showing = Showing()) }
    }

    /** Keeps an endpoint's newly typed [key] as a pasted provider key is kept: keystore, core, then its name. */
    private suspend fun keep(slug: String, keyEnv: String, key: ByteArray) {
        store(keyEnv, key)
        bridge.secretsChanged()
        keyNames[slug] = keyEnv
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
        val shownSetup = setup.value ?: return
        val shown = shownSetup.step as? SetupStep.Models ?: return
        val name = keyName(shown.provider)?.takeIf { shown.keyHeld } ?: return
        step(shownSetup.showing) {
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
        SetupStep.Providers(listed(refresh), endpoints.saved(), defaultGone)

    private suspend fun listed(refresh: Boolean): List<ProviderChoice> =
        core.call(Methods.MODEL_OPTIONS, ModelOptionsParams(includeUnconfigured = true, refresh = refresh)).providers
            .mapNotNull { row -> offered(row, now())?.let { p -> p.copy(custom = customModels[p.slug].filterNot { it in p.models }) } }

    /**
     * Runs one exchange with the core for the showing [origin]: busy meanwhile, then its next step as a new showing, or
     * the step stays and the error shows — each only while setup still shows [origin]. Answers the showing the next
     * step opened, or null when nothing landed; callers decide from this, never from `Setup.error`.
     */
    private inline fun step(origin: Showing?, exchange: () -> SetupStep): Showing? {
        on(origin) { it.copy(busy = true, error = null, confirm = null, notice = null) }
        try {
            val next = exchange()
            val opened = Showing()
            // A provider list that is shown is also the one Back returns to; a confirm the exchange asked for stays.
            val landed = on(origin) {
                it.copy(step = next, list = next as? SetupStep.Providers ?: it.list, busy = false, showing = opened)
            }
            return opened.takeIf { landed }
        } catch (failed: Exception) {
            val words = forUser(failed)  // rethrows a cancellation: not a failure to show
            on(origin) { it.copy(busy = false, error = words) }
            return null
        } finally {  // after a landing the origin is gone; after a cancellation this ends the busy state
            on(origin) { it.copy(busy = false) }
        }
    }

    /** Writes [change] only while setup still shows [origin]; answers whether it landed. */
    private fun on(origin: Showing?, change: (Setup) -> Setup?): Boolean {
        var landed = false
        setup.update { s ->  // update may run this more than once: landed is the last attempt's
            landed = s != null && s.showing === origin
            if (s != null && s.showing === origin) change(s) else s
        }
        return landed
    }

    /** Shows [step] as a new showing: not busy, no error, no confirm or notice (those belong to the showing they came from). */
    private fun show(step: SetupStep) {
        setup.update { it?.copy(step = step, busy = false, error = null, confirm = null, notice = null, showing = Showing()) }
    }
}

/**
 * Offered: providers already signed in, those that take a pasted key, and Nous Portal, whose device-code
 * sign-in the core runs (`nous.login.*`). Other sign-in flows (OAuth elsewhere, SDKs) come later. Each carries the
 * row's facts as of [nowMs].
 */
internal fun offered(row: ModelOptionProvider, nowMs: Long): ProviderChoice? {
    val ready = row.authenticated == true
    val keyEnv = row.keyEnv?.takeIf { row.authType == "api_key" && it.isNotEmpty() }
    val signIn = !ready && row.slug == "nous" && row.authType == "oauth_device_code"
    if (!ready && keyEnv == null && !signIn) return null
    return ProviderChoice(row.slug, row.name, keyEnv, ready, row.models.orEmpty(), signIn, ProviderFacts.of(row, nowMs))
}
