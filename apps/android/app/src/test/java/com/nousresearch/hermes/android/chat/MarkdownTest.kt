package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.chat.Markdown.Block
import com.nousresearch.hermes.android.chat.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Markdown a model writes, read into the blocks and styled runs the chat draws. */
class MarkdownTest {
    private fun plain(text: String) = Span(text)

    @Test
    fun paragraphsAreSeparatedByBlankLinesAndJoinTheirLines() {
        assertEquals(listOf(Block.Paragraph(listOf(plain("one two"))), Block.Paragraph(listOf(plain("three")))),
                     Markdown.parse("one\ntwo\n\nthree"))
    }

    @Test
    fun headingsKeepTheirLevel() {
        assertEquals(listOf(Block.Heading(1, listOf(plain("Title"))), Block.Heading(3, listOf(plain("Part")))),
                     Markdown.parse("# Title\n### Part"))
    }

    @Test
    fun inlineStylesBecomeRuns() {
        assertEquals(listOf(plain("a "), Span("bold", bold = true), plain(" and "), Span("it", italic = true), plain(", "),
                            Span("x()", code = true), plain(", "), Span("gone", strike = true), plain(" "),
                            Span("site", link = "https://example.com")),
                     Markdown.inline("a **bold** and *it*, `x()`, ~~gone~~ [site](https://example.com)"))
    }

    @Test
    fun stylesNest() {
        assertEquals(listOf(Span("all ", bold = true), Span("both", bold = true, italic = true)), Markdown.inline("**all *both***"))
    }

    @Test
    fun codeSpansAreLiteral() {
        assertEquals(listOf(Span("**not bold**", code = true)), Markdown.inline("`**not bold**`"))
    }

    @Test
    fun anUnclosedMarkerStaysAsWritten() {
        // A reply still streaming, or prose that uses the characters for themselves.
        assertEquals(listOf(plain("2 * 3 = 6 and **half")), Markdown.inline("2 * 3 = 6 and **half"))
    }

    @Test
    fun snakeCaseIsNotItalic() {
        assertEquals(listOf(plain("call my_function_name now")), Markdown.inline("call my_function_name now"))
        assertEquals(listOf(plain("set max_retries_ to 3")), Markdown.inline("set max_retries_ to 3"))
    }

    @Test
    fun fencedCodeKeepsItsTextAndLanguage() {
        assertEquals(listOf(Block.Paragraph(listOf(plain("Run:"))), Block.Code("bash", "ls -la\n  echo **x**")),
                     Markdown.parse("Run:\n```bash\nls -la\n  echo **x**\n```"))
    }

    @Test
    fun aFenceStillOpenWhileStreamingIsCodeToTheEnd() {
        assertEquals(listOf(Block.Code("", "partial")), Markdown.parse("```\npartial"))
    }

    @Test
    fun listsKeepTheirMarkersAndNesting() {
        assertEquals(listOf(Block.ListItem(0, null, listOf(plain("first"))), Block.ListItem(1, null, listOf(plain("inner"))),
                            Block.ListItem(0, 2, listOf(plain("numbered")))),
                     Markdown.parse("- first\n  * inner\n2. numbered"))
    }

    @Test
    fun aListItemContinuesOnItsIndentedLines() {
        assertEquals(listOf(Block.ListItem(0, null, listOf(plain("one more")))), Markdown.parse("- one\n  more"))
    }

    @Test
    fun quotesAndRules() {
        assertEquals(listOf(Block.Quote(listOf(plain("said this"))), Block.Rule, Block.Paragraph(listOf(plain("after")))),
                     Markdown.parse("> said\n> this\n\n---\nafter"))
    }

    @Test
    fun tablesKeepTheirCells() {
        assertEquals(listOf(Block.Table(listOf(listOf(plain("a")), listOf(plain("b"))),
                                        listOf(listOf(listOf(plain("1")), listOf(Span("2", bold = true)))))),
                     Markdown.parse("| a | b |\n|---|:-:|\n| 1 | **2** |"))
    }

    @Test
    fun aPipeWithoutADividerRowIsProse() {
        assertEquals(listOf(Block.Paragraph(listOf(plain("a | b")))), Markdown.parse("a | b"))
    }

    @Test
    fun aBareLinkIsALink() {
        assertEquals(listOf(plain("see "), Span("https://nousresearch.com/x", link = "https://nousresearch.com/x"), plain(".")),
                     Markdown.inline("see https://nousresearch.com/x."))
    }

    @Test
    fun escapedMarkersAreLiteral() {
        assertEquals(listOf(plain("*not* it")), Markdown.inline("\\*not\\* it"))
    }
}
