package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.ToolsetsKeysReply
import com.nousresearch.hermes.bridge.protocol.ToolsetRow as WireToolsetRow

/**
 * One toolset the phone can switch (capabilities-10, §2.3): [label] is the core's GUI title (Desktop's), [toolCount]
 * the core's `tool_count`, [enabled] whether the config's set (the one new chats build with) holds it, [needsKeys]
 * whether the core lacks a key it needs (`toolsets_keys`, DC-P25), and [tools] its tools' names, null until
 * `tools.list` is read (D3).
 */
data class ToolsetRow(val name: String, val label: String, val description: String, val toolCount: Long, val enabled: Boolean,
                      val needsKeys: Boolean, val tools: List<String>? = null) {
    /**
     * Whether a search for [query] finds this toolset: the trimmed, case-folded query in its name, label or
     * description; never its tools' names, which the list does not hold (D9). A blank query finds every toolset. Pure.
     */
    fun matches(query: String): Boolean {
        val wanted = query.trim().lowercase()
        return wanted.isEmpty() || listOf(name, label, description).any { it.lowercase().contains(wanted) }
    }

    companion object {
        /** The toolsets Desktop never lists (`apps/desktop/src/lib/desktop-toolsets.ts`, `DESKTOP_HIDDEN_TOOLSETS`). */
        val HIDDEN: Set<String> = setOf("discord", "discord_admin", "yuanbao", "context_engine", "moa")

        /**
         * The core's [rows] (`toolsets.list`) the page offers (D2): only those [keys] names switchable (what
         * `tools.configure` switches for the CLI) and not [HIDDEN], titled by the core's label, A–Z by it,
         * case-folded (D10). Total: a row the keys do not name is not offered.
         */
        fun listed(rows: List<WireToolsetRow>, keys: ToolsetsKeysReply): List<ToolsetRow> {
            val labels = keys.switchable.associate { it.name to it.label }
            val needing = keys.needsKeys.toSet()
            return rows.mapNotNull { row ->
                labels[row.name]?.takeIf { row.name !in HIDDEN }?.let { label ->
                    ToolsetRow(row.name, label, row.description, row.toolCount, row.enabled, needsKeys = row.name in needing)
                }
            }.sortedBy { it.label.lowercase() }
        }
    }
}

/** Every row on exactly when [enabled] (the core's re-read `enabled_toolsets`) names it: the core's answer, never the ask (D6). Pure. */
fun List<ToolsetRow>.switched(enabled: List<String>): List<ToolsetRow> {
    val on = enabled.toSet()
    return map { it.copy(enabled = it.name in on) }
}

/**
 * The Toolsets page: [rows] the core's last answer, null while read or when it could not be read; [query] the search
 * typed, which only narrows what is shown; [open] the toolset whose tools are shown; [busy] while one call runs, every
 * switch off meanwhile; [error] the core's words. The core owns every toolset's state, `platform_toolsets.cli` (§2.5);
 * no screen reads a wire type (§5).
 */
data class ToolsetsUi(val rows: List<ToolsetRow>? = null, val query: String = "", val open: String? = null, val busy: Boolean = false,
                      val error: String? = null)

/** The rows the page shows: the core's, narrowed by the [ToolsetsUi.query]; null while none are read. Pure. */
fun ToolsetsUi.shown(): List<ToolsetRow>? = rows?.filter { it.matches(query) }
