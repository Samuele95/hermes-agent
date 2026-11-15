package com.nousresearch.hermes.android.settings

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

/** The settings page. [approvals] is null when the core's mode could not be read: no mode is shown as the one in force. */
data class SettingsUi(val approvals: ApprovalMode?, val busy: Boolean = false, val error: String? = null)
