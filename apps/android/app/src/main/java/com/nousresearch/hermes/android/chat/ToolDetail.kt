package com.nousresearch.hermes.android.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import kotlin.math.max

/**
 * One part of a tool call's detail, as its open row draws it: read from the [Entry.Tool] the chat already holds, never
 * a wire type (EJ-62, chat-08). [copy] is the text the part's own Copy puts on the clipboard: what it shows.
 */
sealed interface ToolSection {
    /** The text this part's Copy hands over. */
    val copy: String

    /** What the agent ran: a terminal's command or the code it executed; [exit] the terminal's exit code (null: none came, or not an integer). */
    data class Command(val text: String, val exit: Long?) : ToolSection { override val copy: String get() = text }

    /** What the tool printed or answered, as plain text, escape codes dropped (D5); a trimmed one keeps its marker. */
    data class Output(val text: String) : ToolSection { override val copy: String get() = text }

    /** The edit a tool made, as unified diff lines; [added] / [removed] count its changed lines, file headers not counted. */
    data class Diff(val text: String, val added: Int, val removed: Int) : ToolSection {
        override val copy: String get() = text

        companion object {
            /**
             * The core's `inline_diff` without its terminal chrome (D4): every ANSI escape gone, a first line opening on `┊`
             * (the review header, in any locale) dropped, blank lines trimmed at both ends; then counted as Desktop counts.
             */
            fun of(diff: String): Diff {
                val lines = stripAnsi(diff).lines().let { if (it.firstOrNull()?.trim()?.startsWith("┊") == true) it.drop(1) else it }
                    .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                return Diff(lines.joinToString("\n"), added = lines.count { it.startsWith("+") && !it.startsWith("+++") },
                            removed = lines.count { it.startsWith("-") && !it.startsWith("---") })
            }
        }
    }

    /** A web search: the [query] the agent asked and the [hits] the core returned (at most [MAX_HITS], D11). */
    data class Search(val query: String, val hits: List<Hit>) : ToolSection {
        /** One result: its [title] and [url] (either may be empty, never both), its [snippet] (null: none came). */
        data class Hit(val title: String, val url: String, val snippet: String?)

        /** The hits, one block each (title, link, snippet); with no hits, the query (Desktop's "Copy query"). */
        override val copy: String
            get() = if (hits.isEmpty()) query
            else hits.joinToString("\n\n") { listOfNotNull(it.title, it.url, it.snippet).filter(String::isNotBlank).joinToString("\n") }

        companion object {
            /** The most hits a search shows (Desktop's `extractSearchResults` limit). */
            const val MAX_HITS = 6
        }
    }

    /**
     * The call exactly as the core sent it, as display text: [args] pretty-printed (null: none), [result] pretty-printed
     * when it parses as JSON, else as it is (null: none came, or the history did not keep it). Shown behind a disclosure.
     */
    data class Payload(val args: String?, val result: String?) : ToolSection {
        /** Desktop's technical trace: "Arguments:" and "Result:" blocks, each only when present. */
        override val copy: String
            get() = listOfNotNull(args?.let { "Arguments:\n$it" }, result?.let { "Result:\n$it" }).joinToString("\n\n")
    }
}

/** Every ANSI CSI sequence (colours, cursor moves) a terminal-rendered text carries. */
private val ANSI = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

private fun stripAnsi(text: String): String = text.replace(ANSI, "")

private val Pretty = Json { prettyPrint = true }

/**
 * A tool call's detail as typed sections, and its length in words: pure and total readers over the [Entry.Tool] the
 * chat holds (FP, §4). Nothing here fetches, stores or logs: an old chat shows only what its history kept (bet 6).
 */
object ToolDetail {
    /**
     * The sections [tool] opens into, always in this order: Command, Search, Diff, Output, Payload. Each is there only
     * when the row holds what it shows; an unknown tool shows its Output and Payload. Never throws.
     */
    fun of(tool: Entry.Tool): List<ToolSection> {
        val obj = tool.result?.let(::parsed) as? JsonObject  // a trimmed JSON text no longer parses: it is text (invariant 6)
        val args = tool.args
        val sections = ArrayList<ToolSection>(5)

        val command = when (tool.name) { "terminal" -> str(args, "command"); "execute_code" -> str(args, "code"); else -> null }
        // A true or a 1.5 is not an exit code (as failedOf reads it).
        val exit = (obj?.get("exit_code") as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull }?.content?.toLongOrNull()
        command?.let { sections += ToolSection.Command(it, exit.takeIf { tool.name == "terminal" }) }

        val search = if (tool.name == "web_search") {
            val hits = ((obj?.get("data") as? JsonObject)?.get("web") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { hit ->
                val (title, url) = str(hit, "title") to str(hit, "url")
                if (title == null && url == null) null else ToolSection.Search.Hit(title.orEmpty(), url.orEmpty(), str(hit, "description"))
            }.take(ToolSection.Search.MAX_HITS)
            val query = str(args, "query")
            if (query != null || hits.isNotEmpty()) ToolSection.Search(query.orEmpty(), hits) else null
        } else null
        val diff = tool.diff?.let(ToolSection.Diff::of)?.takeIf { it.text.isNotBlank() }
        sections += listOfNotNull(search, diff)

        // Only the result is an output: never the summary or the name (an old chat shows no output it did not keep).
        val output = tool.result?.let { result ->
            if (obj != null) {
                val main = listOf("output", "content", "text", "message").firstNotNullOfOrNull { str(obj, it) }
                val error = str(obj, "error")
                listOfNotNull(main, error?.takeIf { it != main }).joinToString("\n\n")
                    .ifEmpty { if (search?.hits.isNullOrEmpty() && diff == null) pretty(obj) else "" }
            } else stripAnsi(result)
        }
        output?.takeIf { it.isNotBlank() }?.let { sections += ToolSection.Output(it) }

        val payloadArgs = args?.takeIf { it.isNotEmpty() }?.let(::pretty)
        val payloadResult = tool.result?.let { r -> parsed(r)?.let(::pretty) ?: r }
        if (payloadArgs != null || payloadResult != null) sections += ToolSection.Payload(payloadArgs, payloadResult)
        return sections
    }

    /**
     * How long a call ran, from the core's seconds, as Desktop writes it (`formatDurationSeconds`): "420ms", "3.4s",
     * "13s", "1m 4s", "1h 2m". Digits are always the root locale's (D12). A negative or non-finite time: "".
     */
    fun duration(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0) return ""
        if (seconds < 1) return "${max(1L, Math.round(seconds * 1000))}ms"
        if (seconds < 60) return String.format(Locale.ROOT, if (seconds < 10) "%.1fs" else "%.0fs", seconds)
        val minutes = Math.round(seconds) / 60
        val rest = Math.round(seconds) % 60
        if (minutes < 60) return if (rest != 0L) "${minutes}m ${rest}s" else "${minutes}m"
        return if (minutes % 60 != 0L) "${minutes / 60}h ${minutes % 60}m" else "${minutes / 60}h"
    }

    /** [text] as JSON, or null when it is not JSON (a plain string, a trimmed text). */
    private fun parsed(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()

    private fun pretty(element: JsonElement): String = Pretty.encodeToString(JsonElement.serializer(), element)

    /** [key] of [fields] when it is a non-blank string, escape codes dropped (D5); anything else: null. */
    private fun str(fields: JsonObject?, key: String): String? =
        (fields?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::stripAnsi)?.takeIf { it.isNotBlank() }
}
