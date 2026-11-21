package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.HubOfficial
import com.nousresearch.hermes.bridge.contract.HubScanReply
import com.nousresearch.hermes.bridge.protocol.SkillBrowseItem
import com.nousresearch.hermes.bridge.protocol.SkillHubHit
import com.nousresearch.hermes.bridge.protocol.SkillInspectInfo

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
 * The security scan of a hub skill (`hub_scan`, D5): the core's [verdict] ("safe", "caution", "dangerous"), whether its
 * install policy [allowed] it (a policy that would ask does not), every finding most severe first, and the source's
 * [trust]. The core's rule, never overridden (D3).
 */
data class HubScan(val verdict: String, val allowed: Boolean, val findings: List<String>, val trust: String) {
    companion object {
        /** The bridge's [reply], field for field (§5). Total. */
        fun of(reply: HubScanReply): HubScan = HubScan(reply.verdict, reply.allowed, reply.findings, reply.trust)
    }
}

/**
 * A hub skill opened for its preview (`skills.manage inspect`): the [item] as the core resolved it (its [HubItem.key]
 * is the identifier a scan and an install take, D6), its [tags], the core's SKILL.md preview [skillMd]; then its [scan],
 * or the words [scanFailed] when the bridge could not run it (D5).
 */
data class HubPreview(val item: HubItem, val tags: List<String>, val skillMd: String?, val scan: HubScan? = null,
                      val scanFailed: String? = null) {
    /** Whether the scan answered or failed: Install waits for it (D1). */
    val scanned: Boolean get() = scan != null || scanFailed != null

    companion object {
        /**
         * The core's [info] for the row [listed]; null when it names nothing (the core's `{}`: not found, ambiguous or
         * unreachable look alike, D14). The core's fields win; the row's trust and category stay. Total.
         */
        fun of(info: SkillInspectInfo, listed: HubItem): HubPreview? {
            if (info.name == null && info.identifier == null) return null
            val item = HubItem(info.name?.ifBlank { null } ?: listed.name, info.description?.ifBlank { null } ?: listed.description,
                               info.source?.ifBlank { null } ?: listed.source, listed.trust,
                               info.identifier?.ifBlank { null } ?: listed.identifier, listed.category)
            return HubPreview(item, info.tags.orEmpty(), info.skillMdPreview)
        }
    }
}

/**
 * An install the core refused (D4): the skill's [name], the core's [words] with its console markup removed (D10), and
 * [byScan] when the security scan blocked it, which offers "View scan".
 */
data class HubBlock(val name: String, val words: String, val byScan: Boolean) {
    companion object {
        /** Desktop's `INSTALL_BLOCKED_CURRENT_RE`, any whitespace between its words (a wrapped line): the core's scan-block line. */
        private val SCAN_BLOCKED = Regex("""Not installed:\s+the\s+security\s+scan\s+found""", RegexOption.IGNORE_CASE)

        /** The refusal of [name] in the core's [message]. Total. */
        fun of(name: String, message: String): HubBlock {
            val words = plain(message)
            return HubBlock(name, words, SCAN_BLOCKED.containsMatchIn(words))
        }
    }
}

/** One Rich style word (`bold`, `red`, `bright_cyan`, …). */
private const val STYLE = """(?:bold|dim|italic|underline|red|green|yellow|blue|cyan|magenta|white|bright_(?:red|green|yellow|blue|cyan|magenta|white))"""

/** A Rich style tag: `[/]`, `[/<style>]` or `[<style> <style>…]`. */
private val MARKUP = Regex("""\[/(?:$STYLE(?: $STYLE)*)?]|\[$STYLE(?: $STYLE)*]""")

/**
 * [words] as the core printed them with its Rich console style tags removed (D10), trimmed; any other bracket
 * (`[1/3]`, `[skill]`) stays. Pure and total.
 */
fun plain(words: String): String = MARKUP.replace(words, "").trim()

/**
 * The hub page: [query] the field's text, which only narrows the official list until it is searched; [official] the
 * official skills (`hub_official`), null while read or when they could not be; [items] the last search or browse;
 * [installed] the names `skills_list` lists (D8); [busy] while the core is asked; [error] its words; [open] the skill
 * previewed; [installing] the identifier being installed; [blocked] the core's last refusal; [notice] the skill just
 * installed (D8). Hub results are the core's and never stored (§2.5); no screen reads a wire type (§5).
 */
data class HubUi(val query: String = "", val official: List<HubItem>? = null, val items: HubResults? = null,
                 val installed: Set<String> = emptySet(), val busy: Boolean = false, val error: String? = null,
                 val open: HubPreview? = null, val installing: String? = null, val blocked: HubBlock? = null, val notice: String? = null)

/**
 * Whether Install is offered enabled — the one definition, for the controller's admission and the screen alike: an idle
 * page, a preview open whose skill is not installed (D8), whose scan answered or failed (D1) and whose policy did not
 * refuse it (D3). Pure.
 */
fun HubUi.mayInstall(): Boolean {
    val preview = open ?: return false
    return !busy && installing == null && preview.item.name !in installed && preview.scanned && preview.scan?.allowed != false
}

/** "Available to install": the official skills not installed (by name, D8), narrowed by the [HubUi.query]; null while none are read. Pure. */
fun HubUi.available(): List<HubItem>? = official?.filter { it.name !in installed && it.matches(query) }
