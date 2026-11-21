package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A past conversation is listed in History, renamed there without being opened, continues where it left off (spec
 * §2.5), and is deleted from the drawer while the chat is in it, through the real core.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class HistorySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun aPastConversationContinuesFromHistory() {
        steps.setUpEndpoint()
        val word = "kiwi${System.currentTimeMillis() % 100000}"
        steps.say("Reply with only the word $word.")
        val first = steps.chat().entries
        runBlocking { (steps.app.core.value as CoreState.Ready).chat.open() }  // a fresh session, as after a restart
        compose.waitUntil(30_000) { steps.chat().entries.isEmpty() }

        compose.onNodeWithTag("open-drawer").performClick()  // the drawer lists the sessions as it opens
        compose.waitUntil(60_000) { steps.shown("history") && !steps.shown("history-empty") }
        // Renamed aside: the chat stays on its new session, the drawer lists the core's new title.
        val named = "kiwi chat $word"
        compose.onNodeWithText(word, substring = true, useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("session-rename").performClick()
        compose.onNodeWithTag("rename-field").performTextReplacement(named)
        compose.onNodeWithTag("rename-save").performClick()
        compose.waitUntil(60_000) { !steps.shown("rename-field") && listed(named) }
        assertTrue("renaming aside switched nothing", steps.chat().entries.isEmpty())
        compose.onNodeWithText(word, substring = true, useUnmergedTree = true).performClick()
        compose.waitUntil(60_000) { steps.shown("draft") && steps.chat().entries.isNotEmpty() }

        assertEquals("the transcript is back", first.filterIsInstance<Entry.User>(), steps.chat().entries.filterIsInstance<Entry.User>())
        val turn = steps.say("Repeat the word you replied with before.")
        val reply = turn.filterIsInstance<Entry.Reply>().lastOrNull()
        Log.i(SmokeSteps.TAG, "resumed reply: $reply")
        assertTrue("the resumed session remembers: $reply", reply?.text?.contains(word, ignoreCase = true) == true)

        // Deleted while the chat is in it: the chat moves to a new, empty session first, then the row goes.
        val resumedId = (steps.app.core.value as CoreState.Ready).chat.sessionId
        compose.onNodeWithTag("open-drawer").performClick()
        compose.waitUntil(60_000) { listed(named) }
        compose.onNodeWithText(named, useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("session-delete").performClick()
        compose.onNodeWithTag("delete-confirm").performClick()
        compose.waitUntil(120_000) { (!listed(named) && !steps.shown("delete-dialog")) || steps.shown("history-error") }
        assertTrue("the delete was not refused", !steps.shown("history-error"))
        assertNotEquals(resumedId, (steps.app.core.value as CoreState.Ready).chat.sessionId)
        assertTrue("the chat moved off its own first", steps.chat().entries.isEmpty())
    }

    /** A drawer row titled exactly [title] is shown. */
    private fun listed(title: String) =
        compose.onAllNodes(hasText(title), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}
