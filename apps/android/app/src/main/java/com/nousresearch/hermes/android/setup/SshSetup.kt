package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.Pinned
import com.nousresearch.hermes.bridge.contract.SshCheckLoginParams
import com.nousresearch.hermes.bridge.contract.SshConfigureParams
import com.nousresearch.hermes.bridge.contract.SshHostKeyParams
import com.nousresearch.hermes.bridge.contract.SshTrustHostParams

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
 * the core reads as `TERMINAL_SSH_PRIVATE_KEY` (re-read through [bridge]); a host is trusted only after the
 * user has seen its fingerprint, and a host pinned to another key is refused. [bridge] calls the
 * app's Python side (the `ssh_*` calls of the bridge contract). [state] is null while setup is closed.
 */
class SshSetup(
    private val secrets: SecretStore,
    private val bridge: AppBridge,
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
        val seen = bridge.ask(BridgeCalls.SSH_HOST_KEY, SshHostKeyParams(target.host, target.port))
        when (seen.pinned) {
            Pinned.YES -> configured(target)
            Pinned.CHANGED -> throw StepFailed("${target.host}:${target.port} presents a different host key than the one " +
                                               "pinned. If the host was really reinstalled, remove its line from known_hosts first.")
            Pinned.NO -> SshStep.Fingerprint(target, seen.key, seen.fingerprint)
        }
    }

    /** The user confirmed the fingerprint on screen: pin that key and point the terminal at the host. */
    suspend fun trust() = step {
        val seen = setup.value?.step as? SshStep.Fingerprint ?: return@step null
        bridge.ask(BridgeCalls.SSH_TRUST_HOST, SshTrustHostParams(seen.target.host, seen.target.port, seen.key))
        configured(seen.target)
    }

    /** Logs in once with the app's key, so the user knows the public key reached `authorized_keys`. */
    suspend fun checkLogin() = step {
        val ready = setup.value?.step as? SshStep.Ready ?: return@step null
        bridge.ask(BridgeCalls.SSH_CHECK_LOGIN, SshCheckLoginParams(ready.target.host, ready.target.user, ready.target.port))
        ready.copy(loginOk = true)
    }

    private suspend fun ensureIdentity() {
        if (secrets.contains(IDENTITY)) return
        val pair = bridge.ask(BridgeCalls.SSH_NEW_KEY)
        secrets.put(IDENTITY, pair.private.toByteArray())
        bridge.secretsChanged()
    }

    private suspend fun configured(target: SshTarget): SshStep.Ready {
        bridge.ask(BridgeCalls.SSH_CONFIGURE, SshConfigureParams(target.host, target.user, target.port))
        return SshStep.Ready(target, bridge.ask(BridgeCalls.SSH_PUBLIC_KEY).public)
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
        } catch (failed: Exception) {
            setup.update { it?.copy(busy = false, error = forUser(failed)) }
        }
    }
}
