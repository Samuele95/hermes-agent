package com.nousresearch.hermes.android.chat

/**
 * The Markdown models write, read into what the chat draws: blocks (paragraphs, headings, fenced code, list items,
 * quotes, tables, rules) holding styled runs. A small reader rather than a library: replies are read again on every
 * streamed delta, so it must take any prefix of a reply — an unclosed fence is code to the end, an unclosed marker
 * stays as written — and never fail.
 */
object Markdown {
    /** A run of text with one style; [link] is the target of a link. */
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false,
                    val strike: Boolean = false, val link: String? = null)

    sealed interface Block {
        data class Paragraph(val spans: List<Span>) : Block
        data class Heading(val level: Int, val spans: List<Span>) : Block
        data class Code(val language: String, val text: String) : Block
        /** One item; [depth] 0 is the outer list, [number] is null for a bullet. */
        data class ListItem(val depth: Int, val number: Int?, val spans: List<Span>) : Block
        data class Quote(val spans: List<Span>) : Block
        data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : Block
        data object Rule : Block
    }

    private val FENCE = Regex("""^\s*(`{3,}|~{3,})\s*([^\s`]*).*$""")
    private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val RULE = Regex("""^\s{0,3}([-*_])(?:\s*\1){2,}\s*$""")
    private val QUOTE = Regex("""^\s{0,3}>\s?(.*)$""")
    private val ITEM = Regex("""^(\s*)(?:[-*+]|(\d{1,9})[.)])\s+(.*)$""")
    private val DIVIDER_CELL = Regex("""^\s*:?-+:?\s*$""")

    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) blocks += Block.Paragraph(inline(paragraph.joinToString(" ")))
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            FENCE.matchEntire(line)?.let { fence ->
                flush()
                val marker = fence.groupValues[1]
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) body += lines[i++]
                blocks += Block.Code(fence.groupValues[2], body.joinToString("\n"))
                i++  // the closing fence, or past the end while the reply is still streaming
                continue
            }
            if (line.isBlank()) {
                flush(); i++; continue
            }
            HEADING.matchEntire(line)?.let {
                flush(); blocks += Block.Heading(it.groupValues[1].length, inline(it.groupValues[2])); i++; continue
            }
            if (RULE.matches(line)) {
                flush(); blocks += Block.Rule; i++; continue
            }
            if (QUOTE.matches(line)) {
                flush()
                val quoted = mutableListOf<String>()
                while (i < lines.size) {
                    quoted += (QUOTE.matchEntire(lines[i]) ?: break).groupValues[1].trim()
                    i++
                }
                blocks += Block.Quote(inline(quoted.filter { it.isNotEmpty() }.joinToString(" ")))
                continue
            }
            if ('|' in line && i + 1 < lines.size && isDivider(lines[i + 1])) {
                flush()
                val header = cells(line)
                val rows = mutableListOf<List<List<Span>>>()
                i += 2
                while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) rows += cells(lines[i++])
                blocks += Block.Table(header, rows)
                continue
            }
            ITEM.matchEntire(line)?.let { item ->
                flush()
                val indent = item.groupValues[1].replace("\t", "    ").length
                val words = mutableListOf(item.groupValues[3].trim())
                i++
                // Indented lines that start nothing of their own continue the item.
                while (i < lines.size && lines[i].isNotBlank() && lines[i].first().isWhitespace() && startsNothing(lines[i])) {
                    words += lines[i++].trim()
                }
                blocks += Block.ListItem(indent / 2, item.groupValues[2].toIntOrNull(), inline(words.joinToString(" ")))
                continue
            }
            paragraph += line.trim()
            i++
        }
        flush()
        return blocks
    }

    private fun startsNothing(line: String): Boolean =
        !ITEM.matches(line) && !FENCE.matches(line) && !HEADING.matches(line) && !QUOTE.matches(line) && !RULE.matches(line)

    private fun splitRow(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    private fun isDivider(line: String): Boolean = '-' in line && splitRow(line).all { DIVIDER_CELL.matches(it) }

    private fun cells(line: String): List<List<Span>> = splitRow(line).map(::inline)

    private data class Style(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false,
                             val link: String? = null) {
        fun span(text: String, code: Boolean = false) = Span(text, bold, italic, code, strike, link)
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>"

    /** The styled runs of one block's text; adjacent runs of one style are one run. */
    fun inline(text: String): List<Span> {
        val out = mutableListOf<Span>()
        runs(text, Style(), out)
        return out.fold(mutableListOf()) { merged, span ->
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == span.copy(text = "")) merged[merged.size - 1] = last.copy(text = last.text + span.text)
            else merged += span
            merged
        }
    }

    private fun runs(s: String, style: Style, out: MutableList<Span>) {
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) out += style.span(plain.toString())
            plain.clear()
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                plain.append(s[i + 1]); i += 2; continue
            }
            if (c == '`') {
                val ticks = s.countFrom(i, '`')
                val close = findTicks(s, i + ticks, ticks)
                if (close < 0) {
                    plain.append(s, i, i + ticks); i += ticks; continue
                }
                flush()
                val body = s.substring(i + ticks, close)
                out += style.span(if (body.length > 2 && body.startsWith(' ') && body.endsWith(' ')) body.substring(1, body.length - 1) else body,
                                  code = true)
                i = close + ticks
                continue
            }
            if (c == '[' && style.link == null) {
                val link = link(s, i)
                if (link != null) {
                    flush()
                    runs(link.label, style.copy(link = link.target), out)
                    i = link.end
                    continue
                }
            }
            if (style.link == null && (s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                val url = s.substring(i).takeWhile { !it.isWhitespace() && it != '<' && it != '>' }.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"')
                    .let { if (it.endsWith(')') && '(' !in it) it.trimEnd(')') else it }
                flush()
                out += style.copy(link = url).span(url)
                i += url.length
                continue
            }
            val emphasis = emphasis(s, i)
            if (emphasis != null) {
                flush()
                val inner = when (emphasis.marker) {
                    "**", "__" -> style.copy(bold = true)
                    "~~" -> style.copy(strike = true)
                    else -> style.copy(italic = true)
                }
                runs(s.substring(i + emphasis.marker.length, emphasis.close), inner, out)
                i = emphasis.close + emphasis.marker.length
                continue
            }
            plain.append(c)
            i++
        }
        flush()
    }

    private fun String.countFrom(start: Int, c: Char): Int {
        var n = 0
        while (start + n < length && this[start + n] == c) n++
        return n
    }

    /** Where a run of exactly [ticks] backticks starts at or after [from], or -1. */
    private fun findTicks(s: String, from: Int, ticks: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '`') {
                val run = s.countFrom(j, '`')
                if (run == ticks) return j
                j += run
            } else j++
        }
        return -1
    }

    private class Link(val label: String, val target: String, val end: Int)

    private fun link(s: String, open: Int): Link? {
        val close = s.indexOf(']', open + 1)
        if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
        val end = s.indexOf(')', close + 2)
        if (end < 0) return null
        val target = s.substring(close + 2, end).trim().substringBefore(' ')
        if (target.isEmpty() || close == open + 1) return null
        return Link(s.substring(open + 1, close), target, end + 1)
    }

    private class Emphasis(val marker: String, val close: Int)

    /** The emphasis opened at [i] and where it closes, or null if [i] opens none (the marker is then plain text). */
    private fun emphasis(s: String, i: Int): Emphasis? {
        val marker = when {
            s.startsWith("**", i) -> "**"
            s.startsWith("__", i) -> "__"
            s.startsWith("~~", i) -> "~~"
            s[i] == '*' || s[i] == '_' -> s[i].toString()
            else -> return null
        }
        val after = i + marker.length
        if (after >= s.length || s[after].isWhitespace()) return null
        // An underscore inside a word (snake_case) is part of the word.
        if (marker[0] == '_' && i > 0 && s[i - 1].isLetterOrDigit()) return null
        val close = closing(s, after, marker) ?: return null
        if (close == after) return null
        if (marker[0] == '_' && close + marker.length < s.length && s[close + marker.length].isLetterOrDigit()) return null
        return Emphasis(marker, close)
    }

    /**
     * Where [marker] closes, scanning from [from]: skipping code spans, and pairing the other star marker on the way,
     * so `**all *both***` closes its bold after the italic's star, not inside it.
     */
    private fun closing(s: String, from: Int, marker: String): Int? {
        var other = false  // the other star marker is open
        var j = from
        while (j < s.length) {
            if (s[j] == '\\') { j += 2; continue }
            if (s[j] == '`') {
                val ticks = s.countFrom(j, '`')
                val end = findTicks(s, j + ticks, ticks)
                j = if (end < 0) j + ticks else end + ticks
                continue
            }
            val closesHere = s.startsWith(marker, j) && !s[j - 1].isWhitespace()
            when (marker) {
                "**" -> when {
                    !other && closesHere -> return j
                    s[j] == '*' -> { other = !other; j++; continue }
                }
                "*" -> when {
                    s.startsWith("**", j) && (other || (j + 2 < s.length && !s[j + 2].isWhitespace())) -> { other = !other; j += 2; continue }
                    !other && closesHere -> return j
                }
                else -> if (closesHere) return j
            }
            j++
        }
        return null
    }
}
