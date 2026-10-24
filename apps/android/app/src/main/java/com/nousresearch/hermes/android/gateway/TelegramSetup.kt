package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The phone's Telegram bot as the gateway screen shows it; [bot] is known once Telegram confirmed the token.
 * [sdk] is false for a build without the Telegram library, which the app cannot add later.
 */
data class TelegramState(val tokenHeld: Boolean = false, val allowedUser: String? = null, val bot: String? = null,
                         val busy: Boolean = false, val error: String? = null, val sdk: Boolean = true)

/**
 * The phone's own Telegram bot for the always-on gateway (D11, D24). The token goes to the keystore as
 * `TELEGRAM_BOT_TOKEN` (the core reads it through its secret source, [secretsChanged]); one numeric user id may
 * talk to the bot. The id is set first, so a wrong one stores nothing, and a token Telegram refuses is deleted
 * again. [bridge] calls the app's Python side (`hermes_android.telegram_*`, JSON in and out). [state] is null
 * while closed. A running gateway reads the keystore when it starts, so a change applies at its next start.
 */
class TelegramSetup(
    private val secrets: SecretStore,
    private val secretsChanged: suspend () -> Unit,
    private val bridge: suspend (function: String, arg: String) -> String,
) {
    private companion object {
        const val TOKEN = "TELEGRAM_BOT_TOKEN"
    }

    private val setup = MutableStateFlow<TelegramState?>(null)
    val state: StateFlow<TelegramState?> = setup.asStateFlow()

    suspend fun open() {
        setup.value = try {
            val status = call("telegram_status")
            TelegramState(tokenHeld = status["token"]?.toString() == "true", allowedUser = status.text("allowed_user"),
                          sdk = status["sdk"]?.toString() != "false")
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
            val allowed = call("telegram_configure", "allowed_user" to user)
            allowed.text("error")?.let { return fail(it) }
            if (token != null) {
                secrets.put(TOKEN, token)
                secretsChanged()
            }
            val checked = call("telegram_check")
            checked.text("error")?.let { refused ->
                if (token != null) {
                    secrets.delete(TOKEN)
                    secretsChanged()
                }
                setup.update { it?.copy(tokenHeld = secrets.get(TOKEN) != null, allowedUser = allowed.text("allowed_user")) }
                return fail(refused)
            }
            setup.update { it?.copy(tokenHeld = true, allowedUser = allowed.text("allowed_user"), bot = checked.text("bot"), busy = false) }
        } catch (failed: Exception) {
            fail(forUser(failed))
        } finally {
            token?.fill(0)
        }
    }

    private fun fail(reason: String) {
        setup.update { it?.copy(busy = false, error = reason) }
    }

    private suspend fun call(function: String, vararg fields: Pair<String, String>): JsonObject =
        Json.parseToJsonElement(bridge(function, JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) }).toString())).jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
