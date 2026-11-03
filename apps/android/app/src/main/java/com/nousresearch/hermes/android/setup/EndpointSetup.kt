package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.BridgeSecrets
import com.nousresearch.hermes.bridge.contract.DeleteCustomEndpointParams
import com.nousresearch.hermes.bridge.contract.DiscoverEndpointsParams
import com.nousresearch.hermes.bridge.contract.EndpointProbe
import com.nousresearch.hermes.bridge.contract.FoundServer
import com.nousresearch.hermes.bridge.contract.ProbeEndpointParams
import com.nousresearch.hermes.bridge.contract.SaveCustomEndpointParams
import com.nousresearch.hermes.bridge.contract.SavedEndpoint
import kotlin.coroutines.cancellation.CancellationException

/** What deleting a saved endpoint settled: [wasDefault] (the chat has been reopened), and the forget that failed,
 *  if one did ([notForgotten]: the entry is gone all the same). */
internal data class Deletion(val wasDefault: Boolean, val notForgotten: Exception? = null)

/**
 * The custom-endpoint catalogue (D32): find a server, search the Wi-Fi, list, edit, delete and save the
 * `providers.<id>` entries config.yaml holds, through [bridge]. It keeps no state: it takes the form or list setup
 * shows and answers the next one; setup's one `StateFlow` holds it. A delete settles every effect of blueprint
 * §2 CustomEndpoint (d) here. A new key is kept through [keep] and a slot given back through [forget], the way
 * provider setup keeps and forgets every key; [onDefaultGone] reopens the chat whose default was deleted.
 */
internal class EndpointSetup(
    private val bridge: AppBridge,
    private val secrets: SecretStore,
    private val subnet: suspend () -> String?,
    private val keep: suspend (slug: String, keyEnv: String, key: ByteArray) -> Unit,
    private val forget: suspend (slug: String, keyEnv: String) -> Unit,
    private val onDefaultGone: suspend () -> Unit,
) {
    private companion object {
        /** What an endpoint's base URL starts with; anything else is a host for Find server. */
        val WEB_URL = Regex("^https?://", RegexOption.IGNORE_CASE)
    }

    /** The form on the saved endpoint [id] of [list]; saving it keeps its id (and its key unless a new one is typed). */
    fun edit(list: SetupStep.Providers, id: String): SetupStep.Endpoint? =
        list.saved.firstOrNull { it.id == id }?.let { SetupStep.Endpoint(editing = it) }

    /**
     * Asks for the model server behind [address] (a host, host:port or URL) and its models. [key] is used for this
     * probe only and wiped after; nothing is stored until [save]. The server found comes with the model the form then
     * offers ([suggestedModel]: [model] when it lists it); each server found counts once ([SetupStep.Endpoint.generation]).
     */
    suspend fun find(form: SetupStep.Endpoint, address: String, key: ByteArray?, model: String = ""): SetupStep.Endpoint {
        val reply = try {
            bridge.ask(BridgeCalls.PROBE_ENDPOINT,
                       ProbeEndpointParams(address.trim(), key?.takeIf { it.isNotEmpty() }?.decodeToString()))
        } finally {
            key?.fill(0)
        }
        return when (val probe = serverProbe(reply)) {
            is ServerProbe.Found -> form.copy(found = probe.server, scan = null,
                                              suggestedModel = suggestedModel(model, probe.server), generation = form.generation + 1)
            is ServerProbe.Failed -> throw SetupRefusal(problem(probe.failure))
        }
    }

    /** Looks for model servers on the phone's Wi-Fi network (only when the user asks: it knocks on every host). */
    suspend fun search(form: SetupStep.Endpoint): SetupStep.Endpoint {
        val cidr = subnet() ?: throw SetupRefusal("Connect to a Wi-Fi network to search it.")
        val found = bridge.ask(BridgeCalls.DISCOVER_ENDPOINTS, DiscoverEndpointsParams(cidr)).found
        if (found.isEmpty()) throw SetupRefusal("No model server answered on this network.")
        return form.copy(scan = found)
    }

    /**
     * Deletes the saved endpoint [id] and forgets its key, on the phone and at the core (L10). When it was the default
     * model, the chat starts afresh ([onDefaultGone]), whether or not the forget failed: its live session would keep
     * the model it was opened with (L11).
     */
    suspend fun delete(id: String): Deletion {
        val gone = bridge.ask(BridgeCalls.DELETE_CUSTOM_ENDPOINT, DeleteCustomEndpointParams(id))
        val notForgotten = try {
            forget(id, gone.keyEnv)  // always the endpoint's own slot, held or not
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            failed
        }
        if (gone.wasDefault) onDefaultGone()
        return Deletion(gone.wasDefault, notForgotten)
    }

    /**
     * Saves the endpoint (the one [form] edits keeps its id) and keeps a newly typed [key] (the caller's copy is
     * wiped; with none, an edited endpoint keeps the key it had, and a slot the save left unnamed is forgotten).
     * Answers the provider the saved endpoint now is, offering [model].
     */
    suspend fun save(form: SetupStep.Endpoint, name: String, baseUrl: String, model: String, key: ByteArray?): ProviderChoice {
        if (!WEB_URL.containsMatchIn(baseUrl.trim())) {  // a host (spark-98d1) is not yet a server's URL: Find server makes one
            key?.fill(0)
            throw SetupRefusal("That is a host, not a server's URL: tap Find server to look it up.")
        }
        // has_key: a NEW key goes to the keystore. Without one, the Python half keeps an edited entry's key_env.
        val newKey = key != null && key.isNotEmpty()
        val entry = try {
            bridge.ask(BridgeCalls.SAVE_CUSTOM_ENDPOINT, SaveCustomEndpointParams(form.editing?.id, name, baseUrl, model, newKey))
        } catch (refused: CoreError) {
            key?.fill(0)
            throw refused
        } catch (failed: RuntimeException) {  // a bridge failure carries a traceback, not words for the user
            key?.fill(0)
            throw SetupRefusal("Could not save the endpoint (${failed.javaClass.simpleName}).")
        }
        if (newKey) {
            keep(entry.id, entry.keyEnv, key!!)
        } else {
            entry.forget?.let { forget(entry.id, it) }  // a keyless re-save: its old key must not outlive the entry
        }
        return ProviderChoice(entry.id, name, entry.keyEnv.ifEmpty { null }, true, listOf(model))
    }

    /** The saved endpoints; a list that cannot be read is shown empty (rather than blocking setup) and forgets nothing. */
    suspend fun saved(): List<SavedEndpoint> {
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
}

/**
 * The model the form offers once [server] answers: the one [typed] when the server lists it, else the server's first.
 * A model picked from another server (or a typo) would be saved with this server's address.
 */
internal fun suggestedModel(typed: String, server: FoundServer): String =
    typed.trim().takeIf { it in server.models } ?: server.models.firstOrNull() ?: typed.trim()

/** Why the server at the typed address cannot be set up as it is, in words for the user. */
internal fun problem(outcome: EndpointProbe): String = when (outcome) {
    EndpointProbe.UNREACHABLE -> "Nothing answered at that address. Check the address and that the server is running."
    EndpointProbe.NEEDS_KEY -> "This server needs an API key."
    EndpointProbe.REFUSED ->
        "The server refused this address. Ollama answers only to its own machine's names: use its short name, e.g. spark-98d1."
    EndpointProbe.NO_MODELS -> "The server answered but lists no models. Type the model name instead."
}
