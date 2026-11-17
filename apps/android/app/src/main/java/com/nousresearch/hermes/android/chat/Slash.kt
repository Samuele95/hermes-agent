package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.CommandDispatchResult
import com.nousresearch.hermes.bridge.protocol.CommandsCatalogResult
import com.nousresearch.hermes.bridge.protocol.CompleteSlashResult
import com.nousresearch.hermes.bridge.protocol.DispatchType

/** A row of the slash menu: a command, a skill, or an option of the command typed (D1; Desktop's groups, `use-slash-completions.ts:277`). */
enum class SlashKind { COMMAND, SKILL, OPTION }

/** Who runs a slash line: the app's own controls, or the core's in-process `command.dispatch` (bet 9). */
enum class Runner { APP, DISPATCH }

/**
 * One row of the slash menu. [name] keeps its slash (`/github`) for a command or a skill and is the bare word for an
 * option; [fill] is the composer's line after a pick (D1): `/github `, or `/memory pending`.
 */
data class SlashItem(val name: String, val description: String, val kind: SlashKind, val runs: Runner, val fill: String)

/** A line Send runs: [name] the canonical name without its slash (`queue` for `/q`), [arg] the rest, trimmed. */
data class SlashLine(val name: String, val arg: String, val runs: Runner)

/**
 * What the core's `command.dispatch` asks the app to do, its closed `type` mapped once (EJ-62): [Send] the expanded
 * [Send.message] to the model while the bubble shows [Send.display] (never the message); [Prefill] the composer;
 * [Said] words for a notice (exec, plugin, a refusal); [Alias] run another command (D2). A [Send] or [Prefill]
 * carries the core's notice, shown before the act.
 */
sealed interface Dispatch {
    data class Send(val message: String, val display: String?, val notice: String?) : Dispatch
    data class Prefill(val text: String, val notice: String?) : Dispatch
    data class Said(val words: String) : Dispatch
    data class Alias(val target: String) : Dispatch

    companion object {
        /** An exec or plugin command that wrote nothing (`slash.ts:322`). */
        const val NO_OUTPUT = "(no output)"

        /** The core's directive [r], exhaustive over its `type`; total: it never throws on an answer. */
        fun of(r: CommandDispatchResult): Dispatch {
            val notice = r.notice?.trim()?.ifEmpty { null }
            return when (r.type) {
                DispatchType.SKILL, DispatchType.SEND -> Send(r.message.orEmpty(), r.display?.ifBlank { null }, notice)
                DispatchType.PREFILL -> Prefill(r.message.orEmpty(), notice)
                DispatchType.EXEC, DispatchType.PLUGIN -> Said(r.output?.takeIf { it.isNotBlank() } ?: NO_OUTPUT)
                DispatchType.ALIAS -> Alias(r.target.orEmpty().removePrefix("/"))
            }
        }
    }
}

/**
 * The slash commands the phone runs, read from the core's `commands.catalog` (DC-P12): the app's own six, the core's
 * built-ins `command.dispatch` handles, the user's quick and the plugins' commands, then the skills, most used first.
 * Nothing only `slash.exec` runs is in [items] (chat-21). [skillCount] is the core's. [canon] maps every lower-cased
 * name and alias the core listed to its canonical name; [known] holds every name and alias the core listed.
 */
