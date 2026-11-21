package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.chat.TokenKind.COMMENT
import com.nousresearch.hermes.android.chat.TokenKind.KEYWORD
import com.nousresearch.hermes.android.chat.TokenKind.NUMBER
import com.nousresearch.hermes.android.chat.TokenKind.PLAIN
import com.nousresearch.hermes.android.chat.TokenKind.PUNCTUATION
import com.nousresearch.hermes.android.chat.TokenKind.STRING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code read into coloured runs, as the chat draws a code block (chat-10). */
class CodeHighlighterTest {
    private fun lex(language: String, code: String) = CodeHighlighter.tokens(language, code)

    /** The coloured runs only: what a reader sees highlighted. */
    private fun coloured(language: String, code: String) =
        lex(language, code).filter { it.kind != PLAIN && it.kind != PUNCTUATION }.map { it.kind to it.text }

    private val none = emptyList<Pair<TokenKind, String>>()

    private val samples = mapOf(
        "kotlin" to "fun main() {\n    val s = \"\"\"a \"b\" // c\"\"\" // done\n    /* block */ println(0x1F + 2.5)\n}",
        "python" to "def f(x):\n    '''doc'''  # note\n    return f\"{x}\" if x else None",
        "ts" to "const t = `a \${b}\nc` // tail\nlet n = 10_000",
        "bash" to "if [ \"\$#\" -gt 0 ]; then echo 'hi' # note\nfi",
        "json" to "{\"a\": [1, 2.5, true, null]}",
        "yaml" to "key: \"v\" # c\nlist:\n  - yes",
        "rust" to "let s = \"x\"; // c\n/* b */ 42",
        "text" to "plain words // not a comment")

    @Test
    fun everyPrefixIsSpelledBackExactly() {
        samples.forEach { (lang, code) ->
            for (n in 0..code.length) {
                val prefix = code.take(n)
                val t = lex(lang, prefix)
                assertEquals("$lang/$n", prefix, t.joinToString("") { it.text })
                assertTrue("$lang/$n empty token", t.none { it.text.isEmpty() })
                assertTrue("$lang/$n unmerged", t.zipWithNext().none { (a, b) -> a.kind == b.kind })
            }
        }
    }

    @Test
    fun keywordsAreWholeWordsOfTheirLanguage() {
        assertEquals(listOf(KEYWORD to "val"), coloured("kotlin", "val format = info"))
        assertEquals(listOf(KEYWORD to "def", KEYWORD to "pass"), coloured("python", "def val(): pass"))
        assertEquals(none, coloured("kotlin", "def"))
    }

    @Test
    fun aCommentMarkerInsideAStringStaysString() {
        assertEquals(listOf(KEYWORD to "val", STRING to "\"http://x\"", COMMENT to "// y"), coloured("kotlin", "val u = \"http://x\" // y"))
        assertEquals(listOf(STRING to "'# not'", COMMENT to "# yes"), coloured("python", "s = '# not' # yes"))
    }

    @Test
    fun aBackslashEscapesTheNextCharacterInAString() {
        assertEquals(listOf(KEYWORD to "val", STRING to "\"say \\\"hi\\\"\"", COMMENT to "// c"),
                     coloured("kotlin", "val q = \"say \\\"hi\\\"\" // c"))
    }

    @Test
    fun anUnclosedShortStringStopsAtItsLine() {
        assertEquals(listOf(KEYWORD to "val", STRING to "\"open", KEYWORD to "val", NUMBER to "1"),
                     coloured("kotlin", "val a = \"open\nval b = 1"))
    }

    @Test
    fun anUnclosedLongStringOrBlockCommentRunsToTheEnd() {
        assertEquals(listOf(STRING to "\"\"\"abc\ndef"), coloured("python", "x = \"\"\"abc\ndef"))
        assertEquals(listOf(COMMENT to "/* open\nstill"), coloured("kotlin", "a /* open\nstill"))
    }

    @Test
    fun numbersAndShellCommentsStandAlone() {
        assertEquals(listOf(NUMBER to "0x1F", NUMBER to "1_000", NUMBER to "2.5f"), coloured("kotlin", "x1 = 0x1F + 1_000 + 2.5f"))
        assertEquals(listOf(COMMENT to "# note"), coloured("bash", "echo \$# a#b # note"))
    }

    @Test
    fun eachLanguageColoursItsOwnWords() {
        listOf("kotlin", "KT", " kts ", "java").forEach { assertEquals(it, listOf(KEYWORD to "return"), coloured(it, "return")) }
        listOf("py" to "None", "ts" to "const", "sh" to "fi", "json" to "true", "yml" to "null").forEach { (lang, word) ->
            assertEquals(lang, listOf(KEYWORD to word), coloured(lang, word))
        }
        assertEquals(none, coloured("rust", "fn"))
        assertEquals(listOf(STRING to "\"s\"", COMMENT to "// c"), coloured("rust", "\"s\" // c"))
    }

    @Test
    fun plainOrOversizedCodeIsOneUncolouredRun() {
        listOf("", "text", "md", "diff").forEach {
            assertEquals(it, listOf(CodeToken("x = \"y\" // z", PLAIN)), lex(it, "x = \"y\" // z"))
        }
        val long = List(3_001) { "val x = 1" }.joinToString("\n")
        assertEquals(listOf(CodeToken(long, PLAIN)), lex("kotlin", long))
        assertTrue(coloured("kotlin", long.substringAfter('\n')).isNotEmpty())
        val wide = "val " + "x".repeat(150_000)
        assertEquals(listOf(CodeToken(wide, PLAIN)), lex("kotlin", wide))
        assertEquals(emptyList<CodeToken>(), lex("kotlin", ""))
    }
}
