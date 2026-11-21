package com.nousresearch.hermes.bridge

import com.nousresearch.hermes.bridge.contract.BridgeCall
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.NoParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The app's calls into its own Python half, as `hermes_android/bridge_contract.json` declares them (D26): JSON in
 * and out.
 *
 * Calls and the core's frames are ordered with each other, except calls the contract marks `blocking` (they may
 * wait on the network): a blocking call may run at the same time as frames and other calls. Cancelling the caller
 * does not stop the Python work: the call runs to its own deadline. Callers serialize their own dependent calls
 * by awaiting them, as `ProviderSetup` does through its step and busy state.
 */
fun interface AppBridge {
    suspend fun call(function: String, arg: String): String

    /** The keystore changed: the core re-reads its secrets (variables no longer held are revoked). */
    suspend fun secretsChanged() {
        ask(BridgeCalls.SECRETS_CHANGED)
    }
}

/** [AppBridge] over this process's embedded interpreter, called off the caller's thread; it routes by [BridgeCalls.BLOCKING]. */
object EmbeddedBridge : AppBridge {
    override suspend fun call(function: String, arg: String): String =
        withContext(Dispatchers.IO) { EmbeddedPython.call(function, arg) }
}

/** The bridge's JSON; the app's own records (the gateway's) also decode leniently with it. */
val bridgeJson = Json { ignoreUnknownKeys = true }

/**
 * Makes the declared [call] with [params] and decodes its reply. An `{error}` reply is the Python side's words
 * for the user and comes back as a [CoreError]; anything else the reply could be is the call's declared reply,
 * which `hermes_android.call` has checked against the same contract.
 */
suspend fun <P, R> AppBridge.ask(call: BridgeCall<P, R>, params: P): R {
    val answer = bridgeJson.parseToJsonElement(call(call.name, bridgeJson.encodeToString(call.params, params))).jsonObject
    (answer["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { throw CoreError(0, it.content) }
    return bridgeJson.decodeFromJsonElement(call.reply, answer)
}

/** Makes a declared [call] that takes no params. */
suspend fun <R> AppBridge.ask(call: BridgeCall<NoParams, R>): R = ask(call, NoParams)
