package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionHistoryParams
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Retry on the phone's model, through the core: the last prompt runs again in place of its turn, and the core's own
 * stored transcript is cut the same way — first a cut to an empty transcript, then one that keeps the first turn.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class RetrySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun theLastPromptRunsAgainInPlaceOfItsTurn() {
        steps.setUpEndpoint()
        val ready = steps.app.core.value as CoreState.Ready
        fun coreUsers() = runBlocking {
            ready.gateway.call(Methods.SESSION_HISTORY, SessionHistoryParams(sessionId = ready.chat.sessionId))
        }.messages.filter { it.role == "user" && it.displayKind == null }
        fun shownUsers() = steps.chat().entries.filterIsInstance<Entry.User>()

        /** Taps Retry and waits for the turn that replaces [old]'s: a reply under a newly named prompt, or a notice. */
        fun retried(old: Long?) {
            compose.waitUntil(30_000) { steps.shown("retry") }
            compose.onNodeWithTag("retry").performClick()
            val began = System.currentTimeMillis()
            compose.waitUntil(600_000) {
                val c = steps.chat()
                !c.running && c.entries.lastOrNull().let {
                    it is Entry.Notice || (it is Entry.Reply && c.entries.filterIsInstance<Entry.User>().last().rowId.let { id -> id != null && id != old })
                }
            }
            Log.i(SmokeSteps.TAG, "retried turn after ${System.currentTimeMillis() - began} ms: ${steps.chat().entries}")
        }
        fun assertAnswered() {
            val entries = steps.chat().entries
            assertTrue("no notice: $entries", entries.none { it is Entry.Notice })
            assertTrue("the last entry is a complete reply: $entries", (entries.last() as? Entry.Reply)?.status == TurnStatus.COMPLETE)
        }

        // The only turn: its retry cuts the core's transcript to nothing (the confirm_empty_truncate path).
        steps.say("Reply with one random four-digit number and nothing else.")
        val first = shownUsers().single()
        assertNotNull("prompt.submit named the row", first.rowId)
        retried(first.rowId)
        assertAnswered()
        val again = shownUsers().single()
        assertTrue("a new row for the retried prompt: $again vs $first", again.rowId != first.rowId)
        assertEquals("the core cut too", listOf(again.rowId), coreUsers().map { it.rowId })

        // A second turn: its retry keeps the first, whose row id survives unchanged.
        steps.say("Now reply with the word kiwi and nothing else.")
        val second = shownUsers()
        Log.i(SmokeSteps.TAG, "shown user ids before the second retry: ${second.map { it.rowId }}")
        retried(second.last().rowId)
        assertAnswered()
        val after = shownUsers().map { it.rowId }
        assertEquals("the first prompt kept its id", again.rowId, after.first())
        assertTrue("the second prompt has a new id: $after vs ${second.map { it.rowId }}", after.size == 2 && after[1] != null && after[1] != second.last().rowId)
        assertEquals("the core keeps the same prompts", after, coreUsers().map { it.rowId })

        steps.screenshot("b5-retry")
    }
}
