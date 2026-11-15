package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.SettingsReadReply
import com.nousresearch.hermes.bridge.contract.SettingsWriteParams
import com.nousresearch.hermes.bridge.contract.SkillEntry
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
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
 * A provider Hermes holds a key or sign-in for (`model.options`, F10). [removable]: Remove offers to forget it here;
 * a saved custom endpoint is deleted in provider setup instead (D3). [current]: the provider Hermes answers with.
 */
data class ConnectedProvider(val slug: String, val name: String, val removable: Boolean, val current: Boolean) {
    companion object {
        /** Rows that hold no credential: the managed local server and the MoA aggregate (D1). */
        private val NO_CREDENTIAL = setOf("local", "virtual")

        /**
         * The core's [row] as connected, or null: not signed in, the Nous free tier (D2), or nothing to remove (D1). Total.
         * The core sends `auth_type` only on rows not signed in (`inventory.py:835-849`), so removability is read from
         * `is_user_defined` (D1).
         */
        fun of(row: ModelOptionProvider): ConnectedProvider? =
            if (row.authenticated != true || row.freeTierRow == true || row.authType in NO_CREDENTIAL) null
            else ConnectedProvider(row.slug, row.name, removable = row.isUserDefined != true, current = row.isCurrent == true)
    }
}

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
 * could not be read. [connected] the core's connected providers, null when they could not be read (D5).
 * [confirmRemove] the allowlist pattern a removal waits to be confirmed for; [confirmProvider] the provider slug a
 * removal waits to be confirmed for (D4): one question at a time. [nextStart] while a removal waits for the always-on
 * gateway's next start (the pid it was made under still runs; F4, Dv31). [busy] while one write runs; [error] the
 * core's words for the last failure.
 */
data class SettingsUi(val approvals: ApprovalMode? = null, val personality: String? = null, val config: ConfigSettings? = null,
                      val connected: List<ConnectedProvider>? = null, val confirmRemove: String? = null,
                      val confirmProvider: String? = null, val nextStart: Boolean = false, val busy: Boolean = false,
                      val error: String? = null)

/** One skill as the core lists it (`skills_list`): [enabled] is the global `skills.disabled` list's answer (D7). */
data class SkillRow(val name: String, val description: String, val category: String?, val enabled: Boolean) {
    companion object {
        /** The bridge's [entry], field for field (§5: no screen reads the wire type). Total. */
        fun of(entry: SkillEntry): SkillRow = SkillRow(entry.name, entry.description, entry.category, entry.enabled)
    }
}

/** One category's skills, in the core's order; [category] null for skills with none (D9). */
data class SkillGroup(val category: String?, val rows: List<SkillRow>) {
    /** The category's switch: on when every skill in it is on (Desktop's "All", D5). */
    val on: Boolean get() = rows.all { it.enabled }

    /** The names a flip of the category's switch to [on] sends: the skills not already so (D5). */
    fun switching(on: Boolean): List<String> = rows.filter { it.enabled != on }.map { it.name }

    companion object {
        /** [rows] grouped by category in the order the core lists them. Total. */
        fun of(rows: List<SkillRow>): List<SkillGroup> = rows.groupBy { it.category }.map { (c, r) -> SkillGroup(c, r) }
    }
}

/**
 * The skills page: [rows] the core's last answer, null while read or when it could not be read (D3); [busy] while one
 * change is written; [error] the core's words.
 */
data class SkillsUi(val rows: List<SkillRow>? = null, val busy: Boolean = false, val error: String? = null)
