package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.SecretStore
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.BridgeSecrets
import com.nousresearch.hermes.bridge.contract.PlatformEntry
import com.nousresearch.hermes.bridge.contract.TelegramCheck
import com.nousresearch.hermes.bridge.contract.TelegramConfigureParams
import com.nousresearch.hermes.bridge.contract.TelegramEnableParams
import com.nousresearch.hermes.bridge.protocol.Events
import kotlin.coroutines.cancellation.CancellationException

/**
 * The phone's Telegram bot as the gateway screen shows it; [bot] is known once Telegram confirmed the token.
 * [sdk] is false for a build without the Telegram library, which the app cannot add later. [enabled] is config.yaml's
 * `platforms.telegram.enabled` as the core reads it, [live] what the bot is doing ([telegramLive]), [confirmRemove]
 * while Remove the bot waits to be confirmed, and [nextStart] while a switch or a removal waits for the gateway's
 * next start (the pid it was made under still runs).
 */
data class TelegramState(val tokenHeld: Boolean = false, val allowedUser: String? = null, val bot: String? = null,
                         val busy: Boolean = false, val error: String? = null, val sdk: Boolean = true,
                         val enabled: Boolean = true, val live: PlatformLive? = null, val confirmRemove: Boolean = false,
                         val nextStart: Boolean = false)

/** This state with its live line judged against the gateway seen [now], and the mark kept while [under] is its pid. */
private fun TelegramState.judged(now: GatewaySeen, entry: PlatformEntry?, under: Int?): TelegramState =
    copy(live = telegramLive(enabled, tokenHeld, now, entry), nextStart = under != null && now.pid == under)

/** Switching the bot: busy, or null when not admitted (busy, or a removal waiting to be confirmed). */
private fun TelegramState.switching(): TelegramState? = if (busy || confirmRemove) null else copy(busy = true, error = null)

/** Removing the bot as confirmed: busy with the confirmation gone, or null when not admitted. */
private fun TelegramState.removing(): TelegramState? =
    if (busy || !confirmRemove) null else copy(confirmRemove = false, busy = true, error = null)

/**
 * The phone's own Telegram bot for the always-on gateway (D11, D24). The token goes to the keystore as
 * `TELEGRAM_BOT_TOKEN` (the core reads it through its secret source, re-read through [bridge]); one numeric user id may
 * talk to the bot. The id is set first, so a wrong one stores nothing, and a token Telegram refuses is deleted
 * again. [bridge] calls the app's Python side (the `telegram_*` calls of the bridge contract). [state] is null
 * while closed. A running gateway reads the keystore when it starts, so a change applies at its next start.
 *
 * The bot's live state is the core's `gateway_state.json` entry judged by [telegramLive] against [seen] (the app's
 * own record of the gateway), read again on each `platforms.changed` ([follow]) and by [refreshLive] (D39 F3). The
 * switch is config.yaml's `platforms.telegram.enabled` through `telegram_enable`, and turns on only while the
 * keystore holds a token (D40). Remove the bot switches it off first, then deletes the token (D40: never
 * `enabled: true` without a token). Both are marked for the gateway's next start until its pid changes (Dv31);
 * nothing here restarts it.
 */
