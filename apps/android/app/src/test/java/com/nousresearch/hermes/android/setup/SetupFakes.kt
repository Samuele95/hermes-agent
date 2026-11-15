package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The fakes setup's tests share: a core that answers model.options / config.set / model.disconnect / nous.login.*,
 * a bridge answering [answers] (plus secrets_changed and save_custom_endpoint), a keystore in [held], and the
 * [ProviderSetup] under test.
 */
abstract class SetupFakes {
    protected val answers = mutableMapOf<String, String>()   // bridge calls answered by these tests
    protected val sent = mutableListOf<Pair<String, String>>()
    protected var subnet: String? = null
    protected var subnetFound: suspend () -> String? = { subnet }  // the shell's Wi-Fi adapter
    protected fun argOf(function: String) = Json.parseToJsonElement(sent.last { it.first == function }.second).jsonObject
    protected val held = mutableMapOf<String, ByteArray>()
    protected var failDeletes = 0  // the keystore's next deletes that throw
    protected val secrets = object : SecretStore {
        override fun put(name: String, value: ByteArray) { held[name] = value.copyOf() }
        override fun get(name: String) = held[name]?.copyOf()  // a fresh copy, as the keystore decrypts one
        override fun delete(name: String) {
            if (failDeletes > 0) { failDeletes--; throw IllegalStateException("keystore unavailable") }
            held.remove(name)
        }
        override fun all() = held.mapValues { it.value.copyOf() }
    }
    protected val log = mutableListOf<String>()
    protected val customs = CustomModels.InMemory()  // the model ids kept for each provider
    protected var deepseekRow = UNAUTHENTICATED_DEEPSEEK
    protected var nousRow = """{"slug":"nous","name":"Nous Portal","models":[],"authenticated":false,"auth_type":"oauth_device_code","key_env":""}"""
    protected var copilotRow = """{"slug":"copilot","name":"GitHub Copilot","models":["gpt-5"],"authenticated":true}"""
    protected val polls = ArrayDeque(listOf("""{"status":"pending"}""", """{"status":"approved"}"""))
    protected var disconnect: (JsonObject) -> String = { """{"slug":"deepseek","name":"DeepSeek","disconnected":true}""" }
    protected var configSet: (JsonObject) -> String = { """{"key":"model","value":"deepseek-v4-pro","scope":"global"}""" }
    protected var onCoreCall: (method: String) -> Unit = {}  // runs first in each core call (the reply is not suspend)
    protected val core = FakeCoreGateway { method, params ->
        onCoreCall(method)
        log += method
        when (method) {
            "setup.status" -> """{"provider_configured":false}"""
            "model.options" -> """{"providers":[$deepseekRow, $nousRow,
                {"slug":"openai-codex","name":"OpenAI Codex","models":[],"authenticated":false,"auth_type":"oauth_external","key_env":""},
                {"slug":"openrouter","name":"OpenRouter","models":[],"authenticated":false,"auth_type":"api_key","key_env":""},
                $copilotRow]}"""
            "config.set" -> configSet(params)
            "model.disconnect" -> disconnect(params)
            "nous.login.start" -> """{"login_id":"L1","user_code":"NOUS-1234","verification_url":"https://portal.test/device?user_code=NOUS-1234","expires_in":600,"poll_interval":1}"""
            "nous.login.poll" -> polls.removeFirst().also { if ("approved" in it) nousRow = READY_NOUS }
            "nous.login.cancel" -> """{"cancelled":true}"""
            else -> error("unexpected $method")
        }
    }
    protected var endpointSaved: (JsonObject) -> String = { """{"id":"host-ollama","key_env":"HERMES_CUSTOM_HOST_OLLAMA_API_KEY","forget":null}""" }
    protected var secretsChanged: () -> Unit = { log += "secrets_changed" }
    protected var onList: () -> Unit = {}  // runs while the Python half reads the list
    protected var onSave: suspend () -> Unit = {}  // runs while the Python half saves an endpoint
    protected var beforeBridge: suspend (function: String) -> Unit = {}  // runs first in each bridge call: may hold its answer
    protected val bridge = AppBridge { function, request ->
        beforeBridge(function)
        if (function == "list_custom_endpoints") onList()
        if (function == "delete_custom_endpoint") dropListed(Json.parseToJsonElement(request).jsonObject.getValue("id").jsonPrimitive.content)
        answers[function]?.let { sent += function to request; return@AppBridge it }
        when (function) {
            "secrets_changed" -> { secretsChanged(); "{}" }
            "save_custom_endpoint" -> {
                sent += function to request
                log += "save_endpoint"
                onSave()
                endpointSaved(Json.parseToJsonElement(request).jsonObject)
            }
            else -> error("unexpected bridge call $function")
        }
    }
    protected val setup = ProviderSetup(core, secrets, bridge, session = { "s1" }, reopenChat = { log += "reopen_chat" },
                                    subnet = { subnetFound() }, customModels = customs, now = { NOW_MS })

    protected fun ProviderSetup.step() = state.value?.step

    /** The list answer: the saved [rows] and every key variable config.yaml names ([keyEnvs]). */
    protected fun listing(vararg rows: String, keyEnvs: List<String> = emptyList()) =
        """{"endpoints":[${rows.joinToString(",")}],"key_envs":[${keyEnvs.joinToString(",") { "\"$it\"" }}]}"""

    /** As the Python half does: a deleted endpoint is no longer listed, nor its own slot named. */
    protected fun dropListed(id: String) {
        val listed = answers["list_custom_endpoints"]?.let { Json.parseToJsonElement(it).jsonObject } ?: return
        val kept = listed.getValue("endpoints").jsonArray.filter { it.jsonObject.getValue("id").jsonPrimitive.content != id }
        val named = listed.getValue("key_envs").jsonArray.filter { it.jsonPrimitive.content != "HERMES_CUSTOM_${id.uppercase()}_API_KEY" }
        answers["list_custom_endpoints"] = JsonObject(mapOf("endpoints" to JsonArray(kept), "key_envs" to JsonArray(named))).toString()
    }

    protected val dgx = """{"id":"dgx","name":"DGX","base_url":"http://spark-98d1:11434/v1","model":"gemma4:12b-it-q8_0","has_key":true}"""
}

/** The wall clock setup's tests run at: 2026-11-15T08:00:00Z. */
internal const val NOW_MS = 1_794_729_600_000L
internal const val READY_NOUS = """{"slug":"nous","name":"Nous Portal","models":["nous/hermes-4"],"authenticated":true}"""
internal const val SIGNED_IN_DEEPSEEK = """{"slug":"deepseek","name":"DeepSeek","models":["deepseek-v4-pro"],"authenticated":true}"""
internal const val UNAUTHENTICATED_DEEPSEEK =
    """{"slug":"deepseek","name":"DeepSeek","models":[],"authenticated":false,"auth_type":"api_key","key_env":"DEEPSEEK_API_KEY"}"""
