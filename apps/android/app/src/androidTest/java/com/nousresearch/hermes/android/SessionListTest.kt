package com.nousresearch.hermes.android

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.HistoryUi
import com.nousresearch.hermes.android.chat.SessionList
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The past conversations in the drawer, under their headings, each by its title (or how it began) and a tap from continuing. */
@RunWith(AndroidJUnit4::class)
class SessionListTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()
    private val now = System.currentTimeMillis() / 1000.0

    private fun show(ui: HistoryUi) = compose.setContent { SessionList(ui, onPick = { picked += it }) }

    @Test
    fun aPastSessionIsListedUnderItsHeadingAndContinuedOnATap() {
        show(HistoryUi(listOf(SessionListRow(id = "db-1", title = "Calendar", messageCount = 3, startedAt = now - 60),
                              SessionListRow(id = "db-2", preview = "write a haiku about rain", messageCount = 2, startedAt = now - 120),
                              SessionListRow(id = "tg", title = "From the bot", messageCount = 2, startedAt = now, source = "telegram"))))

        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Telegram").assertExists()  // messaging sessions apart, as the desktop's sidebar has them
        compose.onNodeWithText("write a haiku about rain", substring = true).assertExists()  // untitled: how it began
        compose.onNodeWithText("Calendar").performClick()

        assertEquals(listOf("db-1"), picked)
    }

    @Test
    fun noPastSessionSaysSo() {
        show(HistoryUi(emptyList()))

        compose.onNodeWithTag("history-empty").assertExists()
    }

    @Test
    fun aFailureIsShown() {
        show(HistoryUi(emptyList(), error = "session store unavailable"))

        compose.onNodeWithTag("history-error").assertExists()
    }
}
