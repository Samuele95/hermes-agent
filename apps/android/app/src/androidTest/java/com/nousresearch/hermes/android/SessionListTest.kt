package com.nousresearch.hermes.android

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.HistoryUi
import com.nousresearch.hermes.android.chat.SessionActions
import com.nousresearch.hermes.android.chat.SessionList
import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The past conversations in the drawer, under their headings, each by its title (or how it began) and a tap from
 * continuing; a long press offers to rename, export or delete one.
 */
@RunWith(AndroidJUnit4::class)
class SessionListTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()
    private val now = System.currentTimeMillis() / 1000.0

    private val heard = mutableListOf<String>()
    private val actions = SessionActions(edit = { heard += "edit:${it?.value}" }, saveTitle = { heard += "save:$it" },
                                         askDelete = { heard += "ask:${it?.value}" }, confirmDelete = { heard += "confirm" })
    private val calendar = listOf(SessionListRow(id = "db-1", title = "Calendar", messageCount = 3, startedAt = now - 60))

    private fun show(ui: HistoryUi, actions: SessionActions? = null) =
        compose.setContent { SessionList(ui, onPick = { picked += it.value }, actions = actions) }

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

    @Test
    fun aLongPressOffersRenameAndDelete() {
        show(HistoryUi(calendar), actions)

        compose.onNodeWithText("Calendar").performTouchInput { longClick() }
        compose.onNodeWithTag("session-rename").performClick()
        assertEquals(listOf("edit:db-1"), heard)

        compose.onNodeWithText("Calendar").performTouchInput { longClick() }
        compose.onNodeWithTag("session-delete").performClick()
        assertEquals(listOf("edit:db-1", "ask:db-1"), heard)

        compose.onNodeWithText("Calendar").performClick()  // a plain tap still continues it
        assertEquals(listOf("db-1"), picked)
    }

    @Test
    fun aLongPressOffersExportBetweenRenameAndDelete() {
        show(HistoryUi(calendar), SessionActions(actions.edit, actions.saveTitle, actions.askDelete, actions.confirmDelete,
                                                 export = { heard += "export:${it.value}" }))

        compose.onNodeWithText("Calendar").performTouchInput { longClick() }
        val tops = listOf("session-rename", "session-export", "session-delete").map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot().top }
        assertTrue("Rename…, then Export, then Delete: $tops", tops.zipWithNext().all { (a, b) -> a < b })
        compose.onNodeWithTag("session-export").performClick()

        assertEquals(listOf("export:db-1"), heard)
        compose.onNodeWithTag("session-export").assertDoesNotExist()  // the menu closed
        assertEquals(emptyList<String>(), picked)
    }

    @Test
    fun withoutExportTheRowsMenuHasNone() {
        show(HistoryUi(calendar), actions)

        compose.onNodeWithText("Calendar").performTouchInput { longClick() }

        compose.onNodeWithTag("session-export").assertDoesNotExist()
        compose.onNodeWithTag("session-rename").assertExists()
        compose.onNodeWithTag("session-delete").assertExists()
    }

    @Test
    fun theRowBeingRenamedIsAFieldThatSavesItsTitle() {
        show(HistoryUi(calendar, editing = StoredSessionId("db-1")), actions)

        compose.onNodeWithTag("rename-field").assertTextEquals("Calendar")
        compose.onNodeWithTag("rename-field").performTextReplacement("Trip plans")
        compose.onNodeWithTag("rename-save").performClick()
        assertEquals(listOf("save:Trip plans"), heard)

        compose.onNodeWithTag("rename-field").performTextReplacement("  ")
        compose.onNodeWithTag("rename-save").assertIsNotEnabled()  // a blank title cannot be saved
        compose.onNodeWithTag("rename-cancel").performClick()
        assertEquals(listOf("save:Trip plans", "edit:null"), heard)
    }

    @Test
    fun aDeleteAsksBeforeItDeletes() {
        show(HistoryUi(calendar, confirmDelete = StoredSessionId("db-1")), actions)

        compose.onNodeWithTag("delete-dialog").assertExists()
        compose.onNodeWithText("This will permanently delete “Calendar”", substring = true).assertExists()
        compose.onNodeWithTag("delete-cancel").performClick()
        compose.onNodeWithTag("delete-confirm").performClick()

        assertEquals(listOf("ask:null", "confirm"), heard)
    }

    @Test
    fun withoutActionsARowOnlyContinues() {
        show(HistoryUi(calendar))

        compose.onNodeWithText("Calendar").performTouchInput { longClick() }

        compose.onNodeWithTag("session-rename").assertDoesNotExist()
        assertEquals(listOf("db-1"), picked)  // no menu: the long press is a tap (no onLongClick, no long-press job)
    }
}
