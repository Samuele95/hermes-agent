package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.HubOfficial
import com.nousresearch.hermes.bridge.protocol.SkillBrowseItem
import com.nousresearch.hermes.bridge.protocol.SkillHubHit

/**
 * One skill the Skills Hub answered (capabilities-08): an official skill shipped beside the core (`hub_official`), a
 * search hit or a browsed row (`skills.manage`). [source] and [trust] as the core says them ("official", "skills-sh",
 * "community", …), null where the answer has none; [identifier] the hub's own id, which a search hit lacks; [category]
 * an official skill's (D6). The core owns every field (§2.5).
 */
data class HubItem(val name: String, val description: String, val source: String?, val trust: String?, val identifier: String?,
                   val category: String? = null) {
    /** What the skill is opened by: the hub's identifier, else its name (a search hit, D6), which `inspect` resolves. */
    val key: String get() = identifier ?: name

    /** Whether the core's official index ships it (installed with no network). */
    val official: Boolean get() = source == "official"

    /**
     * Whether a search for [query] finds this skill ([SkillRow.matches]'s rule): the trimmed, case-folded query in its
     * name, description or category; a blank query finds every skill. Pure and total.
     */
    fun matches(query: String): Boolean {
        val wanted = query.trim().lowercase()
        return wanted.isEmpty() || listOfNotNull(name, description, category).any { it.lowercase().contains(wanted) }
    }

    companion object {
        /** An official skill as `hub_official` lists it (§5: no screen reads the wire type). Total. */
        fun of(row: HubOfficial): HubItem = HubItem(row.name, row.description, "official", null, row.identifier, row.category)

        /** A search hit: the core answers a name and a description only (`SkillHubHit`). Total. */
        fun of(hit: SkillHubHit): HubItem = HubItem(hit.name, hit.description, null, null, null)

        /** A browsed row; the core's blank source or identifier is none. Total. */
        fun of(row: SkillBrowseItem): HubItem =
            HubItem(row.name, row.description, row.source.ifBlank { null }, row.trust, row.identifier?.ifBlank { null })
    }
}

/** What a search or a browse answered (D7): the [items], the search's [query], or for a browse (null query) its [page] of [pages]. */
data class HubResults(val items: List<HubItem>, val query: String? = null, val page: Long = 1, val pages: Long = 1)

/**
 * The hub page: [query] the field's text, which only narrows the official list until it is searched; [official] the
 * official skills (`hub_official`), null while read or when they could not be; [items] the last search or browse;
 * [installed] the names `skills_list` lists (D8); [busy] while the core is asked; [error] its words. Hub results are the
 * core's and never stored (§2.5); no screen reads a wire type (§5).
 */
data class HubUi(val query: String = "", val official: List<HubItem>? = null, val items: HubResults? = null,
                 val installed: Set<String> = emptySet(), val busy: Boolean = false, val error: String? = null)

/** "Available to install": the official skills not installed (by name, D8), narrowed by the [HubUi.query]; null while none are read. Pure. */
fun HubUi.available(): List<HubItem>? = official?.filter { it.name !in installed && it.matches(query) }
