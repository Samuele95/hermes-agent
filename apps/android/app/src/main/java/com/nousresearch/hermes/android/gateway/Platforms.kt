package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.bridge.contract.PlatformEntry

/**
 * What the phone's bot is doing (D39 F3, bet 7): one of the core's own adapter words, as its `gateway_state.json`
 * entry writes them, or a state the app derives when that entry does not describe the running gateway.
 */
enum class PlatformState {
    // The core's words.
    CONNECTED, CONNECTING, RETRYING, FATAL, PAUSED, DISABLED,

    // Derived by the app.
    NOT_CONFIGURED, GATEWAY_OFF, PENDING_RESTART,

    /** A word the app does not know (for example the core's `disconnected`). */
    UNKNOWN;

    companion object {
        /** The core's [word] exactly as it writes it; anything else, a derived name included, is [UNKNOWN] (DC-J13). */
        fun of(word: String?): PlatformState = when (word) {
            "connected" -> CONNECTED
            "connecting" -> CONNECTING
            "retrying" -> RETRYING
            "fatal" -> FATAL
            "paused" -> PAUSED
            "disabled" -> DISABLED
            else -> UNKNOWN
        }
    }
}

/** The bot's [state], with the core's own [message] (`error_message`) and [since] (`updated_at`) when the entry is believed. */
data class PlatformLive(val state: PlatformState, val message: String? = null, val since: String? = null)

/**
 * The bot's live state (D39 F3, bet 7; D38). [entry] is the core's own `platforms.telegram` entry, passed on as the
 * core wrote it (J3); whether it describes the running gateway is decided here, by its `writer_pid` against the
 * app's own record ([seen]), and nowhere else. The Dashboard route's ladder, with the app's liveness in place of the
 * core's: off beats everything, then no token, then a gateway that does not run, then an entry another process (or
 * none) wrote. Derived states carry no message: a dead writer's words are history.
 */
fun telegramLive(enabled: Boolean, tokenHeld: Boolean, seen: GatewaySeen, entry: PlatformEntry?): PlatformLive = when {
    !enabled -> PlatformLive(PlatformState.DISABLED)
    !tokenHeld -> PlatformLive(PlatformState.NOT_CONFIGURED)
    seen.status != GatewayStatus.RUNNING -> PlatformLive(PlatformState.GATEWAY_OFF)
    // A null pid on either side is never a match (V9).
    entry == null || seen.pid == null || entry.writerPid != seen.pid -> PlatformLive(PlatformState.PENDING_RESTART)
    else -> PlatformLive(PlatformState.of(entry.state), entry.errorMessage, entry.updatedAt)
}