class TelegramSetup(
    private val secrets: SecretStore,
    private val bridge: AppBridge,
    private val seen: () -> GatewaySeen,
) {
    private companion object {
        const val TOKEN = BridgeSecrets.TELEGRAM_BOT_TOKEN
    }

    private val setup = MutableStateFlow<TelegramState?>(null)
    val state: StateFlow<TelegramState?> = setup.asStateFlow()

    /** The core's last entry for the bot, as read. */
    @Volatile private var entry: PlatformEntry? = null

    /** The pid of the gateway process live when the switch or a removal was last changed (null: nothing to mark). */
    private val nextStartUnder = AtomicReference<Int?>(null)

    suspend fun open() {
        setup.value = try {
            val status = bridge.ask(BridgeCalls.TELEGRAM_STATUS)
            entry = status.live
            val now = seen()
            TelegramState(tokenHeld = status.token, allowedUser = status.allowedUser, sdk = status.sdk, enabled = status.enabled)
                .judged(now, entry, mark(now))
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
            val held = token != null && secrets.contains(TOKEN)  // a token is replaced, not added (V6)
            if (token != null) {
                secrets.put(TOKEN, token)
                bridge.secretsChanged()
            }
            val checked = bridge.ask(BridgeCalls.TELEGRAM_CHECK)
            when (checked.check) {
                TelegramCheck.OK -> show { it.copy(tokenHeld = true, allowedUser = allowed, bot = checked.bot, busy = false) }
                // Only a token Telegram itself refused is taken back; one it could not be asked about may be good.
                TelegramCheck.REFUSED -> {
                    // A held token's replacement may have the bot on: it is switched off before it goes (V6, D40).
                    val off = token != null && held
                    if (off) {
                        takeBack()
                    } else if (token != null) {
                        secrets.delete(TOKEN)
                        bridge.secretsChanged()
                    }
                    val stillHeld = secrets.contains(TOKEN)
                    show { it.copy(tokenHeld = stillHeld, allowedUser = allowed, bot = null, enabled = it.enabled && !off) }
                    fail(checked.message.orEmpty())
                }
                TelegramCheck.UNREACHABLE -> {
                    val stillHeld = secrets.contains(TOKEN)
                    show { it.copy(tokenHeld = stillHeld, allowedUser = allowed, bot = null) }
                    fail(checked.message.orEmpty())
                }
            }
        } catch (failed: Exception) {
            fail(forUser(failed))
        } finally {
            token?.fill(0)
        }
    }

    /**
     * Reads the bot again (token, switch and the core's entry) and judges it against the gateway as seen now; a closed
     * setup asks nothing. A failed read keeps what is shown.
     */
    suspend fun refreshLive() {
        if (setup.value == null) return
        val status = try {
            bridge.ask(BridgeCalls.TELEGRAM_STATUS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        entry = status.live
        show { it.copy(tokenHeld = status.token, enabled = status.enabled) }
    }

    /** Reads the bot again on each `platforms.changed` until cancelled (the page runs it while it shows); other signals are not the bot's. */
    suspend fun follow(events: Flow<CoreEvent>) {
        events.collect { if (it.decode(Events.PLATFORMS_CHANGED) != null) refreshLive() }
    }

    /**
     * Switches the bot on or off in config.yaml through `telegram_enable`, for the gateway's next start. On only while
     * the keystore holds a token, read at the tap (D40: on without a token stops the whole gateway); without one
     * nothing is sent. A double tap sends once.
     */
    suspend fun setEnabled(on: Boolean) {
        if (on && !secrets.contains(TOKEN)) {
            show { it.copy(tokenHeld = false) }
            return
        }
        val before = setup.getAndUpdate { it?.switching() ?: it }  // one atomic step: a double tap sends once
        before?.switching() ?: return
        holdingBusy {
            try {
                val reply = bridge.ask(BridgeCalls.TELEGRAM_ENABLE, TelegramEnableParams(on))
                val now = seen()
                nextStartUnder.set(now.pid)
                show(now) { it.copy(enabled = reply.enabled, busy = false) }
            } catch (failed: Exception) {
                fail(forUser(failed))
            }
        }
    }

    /** Asks to remove the bot (false stops asking); only a held token can be removed. Nothing is sent. */
    fun askRemove(ask: Boolean) {
        setup.update { if (it == null || it.busy) it else it.copy(confirmRemove = ask && it.tokenHeld) }
    }

    /**
     * Removes the bot [askRemove] asked about: switched off, its token deleted from the keystore and the core told
     * (DC-J15). The allowed user stays for a new token. A switch-off that fails deletes nothing.
     */
    suspend fun confirmRemove() {
        val before = setup.getAndUpdate { it?.removing() ?: it }  // one atomic step: a double tap removes once
        before?.removing() ?: return
        holdingBusy {
            try {
                takeBack()
                val now = seen()
                nextStartUnder.set(now.pid)
                val stillHeld = secrets.contains(TOKEN)
                show(now) { it.copy(tokenHeld = stillHeld, enabled = false, bot = null, busy = false) }
            } catch (failed: Exception) {
                fail(forUser(failed))
            }
        }
    }

    /**
     * D40's one rule for the app deleting the bot's token: switch the bot off first (a failure stops here and is
     * thrown), then delete the token and tell the core. No environment file, no other config write.
     */
    private suspend fun takeBack() {
        bridge.ask(BridgeCalls.TELEGRAM_ENABLE, TelegramEnableParams(false))
        secrets.delete(TOKEN)
        bridge.secretsChanged()
    }

    /** The mark's pid while the gateway seen [now] is still the one it was made under; dropped once that pid changed. */
    private fun mark(now: GatewaySeen): Int? = nextStartUnder.updateAndGet { under -> under?.takeIf { it == now.pid } }

    /** Applies [change] to an open setup and judges the result against the gateway seen [now] (read once, outside the update). */
    private inline fun show(now: GatewaySeen = seen(), crossinline change: (TelegramState) -> TelegramState) {
        val under = mark(now)
        val read = entry
        setup.update { state -> state?.let(change)?.judged(now, read, under) }
    }

    /** Runs a switch or a removal this setup entered busy for; a cancellation leaves it not busy, and is rethrown. */
    private suspend inline fun holdingBusy(change: () -> Unit) {
        try {
            change()
        } catch (cancelled: CancellationException) {
            setup.update { it?.copy(busy = false) }
            throw cancelled
        }
    }

    private fun fail(reason: String) {
        setup.update { it?.copy(busy = false, error = reason) }
    }
}
