package com.nousresearch.hermes.android.chat

/** What a run of code is, for its colour (chat-10, blueprint F10): a closed set the screen maps exhaustively (EJ-34). */
enum class TokenKind { PLAIN, KEYWORD, STRING, NUMBER, COMMENT, PUNCTUATION }

/** One run of a code block. A block's tokens spell its code back exactly. */
data class CodeToken(val text: String, val kind: TokenKind)

/**
 * Code coloured by its language, read the way [Markdown] reads a reply: a small single-pass lexer rather than a
 * library (DC-B14), because a streaming code block is lexed again on every delta. It takes any prefix (an unclosed
 * string or block comment runs to the end), never fails, never drops or adds a character, and merges neighbours of
 * one kind into one token.
 */
object CodeHighlighter {
    /** Past these a block is drawn plain, as the desktop's (shiki-highlighter.tsx:46-47). */
    const val MAX_CHARS = 150_000
    const val MAX_LINES = 3_000

    /** One language's rules. [lineAtWordStart]: the line comment counts only after whitespace or at the start. */
    private class Syntax(val keywords: Set<String>, val lineComment: String?, val blockComment: Boolean,
                         val shortQuotes: String, val longQuotes: List<String>, val lineAtWordStart: Boolean = false)

    private fun words(text: String) = text.trim().split(Regex("\\s+")).toSet()

    private val KOTLIN_JAVA = Syntax(words("""
        abstract as boolean break byte case catch char class const continue data default do double else enum extends
        false final finally float for fun if implements import in int interface internal is lateinit long new null
        object open operator override package private protected public return sealed short static super suspend switch
        this throw throws true try typealias val var void when while"""), "//", true, "\"'", listOf("\"\"\""))
    private val PYTHON = Syntax(words("""
        False None True and as assert async await break case class continue def del elif else except finally for from
        global if import in is lambda match nonlocal not or pass raise return try while with yield"""),
        "#", false, "\"'", listOf("\"\"\"", "'''"))
    private val JS_TS = Syntax(words("""
        as async await break case catch class const continue debugger default delete do else enum export extends false
        finally for from function if implements import in instanceof interface let new null of private protected public
        readonly return static super switch this throw true try type typeof undefined var void while with yield"""),
        "//", true, "\"'", listOf("`"))
    private val SHELL = Syntax(words("""
        case do done elif else esac export fi for function if in local readonly return select then until while"""),
        "#", false, "\"'", emptyList(), lineAtWordStart = true)
    private val JSON = Syntax(words("true false null"), "//", true, "\"", emptyList())
    private val YAML = Syntax(words("true false null True False Null TRUE FALSE NULL yes no on off"),
        "#", false, "\"'", emptyList(), lineAtWordStart = true)
    private val GENERIC = Syntax(emptySet(), "//", true, "\"", emptyList())

    /** Fences drawn uncoloured, as the desktop's `language || 'text'` (V3). */
    private val PLAIN_NAMES = setOf("", "text", "txt", "plain", "plaintext", "markdown", "md", "log", "output", "diff")

    private val BY_NAME: Map<String, Syntax> = buildMap {
        listOf("kotlin", "kt", "kts", "java").forEach { put(it, KOTLIN_JAVA) }
        listOf("python", "py", "python3").forEach { put(it, PYTHON) }
        listOf("javascript", "js", "jsx", "mjs", "cjs", "typescript", "ts", "tsx").forEach { put(it, JS_TS) }
        listOf("bash", "sh", "shell", "zsh", "console").forEach { put(it, SHELL) }
        listOf("json", "jsonc").forEach { put(it, JSON) }
        listOf("yaml", "yml").forEach { put(it, YAML) }
    }

    private const val PUNCTUATION = "{}[]()<>,;:.=+-*/%!&|^~?@"

    fun tokens(language: String, code: String): List<CodeToken> {
        if (code.isEmpty()) return emptyList()
        val name = language.trim().lowercase()
        if (name in PLAIN_NAMES || code.length > MAX_CHARS || code.count { it == '\n' } + 1 > MAX_LINES) {
            return listOf(CodeToken(code, TokenKind.PLAIN))
        }
        return scan(BY_NAME[name] ?: GENERIC, code)
    }

    private fun scan(syntax: Syntax, code: String): List<CodeToken> {
        val out = ArrayList<CodeToken>()
        // The one emitter: runs arrive in order and touch, so a run of the open kind only moves the open token's end.
        var open: TokenKind? = null
        var openFrom = 0
        fun emit(from: Int, to: Int, kind: TokenKind) {
            if (to <= from || kind == open) return
            open?.let { out += CodeToken(code.substring(openFrom, from), it) }
            open = kind
            openFrom = from
        }
        val n = code.length
        var i = 0
        while (i < n) {
            val c = code[i]
            val long = syntax.longQuotes.firstOrNull { code.startsWith(it, i) }
            val end = when {
                long != null -> {
                    val close = closing(code, i + long.length, long)
                    emit(i, close, TokenKind.STRING); close
                }
                c in syntax.shortQuotes -> {
                    val close = shortString(code, i, c)
                    emit(i, close, TokenKind.STRING); close
                }
                syntax.blockComment && code.startsWith("/*", i) -> {
                    val at = code.indexOf("*/", i + 2)
                    val close = if (at < 0) n else at + 2
                    emit(i, close, TokenKind.COMMENT); close
                }
                syntax.lineComment != null && code.startsWith(syntax.lineComment, i) &&
                    (!syntax.lineAtWordStart || i == 0 || code[i - 1].isWhitespace()) -> {
                    val at = code.indexOf('\n', i)
                    val close = if (at < 0) n else at
                    emit(i, close, TokenKind.COMMENT); close
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' ||
                                     (code[j] == '.' && j + 1 < n && code[j + 1].isDigit()))) j++
                    emit(i, j, TokenKind.NUMBER); j
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$')) j++
                    emit(i, j, if (code.substring(i, j) in syntax.keywords) TokenKind.KEYWORD else TokenKind.PLAIN); j
                }
                c in PUNCTUATION -> { emit(i, i + 1, TokenKind.PUNCTUATION); i + 1 }
                else -> { emit(i, i + 1, TokenKind.PLAIN); i + 1 }
            }
            i = end
        }
        open?.let { out += CodeToken(code.substring(openFrom), it) }
        return out
    }

    /** The end of a long string opened before [from]: past its closing [quote], or the end of the code. */
    private fun closing(code: String, from: Int, quote: String): Int {
        var j = from
        while (j < code.length) {
            if (code[j] == '\\') { j += 2; continue }
            if (code.startsWith(quote, j)) return j + quote.length
            j++
        }
        return code.length
    }

    /** The end of a short string opened at [start]: past its unescaped closing [quote], or its line's end. */
    private fun shortString(code: String, start: Int, quote: Char): Int {
        var j = start + 1
        while (j < code.length) {
            val c = code[j]
            if (c == '\n') return j
            if (c == '\\') { j += 2; continue }
            if (c == quote) return j + 1
            j++
        }
        return code.length
    }
}
