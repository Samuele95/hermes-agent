package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A past conversation is listed in History and continues where it left off (spec §2.5), through the real core. */
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

        compose.onNodeWithTag("open-history").performClick()
        compose.waitUntil(60_000) { steps.shown("history") && !steps.shown("history-empty") }
        compose.onNodeWithText(word, substring = true, useUnmergedTree = true).performClick()
        compose.waitUntil(60_000) { steps.shown("draft") && steps.chat().entries.isNotEmpty() }

        assertEquals("the transcript is back", first.filterIsInstance<Entry.User>(), steps.chat().entries.filterIsInstance<Entry.User>())
        val turn = steps.say("Repeat the word you replied with before.")
        val reply = turn.filterIsInstance<Entry.Reply>().lastOrNull()
        Log.i(SmokeSteps.TAG, "resumed reply: $reply")
        assertTrue("the resumed session remembers: $reply", reply?.text?.contains(word, ignoreCase = true) == true)
    }
}
