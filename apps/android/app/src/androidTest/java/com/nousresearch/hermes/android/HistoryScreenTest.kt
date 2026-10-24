package com.nousresearch.hermes.android

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.HistoryScreen
import com.nousresearch.hermes.android.chat.HistoryUi
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The past conversations, listed by title (or how they began), each one a tap away from continuing. */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()

    private fun show(ui: HistoryUi) = compose.setContent {
        HistoryScreen(ui, onPick = { picked += it }, onClose = { picked += "close" })
    }

    @Test
    fun aPastSessionIsListedAndContinuedOnATap() {
        show(HistoryUi(listOf(SessionListRow(id = "db-1", title = "Calendar", messageCount = 3, startedAt = 1.7e9),
                              SessionListRow(id = "db-2", preview = "write a haiku about rain", messageCount = 2))))

        compose.onNodeWithText("write a haiku about rain", substring = true).assertExists()  // untitled: how it began
        compose.onNodeWithText("Calendar").performClick()

        assertEquals(listOf("db-1"), picked)
    }

    @Test
    fun noPastSessionSaysSo() {
        show(HistoryUi(emptyList()))

        compose.onNodeWithTag("history-empty").assertExists()
        compose.onNodeWithTag("history-close").performClick()

        assertEquals(listOf("close"), picked)
    }

    @Test
    fun aFailureIsShown() {
        show(HistoryUi(emptyList(), error = "session store unavailable"))

        compose.onNodeWithTag("history-error").assertExists()
    }
}
