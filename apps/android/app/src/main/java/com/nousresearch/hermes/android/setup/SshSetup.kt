package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The SSH host the app's terminal runs on. */
data class SshTarget(val host: String, val user: String, val port: Int)

sealed interface SshStep {
    /** Host, user and port to reach. */
    data object Host : SshStep

    /** The host's key, seen for the first time: the user compares [fingerprint] before it is trusted. */
    data class Fingerprint(val target: SshTarget, val key: String, val fingerprint: String) : SshStep

    /** The host is pinned and the terminal runs on it; [publicKey] goes into its `authorized_keys`. */
    data class Ready(val target: SshTarget, val publicKey: String, val loginOk: Boolean = false) : SshStep
}

data class SshState(val step: SshStep = SshStep.Host, val busy: Boolean = false, val error: String? = null)

/**
 * The SSH host setup (D22). The app's Ed25519 identity is created once and kept in [secrets], which
 * the core reads as `TERMINAL_SSH_PRIVATE_KEY` ([secretsChanged]); a host is trusted only after the
 * user has seen its fingerprint, and a host pinned to another key is refused. [bridge] calls the
 * app's Python side (`hermes_android.ssh_*`, JSON in and out). [state] is null while setup is closed.
 */
class SshSetup(
    private val secrets: SecretStore,
    private val secretsChanged: suspend () -> Unit,
    private val bridge: suspend (function: String, arg: String) -> String,
) {
    private companion object {
        const val IDENTITY = "TERMINAL_SSH_PRIVATE_KEY"
    }

    private val setup = MutableStateFlow<SshState?>(null)
    val state: StateFlow<SshState?> = setup.asStateFlow()

    fun open() {
        setup.value = SshState()
    }

    fun close() {
        setup.value = null
    }

    /** Looks at [target]'s host key without logging in; creates the app's identity first if there is none. */
    suspend fun look(target: SshTarget) = step {
        ensureIdentity()
        val seen = call("ssh_host_key", "host" to target.host, "port" to target.port)
        when (seen.text("pinned")) {
            "yes" -> configured(target)
            "changed" -> throw StepFailed("${target.host}:${target.port} presents a different host key than the one " +
                                          "pinned. If the host was really reinstalled, remove its line from known_hosts first.")
            else -> SshStep.Fingerprint(target, seen.text("key")!!, seen.text("fingerprint")!!)
        }
    }

    /** The user confirmed the fingerprint on screen: pin that key and point the terminal at the host. */
    suspend fun trust() = step {
        val seen = setup.value?.step as? SshStep.Fingerprint ?: return@step null
        call("ssh_trust_host", "host" to seen.target.host, "port" to seen.target.port, "key" to seen.key)
        configured(seen.target)
    }

    /** Logs in once with the app's key, so the user knows the public key reached `authorized_keys`. */
    suspend fun checkLogin() = step {
        val ready = setup.value?.step as? SshStep.Ready ?: return@step null
        call("ssh_check_login", "host" to ready.target.host, "user" to ready.target.user, "port" to ready.target.port)
        ready.copy(loginOk = true)
    }

    private suspend fun ensureIdentity() {
        if (secrets.get(IDENTITY) != null) return
        val pair = call("ssh_new_key")
        secrets.put(IDENTITY, pair.text("private")!!.toByteArray())
        secretsChanged()
    }

    private suspend fun configured(target: SshTarget): SshStep.Ready {
        call("ssh_configure", "host" to target.host, "user" to target.user, "port" to target.port)
        return SshStep.Ready(target, call("ssh_public_key").text("public")!!)
    }

    private class StepFailed(message: String) : Exception(message)

    /** Runs one step: busy while it runs, its result as the next step, or its failure in the user's words. */
    private suspend fun step(block: suspend () -> SshStep?) {
        setup.update { it?.copy(busy = true, error = null) }
        try {
            val next = block()
            setup.update { it?.copy(step = next ?: it.step, busy = false) }
        } catch (failed: StepFailed) {
            setup.update { it?.copy(busy = false, error = failed.message) }
        }
    }

    /** A bridge call; an `{error}` answer becomes the step's failure. */
    private suspend fun call(function: String, vararg fields: Pair<String, Any>): JsonObject {
        val request = JsonObject(fields.associate { (name, value) ->
            name to if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString())
        })
        val answer = Json.parseToJsonElement(bridge(function, request.toString())).jsonObject
        answer.text("error")?.let { throw StepFailed(it) }
        return answer
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
