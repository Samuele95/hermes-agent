package com.nousresearch.hermes.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** The app's calls into its own Python half: `hermes_android.<function>(arg)`, JSON strings in and out. */
fun interface AppBridge {
    suspend fun call(function: String, arg: String): String

    /** The keystore changed: the core re-reads its secrets (variables no longer held are revoked). */
    suspend fun secretsChanged() {
        call("secrets_changed", "")
    }
}

/** [AppBridge] over this process's embedded interpreter, called off the caller's thread. */
object EmbeddedBridge : AppBridge {
    override suspend fun call(function: String, arg: String): String =
        withContext(Dispatchers.IO) { EmbeddedPython.call(function, arg) }
}

/** The bridge's replies decode leniently: a field the app does not read yet is no failure. */
val bridgeJson = Json { ignoreUnknownKeys = true }

/** A request object for [AppBridge.call]: strings and numbers, by field name. */
fun request(vararg fields: Pair<String, Any>): JsonObject =
    JsonObject(fields.associate { (name, value) -> name to if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString()) })

/**
 * Calls [function] and decodes its reply as [T]. An `{error}` reply is the Python side's words for the user and
 * comes back as a [CoreError]; a reply that is not a [T] (a renamed field, a value [T] does not know) fails to
 * decode rather than being read as something it is not.
 */
suspend inline fun <reified T> AppBridge.ask(function: String, request: JsonObject = JsonObject(emptyMap())): T {
    val answer = bridgeJson.parseToJsonElement(call(function, request.toString())).jsonObject
    (answer["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { throw CoreError(0, it.content) }
    return bridgeJson.decodeFromJsonElement(answer)
}
