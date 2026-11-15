package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.SettingsReadReply
import com.nousresearch.hermes.bridge.contract.SettingsWriteParams
import com.nousresearch.hermes.bridge.contract.Personality as WirePersonality

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

/** The core's neutral personality, its answer to `config.get personality` when none is chosen (`methods_config.py:203`). */
const val NO_PERSONALITY = "none"

/** One personality the core offers: its [name] (what `config.set personality` takes) and its preview line. */
data class Personality(val name: String, val description: String)

/**
 * What the core last answered to `settings_read` (EJ-17): an immutable snapshot, replaced whole by the re-read after
 * each write and never patched by the edit. The lists are in the core's order (D9: after a removal the core's
 * reconcile has sorted the allowlist; the app never sorts).
 */
data class ConfigSettings(val approvalTimeoutS: Int, val allowlist: List<String>, val memoryEnabled: Boolean,
                          val profileEnabled: Boolean, val memoryChars: Int, val profileChars: Int,
                          val personalities: List<Personality>) {
    companion object {
        /** The bridge's [reply], field for field (§5: no screen reads the wire type). Total. */
        fun of(reply: SettingsReadReply): ConfigSettings =
            ConfigSettings(reply.approvalsTimeoutS, reply.commandAllowlist, reply.memoryEnabled, reply.userProfileEnabled,
                           reply.memoryCharLimit, reply.userCharLimit,
                           reply.personalities.map { p: WirePersonality -> Personality(p.name, p.description) })
    }
}

/**
 * One change the user made on the page (EJ-17, EJ-62): only the fields the user touched; null leaves a field as the
 * core has it. [removeAllowed] are allowlist patterns to take out. The closed key set is the whole of what
 * `settings_write` may change (DC-M15): the mode and the personality are never here (they go through `config.set`).
 */
data class SettingsEdit(val approvalTimeoutS: Int? = null, val memoryEnabled: Boolean? = null, val profileEnabled: Boolean? = null,
                        val memoryChars: Int? = null, val profileChars: Int? = null, val removeAllowed: List<String> = emptyList()) {
    /** The bridge's params for this change, field for field. */
    fun params(): SettingsWriteParams =
        SettingsWriteParams(approvalsTimeoutS = approvalTimeoutS, memoryEnabled = memoryEnabled, userProfileEnabled = profileEnabled,
                            memoryCharLimit = memoryChars, userCharLimit = profileChars, allowlistRemove = removeAllowed)
}

/**
 * The settings page. [approvals] is null when the core's mode could not be read: no mode is shown as the one in force.
 * [personality] is the core's word (a name, or [NO_PERSONALITY]) and [config] its `settings_read`, each null when it
 * could not be read. [confirmRemove] the allowlist pattern a removal waits to be confirmed for; [nextStart] while a
 * removal waits for the always-on gateway's next start (the pid it was made under still runs; F4, Dv31). [busy] while
 * one write runs; [error] the core's words for the last failure.
 */
data class SettingsUi(val approvals: ApprovalMode? = null, val personality: String? = null, val config: ConfigSettings? = null,
                      val confirmRemove: String? = null, val nextStart: Boolean = false, val busy: Boolean = false,
                      val error: String? = null)
