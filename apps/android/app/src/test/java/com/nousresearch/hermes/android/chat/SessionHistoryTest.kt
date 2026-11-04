package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private var resume: suspend (String) -> Unit = { }
    private val history = SessionHistory(list = { list() }, resume = { resumed += it; resume(it) },
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

    /** Two taps on real threads, released within nanoseconds of each other; [tap] runs on each, after [arm] on a fresh drawer. */
    private fun doubleTapped(arm: suspend (SessionHistory) -> Unit, tap: suspend (SessionHistory) -> Unit,
                             make: (AtomicInteger) -> SessionHistory) {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(ROUNDS) { round ->
                val calls = AtomicInteger()
                val drawer = make(calls)
                runBlocking { arm(drawer) }
                val ready = CountDownLatch(2)
                val go = AtomicBoolean(false)
                val taps = List(2) {
                    pool.submit {
                        runBlocking {
                            ready.countDown()
                            while (!go.get()) Thread.onSpinWait()
                            tap(drawer)
                        }
                    }
                }
                ready.await()
                go.set(true)
                taps.forEach { it.get(10, SECONDS) }
                assertEquals("round $round", 1, calls.get())
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun aDoubleTappedDeleteDeletesOnce() = doubleTapped(
        arm = { it.open(); it.askDelete("db-old") },
        tap = { it.confirmDelete() },
        make = { calls -> SessionHistory(list = { rows }, resume = {}, rename = { _, _ -> }, delete = { calls.incrementAndGet() }) })

    @Test
    fun aDoubleTappedSaveRenamesOnce() = doubleTapped(
        arm = { it.open(); it.edit("db-old") },
        tap = { it.saveTitle("Trip") },
        make = { calls -> SessionHistory(list = { rows }, resume = {}, rename = { _, _ -> calls.incrementAndGet() }, delete = {}) })

    @Test
    fun aCancelledDeleteLeavesTheDrawerUsable() = runTest(UnconfinedTestDispatcher()) {
        delete = { awaitCancellation() }
        history.open()
        history.askDelete("db-old")
        val tap = backgroundScope.launch { history.confirmDelete() }
        assertEquals(true, history.state.value?.busy)

        tap.cancel()

        val ui = history.state.value
        assertEquals(false, ui?.busy)
        assertEquals(rows, ui?.sessions)
        assertNull(ui?.error)
        history.askDelete("db-old")
        assertEquals("db-old", history.state.value?.confirmDelete)
    }

    @Test
    fun aCancelledRenameKeepsTheFieldAndTheDrawerUsable() = runTest(UnconfinedTestDispatcher()) {
        rename = { _, _ -> awaitCancellation() }
        history.open()
        history.edit("db-old")
        val tap = backgroundScope.launch { history.saveTitle("Trip") }
        assertEquals(true, history.state.value?.busy)

        tap.cancel()

        assertEquals(false, history.state.value?.busy)
        assertEquals("db-old", history.state.value?.editing)  // as after a refusal (V9)
    }

    @Test
    fun aCancelledListAfterAChangeLeavesTheDrawerUsable() = runTest(UnconfinedTestDispatcher()) {
        history.open()
        list = { awaitCancellation() }
        history.edit("db-old")
        val tap = backgroundScope.launch { history.saveTitle("Trip") }
        assertEquals(listOf("db-old" to "Trip"), renamed)  // the rename went through; the list read after it hangs
        assertEquals(true, history.state.value?.busy)

        tap.cancel()

        assertEquals(false, history.state.value?.busy)
    }

    @Test
    fun aCancelledPickLeavesTheDrawerUsable() = runTest(UnconfinedTestDispatcher()) {
        resume = { awaitCancellation() }
        history.open()
        val tap = backgroundScope.launch { history.pick("db-old") }
        assertEquals(true, history.state.value?.busy)

        tap.cancel()

        assertEquals(false, history.state.value?.busy)
        assertEquals(rows, history.state.value?.sessions)  // still open
    }

    private companion object {
        const val ROUNDS = 500
    }
}
