package com.nousresearch.hermes.android.chat

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dictation through the phone's own speech recognizer (chat-30, D48, F13): what it heard joins the draft, never
 * replacing it and never sent; a cancel, a no-match or an error is an absent result (EJ-55).
 */
class DictationTest {
    @Test
    fun heardWordsJoinTheDraftOnANewLine() {
        assertEquals("Groceries:\nbuy milk", dictated("Groceries:", "buy milk"))
        assertEquals("Groceries:\nbuy milk", dictated("Groceries:\n", "buy milk"))  // no second break
        assertEquals("two  spaces \nbuy milk", dictated("two  spaces ", " buy milk "))  // the user's text as typed
        assertEquals("line one\nline two\nthree", dictated("line one\nline two", "three"))
    }

    @Test
    fun aBlankDraftTakesTheWordsAlone() {
        assertEquals("buy milk", dictated("", "buy milk"))
        assertEquals("buy milk", dictated("  \n ", " buy milk"))  // D5: a blank draft is replaced
    }

    @Test
    fun nothingHeardLeavesTheDraftAsItWas() {
        listOf(null, "", "   ").forEach {
            assertEquals("heard: \"$it\"", "Groceries: ", dictated("Groceries: ", it))  // not trimmed, no new line
            assertEquals("heard: \"$it\"", "", dictated("", it))
        }
    }

    @Test
    fun onlyAnOkAnswerWithWordsIsHeard() {
        assertEquals("buy milk", heardOf(Activity.RESULT_OK, listOf(" buy milk ", "by milk")))  // the first, trimmed
        assertNull("a blank first result: no later one", heardOf(Activity.RESULT_OK, listOf("  ", "by milk")))
        assertNull(heardOf(Activity.RESULT_OK, emptyList()))
        assertNull(heardOf(Activity.RESULT_OK, null))
        assertNull("cancelled", heardOf(Activity.RESULT_CANCELED, listOf("buy milk")))
        // RecognizerIntent's error codes (deprecated, hence numbers): RESULT_NO_MATCH 1, RESULT_CLIENT_ERROR 2,
        // RESULT_SERVER_ERROR 3, RESULT_NETWORK_ERROR 4, RESULT_AUDIO_ERROR 5.
        (1..5).forEach { assertNull("error $it", heardOf(it, listOf("buy milk"))) }
    }
}
