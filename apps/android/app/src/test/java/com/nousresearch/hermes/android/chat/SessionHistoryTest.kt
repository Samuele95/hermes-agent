package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The list of past conversations: open to see them, pick one to continue it, rename or delete one. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a launched change runs until it suspends
class SessionHistoryTest {
    private val rows = listOf(SessionListRow(id = "db-old", title = "Calendar", messageCount = 3))
    private val resumed = mutableListOf<String>()
    private val renamed = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private var list: suspend () -> List<SessionListRow> = { rows }
    private var rename: suspend (String, String) -> Unit = { _, _ -> }
    private var delete: suspend (String) -> Unit = { }
    private val history = SessionHistory(list = { list() }, resume = { resumed += it },
                                         rename = { id, t -> renamed += id to t; rename(id, t) },
                                         delete = { deleted += it; delete(it) })

    @Test
    fun openingListsThePastSessions() = runTest {
        history.open()

        assertEquals(HistoryUi(rows), history.state.value)
    }

    @Test
    fun pickingASessionContinuesItAndClosesTheList() = runTest {
        history.open()

        history.pick("db-old")

        assertEquals(listOf("db-old"), resumed)
        assertNull(history.state.value)
    }

    @Test
    fun aFailureIsShownAndTheListStays() = runTest {
        list = { throw CoreError(4001, "session store unavailable") }

        history.open()

        assertEquals(HistoryUi(emptyList(), error = "session store unavailable"), history.state.value)
    }

    @Test
    fun savingATitleRenamesTheRowAndListsTheRowsAgain() = runTest {
        history.open()
        history.edit("db-old")
        assertEquals("db-old", history.state.value?.editing)
        val newRows = listOf(SessionListRow(id = "db-old", title = "Trip", messageCount = 3))
        list = { newRows }

        history.saveTitle("  Trip  ")

        assertEquals(listOf("db-old" to "Trip"), renamed)
        assertEquals(HistoryUi(newRows), history.state.value)  // the core's title, not a predicted one
    }

    @Test
    fun anUnchangedOrBlankTitleSendsNothing() = runTest {
        history.open()
        history.edit("db-old")

        history.saveTitle("Calendar")  // unchanged: the field closes
        assertEquals(emptyList<Pair<String, String>>(), renamed)
        assertNull(history.state.value?.editing)

        history.edit("db-old")
        history.saveTitle("   ")  // blank: the core would refuse it
        assertEquals(emptyList<Pair<String, String>>(), renamed)
        assertEquals("db-old", history.state.value?.editing)
    }

    @Test
    fun aRefusedTitleKeepsTheFieldWithTheCoresWords() = runTest {
        rename = { _, _ -> throw CoreError(4022, "Title 'Trip' is already in use by session db-x") }
        history.open()
        history.edit("db-old")

        history.saveTitle("Trip")

        val ui = history.state.value
        assertEquals("db-old", ui?.editing)
        assertEquals("Title 'Trip' is already in use by session db-x", ui?.error)
        assertEquals(false, ui?.busy)
        assertEquals(rows, ui?.sessions)
    }

    @Test
    fun deletingAsksFirstAndDeletesOnlyWhenConfirmed() = runTest {
        history.open()

        history.askDelete("db-old")
        assertEquals("db-old", history.state.value?.confirmDelete)
        assertEquals(emptyList<String>(), deleted)

        history.askDelete(null)  // the user cancelled
        assertNull(history.state.value?.confirmDelete)
        history.confirmDelete()
        assertEquals(emptyList<String>(), deleted)

        history.askDelete("db-old")
        list = { emptyList() }
        history.confirmDelete()

        assertEquals(listOf("db-old"), deleted)
        assertEquals(HistoryUi(emptyList()), history.state.value)
    }

    @Test
    fun aRefusedDeleteIsShownAndTheRowsStay() = runTest {
        delete = { throw SetupRefusal(ChatSession.STILL_WORKING) }
        history.open()
        history.askDelete("db-old")

        history.confirmDelete()

        val ui = history.state.value
        assertEquals(ChatSession.STILL_WORKING, ui?.error)
        assertEquals(rows, ui?.sessions)
        assertNull(ui?.confirmDelete)
        assertEquals(false, ui?.busy)
    }

    @Test
    fun renamingAndDeletingAreOneAtATime() = runTest {
        history.open()

        history.edit("db-old")
        history.askDelete("db-old")
        assertNull(history.state.value?.editing)
        assertEquals("db-old", history.state.value?.confirmDelete)

        history.edit("db-old")
        assertNull(history.state.value?.confirmDelete)
        assertEquals("db-old", history.state.value?.editing)
    }

    @Test
    fun aClosedDrawerStaysClosedAfterAChange() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        rename = { _, _ -> gate.await() }
        history.open()
        history.edit("db-old")
        backgroundScope.launch { history.saveTitle("Trip") }
        assertEquals(true, history.state.value?.busy)

        history.close()
        gate.complete(Unit)

        assertNull(history.state.value)
        assertFalse(renamed.isEmpty())
    }
}