data class SlashCatalog(val items: List<SlashItem>, val skillCount: Long, private val canon: Map<String, String>,
                        private val known: Set<String>) {
    /**
     * The line [text] runs, when it starts with the name or alias of a command or skill in [items]: the head is its
     * first word (up to any whitespace, a newline included), the arg the rest, trimmed. Anything else is text: null.
     */
    fun line(text: String): SlashLine? {
        val head = headOf(text) ?: return null
        val item = itemNamed(canonical(head)) ?: return null
        return SlashLine(item.name.removePrefix("/"), text.drop(head.length).trim(), item.runs)
    }

    /** The canonical `/name` of a line the core listed but the phone cannot run (`/status`, `/snap`; D4); else null (a path, a smiley). */
    fun unavailable(text: String): String? {
        if (line(text) != null) return null
        val head = headOf(text)?.lowercase()?.takeIf { it in known } ?: return null
        return canonical(head)
    }

    /**
     * The line [text] runs when it is a command `command.dispatch` runs, never a skill nor the app's own: one whose
     * turn the core built from a prompt of its own (`/plan`, `/goal`; D6: its bubble is the typed line), which a replay
     * of the bubble would send as typed: the core re-expands only a skill (`session_history.py:244-256`). Else null.
     */
    fun dispatchedCommand(text: String): SlashLine? =
        line(text)?.takeIf { it.runs == Runner.DISPATCH && itemNamed("/" + it.name)?.kind == SlashKind.COMMAND }

    /** The bare `/` menu: the commands in their order, then the skills. */
    fun browse(): List<SlashItem> = items.filter { it.kind == SlashKind.COMMAND } + items.filter { it.kind == SlashKind.SKILL }

    /**
     * The core's ranked completions [result] of [draft], kept only for what the phone runs (DC-P12). At the name (no
     * space yet): the commands and skills in [items] the core named, commands first, each in the core's order, filled
     * from `replace_from`. After a space: the options of a command `command.dispatch` runs; none for any other line.
     */
    fun narrowed(draft: String, result: CompleteSlashResult): List<SlashItem> {
        val completions = result.items.orEmpty()
        if (draft.none(Char::isWhitespace)) {
            return completions.mapNotNull { c ->
                itemNamed("/" + c.text.removePrefix("/"))?.copy(fill = fillOf(draft, result.replaceFrom, c.text) + " ")
            }.distinctBy { it.name }.sortedBy { it.kind }
        }
        if (!takesOptions(draft)) return emptyList()
        return completions.map { SlashItem(it.text, it.meta, SlashKind.OPTION, Runner.DISPATCH, fillOf(draft, result.replaceFrom, it.text)) }
    }

    /**
     * The menu when `complete.slash` failed: at the name, the commands and skills whose name starts with [draft]'s word
     * (or, when none does, contains it), commands first; none after a space.
     */
    fun prefixed(draft: String): List<SlashItem> {
        if (draft.any(Char::isWhitespace)) return emptyList()
        val word = draft.removePrefix("/").lowercase()
        val named = browse().map { it to it.name.removePrefix("/").lowercase() }
        return (named.filter { it.second.startsWith(word) }.ifEmpty { named.filter { word in it.second } }).map { it.first }
    }

    private fun headOf(text: String): String? =
        text.takeIf { it.startsWith("/") }?.takeWhile { !it.isWhitespace() }?.takeIf { it.length > 1 }

    private fun canonical(head: String): String = head.lowercase().let { canon[it] ?: it }

    private fun itemNamed(name: String): SlashItem? =
        items.firstOrNull { it.kind != SlashKind.OPTION && it.name.equals(name, ignoreCase = true) }

    private fun takesOptions(draft: String): Boolean {
        val line = line(draft) ?: return false
        return line.runs == Runner.DISPATCH && itemNamed("/" + line.name)?.kind == SlashKind.COMMAND
    }

    companion object {
        /** The app's own six, in this order, in the app's words (D3; Desktop's where the phone does the same, `en.ts:3444-3465`). */
        val APP_COMMANDS: List<SlashItem> = listOf(
            "/new" to "Start a new chat",
            "/model" to "Switch this chat's model",
            "/compress" to "Compress this conversation context",
            "/retry" to "Retry the last message (resend to agent)",
            "/steer" to "Inject a message after the next tool call without interrupting",
            "/queue" to "Queue a prompt for the next turn",
        ).map { (name, words) -> SlashItem(name, words, SlashKind.COMMAND, Runner.APP, "$name ") }

        /** The core's `_SLASH_BUILTINS` the phone offers (`methods_tools.py:1088-1093`), minus the app's six and `/focus`, `/snapshot`, `/skills` (D10). */
        val DISPATCHED_BUILTINS = setOf("learn", "plan", "init", "moa", "goal", "loop", "undo", "memory")

        /** The core's categories whose every command `command.dispatch` runs (`methods_tools.py:473,484`). */
        private val DISPATCHED_CATEGORIES = setOf("User commands", "Plugin commands")

        /** The catalog [r] the core answered, cut to what the phone runs (§3.3); total over its shape. */
        fun of(r: CommandsCatalogResult): SlashCatalog {
            val taken = APP_COMMANDS.mapTo(mutableSetOf()) { it.name.lowercase() }
            val commands = r.categories.orEmpty().flatMap { category ->
                category.pairs.orEmpty().mapNotNull { pair ->
                    val name = pair.firstOrNull()?.takeIf { it.startsWith("/") && it.length > 1 } ?: return@mapNotNull null
                    val runs = name.removePrefix("/").lowercase() in DISPATCHED_BUILTINS || category.name in DISPATCHED_CATEGORIES
                    if (!runs || !taken.add(name.lowercase())) return@mapNotNull null
                    SlashItem(name, pair.getOrNull(1).orEmpty(), SlashKind.COMMAND, Runner.DISPATCH, "$name ")
                }
            }
            val skills = r.skills.orEmpty()
            val skillItems = r.pairs.orEmpty().mapNotNull { pair ->
                val name = pair.firstOrNull()?.takeIf { it in skills && it.length > 1 && taken.add(it.lowercase()) } ?: return@mapNotNull null
                SlashItem(name, pair.getOrNull(1).orEmpty(), SlashKind.SKILL, Runner.DISPATCH, "$name ")
            }.sortedWith(compareByDescending<SlashItem> { skills[it.name]?.usage ?: 0 }.thenBy { it.name })
            val canon = r.canon.orEmpty().mapKeys { it.key.lowercase() }
            val known = r.pairs.orEmpty().mapNotNull { it.firstOrNull()?.lowercase() }.toSet() + canon.keys
            return SlashCatalog(APP_COMMANDS + commands + skillItems, r.skillCount, canon, known)
        }
    }
}

/**
 * The composer's line after a pick of [text] (P1's FOUND: the core's items carry no slash): [draft] up to the core's
 * `replace_from` (1 when absent), then [text], never two slashes. `/gi` + `github` → `/github`; `/memory pe` +
 * `pending` from 8 → `/memory pending`.
 */
fun fillOf(draft: String, replaceFrom: Long?, text: String): String {
    val head = draft.take((replaceFrom ?: 1L).coerceIn(0L, draft.length.toLong()).toInt())
    return head + if (head.endsWith("/")) text.removePrefix("/") else text
}

/** A draft that opens the slash menu: a `/` first, on one line (§3.3; Desktop's mid-message skills are not built). */
fun opensMenu(draft: String): Boolean = draft.startsWith("/") && '\n' !in draft

/** The pause after a keystroke before `complete.slash` is asked, a long handler (DC-P15). */
const val SLASH_DEBOUNCE_MS = 150L
