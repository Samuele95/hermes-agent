package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.TelegramCheck
import com.nousresearch.hermes.bridge.contract.TelegramConfigureParams

/**
 * The phone's Telegram bot as the gateway screen shows it; [bot] is known once Telegram confirmed the token.
 * [sdk] is false for a build without the Telegram library, which the app cannot add later.
 */
data class TelegramState(val tokenHeld: Boolean = false, val allowedUser: String? = null, val bot: String? = null,
                         val busy: Boolean = false, val error: String? = null, val sdk: Boolean = true)

/**
 * The phone's own Telegram bot for the always-on gateway (D11, D24). The token goes to the keystore as
 * `TELEGRAM_BOT_TOKEN` (the core reads it through its secret source, re-read through [bridge]); one numeric user id may
 * talk to the bot. The id is set first, so a wrong one stores nothing, and a token Telegram refuses is deleted
 * again. [bridge] calls the app's Python side (the `telegram_*` calls of the bridge contract). [state] is null
 * while closed. A running gateway reads the keystore when it starts, so a change applies at its next start.
 */
class TelegramSetup(
    private val secrets: SecretStore,
    private val bridge: AppBridge,
) {
    private companion object {
        const val TOKEN = "TELEGRAM_BOT_TOKEN"
    }

    private val setup = MutableStateFlow<TelegramState?>(null)
    val state: StateFlow<TelegramState?> = setup.asStateFlow()

    suspend fun open() {
        setup.value = try {
            val status = bridge.ask(BridgeCalls.TELEGRAM_STATUS)
            TelegramState(tokenHeld = status.token, allowedUser = status.allowedUser, sdk = status.sdk)
        } catch (failed: Exception) {
            TelegramState(error = forUser(failed))
        }
    }

    fun close() {
        setup.value = null
    }

    /** Allows [user] and, when [token] is given, keeps it in place of the one held; the caller's bytes are wiped. */
    suspend fun save(token: ByteArray?, user: String) {
        setup.update { it?.copy(busy = true, error = null) }
        try {
            val allowed = bridge.ask(BridgeCalls.TELEGRAM_CONFIGURE, TelegramConfigureParams(user)).allowedUser
            if (token != null) {
                secrets.put(TOKEN, token)
                bridge.secretsChanged()
            }
            val checked = bridge.ask(BridgeCalls.TELEGRAM_CHECK)
            when (checked.check) {
                TelegramCheck.OK -> setup.update { it?.copy(tokenHeld = true, allowedUser = allowed, bot = checked.bot, busy = false) }
                // Only a token Telegram itself refused is taken back; one it could not be asked about may be good.
                TelegramCheck.REFUSED -> {
                    if (token != null) {
                        secrets.delete(TOKEN)
                        bridge.secretsChanged()
                    }
                    setup.update { it?.copy(tokenHeld = secrets.contains(TOKEN), allowedUser = allowed, bot = null) }
                    fail(checked.message.orEmpty())
                }
                TelegramCheck.UNREACHABLE -> {
                    setup.update { it?.copy(tokenHeld = secrets.contains(TOKEN), allowedUser = allowed, bot = null) }
                    fail(checked.message.orEmpty())
                }
            }
        } catch (failed: Exception) {
            fail(forUser(failed))
        } finally {
            token?.fill(0)
        }
    }

    private fun fail(reason: String) {
        setup.update { it?.copy(busy = false, error = reason) }
    }
}
