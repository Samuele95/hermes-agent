package com.nousresearch.hermes.android.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Only shared plain text is an offer, trimmed and capped; the intent's words, never the app's.
class IncomingTextTest {
    private val send = "android.intent.action.SEND"

    @Test
    fun aSendOfPlainTextIsAnOfferOfItsTrimmedWords() {
        assertEquals(IncomingText("hi there"), IncomingText.of(send, "text/plain", "  hi there \n", null))

        // A charset or another case names the same type; any CharSequence carries the words.
        assertEquals(IncomingText("hi"), IncomingText.of(send, "text/plain; charset=utf-8", "hi", null))
        assertEquals(IncomingText("hi"), IncomingText.of(send, "TEXT/PLAIN", "hi", null))
        assertEquals(IncomingText("x"), IncomingText.of(send, "text/plain", StringBuilder("x"), null))
    }

    @Test
    fun onlyASendOfPlainTextIsAnOffer() {
        // Another action, or none (the launcher's MAIN, a VIEW, several items, the shortcut's own action), is no offer.
        for (action in listOf("android.intent.action.MAIN", "android.intent.action.VIEW", "android.intent.action.SEND_MULTIPLE", null, NEW_CHAT)) {
            assertNull("action $action", IncomingText.of(action, "text/plain", "hi", null))
        }
        // Another type, or none, is no offer: an image, a web page's markup, an untyped intent.
        for (type in listOf("image/png", "text/html", null)) {
            assertNull("type $type", IncomingText.of(send, type, "hi", null))
        }
    }

    @Test
    fun noWordsIsNoOffer() {
        for (text in listOf(null, "", " \n\t ")) {
            assertNull("text ${text?.length}", IncomingText.of(send, "text/plain", text, "A page"))
        }
    }

    @Test
    fun aLongTextIsCutAt20000AndSaysSo() {
        val long = IncomingText.of(send, "text/plain", "a".repeat(20_001), null)!!
        assertEquals(20_000, long.text.length)
        assertTrue(long.cut)

        val exact = IncomingText.of(send, "text/plain", "a".repeat(20_000), null)!!
        assertEquals(20_000, exact.text.length)
        assertFalse(exact.cut)

        // The cut never splits a surrogate pair: the emoji that would straddle it goes whole.
        val emoji = IncomingText.of(send, "text/plain", "a".repeat(19_999) + "😀" + "b", null)!!
        assertEquals(19_999, emoji.text.length)
        assertTrue(emoji.cut)
    }

    @Test
    fun theSubjectIsOneShortLineOrNone() {
        assertNull(IncomingText.of(send, "text/plain", "hi", " ")!!.subject)
        assertEquals("Page title", IncomingText.of(send, "text/plain", "hi", "Page\n  title")!!.subject)
        assertEquals(200, IncomingText.of(send, "text/plain", "hi", "s".repeat(300))!!.subject!!.length)
    }

    @Test
    fun theDraftLeadsWithTheSubjectUnlessTheTextHoldsIt() {
        assertEquals("A page\nhttps://e.org", IncomingText("https://e.org", "A page").draft)
        assertEquals("A page https://e.org", IncomingText("A page https://e.org", "A page").draft)
        assertEquals("https://e.org", IncomingText("https://e.org").draft)
    }
}
