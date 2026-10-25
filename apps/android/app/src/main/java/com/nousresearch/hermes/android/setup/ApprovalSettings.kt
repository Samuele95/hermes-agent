package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.ConfigGetParams
import com.nousresearch.hermes.bridge.protocol.ConfigSetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive

/** The core's `approvals.mode`: who approves a command the agent wants to run that the core flags as risky. */
enum class ApprovalMode(val wire: String) {
    /** The user, every time. */
    MANUAL("manual"),

    /** A second model approves the clearly safe, refuses the clearly destructive, and asks the user otherwise. */
    SMART("smart"),

    /** Nobody: flagged commands run without asking. */
    OFF("off");

    companion object {
        /** The mode the core reports; anything else reads as the core's own fallback, manual. */
        fun of(wire: String?): ApprovalMode = entries.firstOrNull { it.wire == wire } ?: MANUAL
    }
}

data class SettingsState(val approvals: ApprovalMode, val busy: Boolean = false, val error: String? = null)

/**
 * The app's settings that live in the core's config (Dv22): read and written over the protocol, like the
 * desktop's, so the core stays the one owner. [state] is null while the screen is closed.
 */
class ApprovalSettings(private val core: CoreGateway) {
    private companion object {
        const val KEY = "approvals.mode"
    }

    private val screen = MutableStateFlow<SettingsState?>(null)
    val state: StateFlow<SettingsState?> = screen.asStateFlow()

    suspend fun open() {
        screen.value = try {
            SettingsState(ApprovalMode.of(core.call(Methods.CONFIG_GET, ConfigGetParams(key = KEY)).value))
        } catch (failed: Exception) {
            SettingsState(ApprovalMode.MANUAL, error = forUser(failed))
        }
    }

    fun close() {
        screen.value = null
    }

    /** Writes [mode] for every session from now on; a refusal keeps the shown mode and says why. */
    suspend fun choose(mode: ApprovalMode) {
        screen.update { it?.copy(busy = true, error = null) }
        try {
            core.call(Methods.CONFIG_SET, ConfigSetParams(key = KEY, value = JsonPrimitive(mode.wire)))
            screen.update { it?.copy(approvals = mode, busy = false) }
        } catch (failed: Exception) {
            screen.update { it?.copy(busy = false, error = forUser(failed)) }
        }
    }
}
