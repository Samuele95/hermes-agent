package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The list of past conversations: open to see them, pick one to continue it. */
class SessionHistoryTest {
    private val rows = listOf(SessionListRow(id = "db-old", title = "Calendar", messageCount = 3))
    private val resumed = mutableListOf<String>()
    private var list: suspend () -> List<SessionListRow> = { rows }
    private val history = SessionHistory(list = { list() }, resume = { resumed += it })

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
}
