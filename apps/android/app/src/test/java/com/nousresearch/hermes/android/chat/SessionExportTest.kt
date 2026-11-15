package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.contract.SessionExportReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A session's export (sessions-12): the finished file waits until the showing activity has shared it. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a launched export runs until it suspends
class SessionExportTest {
    private val f = ExportFile("/c/exports/capitals-20261115.json", "capitals-20261115.json", 1_234L)
    private val asked = mutableListOf<StoredSessionId?>()
    private var answer: suspend (StoredSessionId?) -> ExportFile = { f }
    private val export = SessionExport(export = { asked += it; answer(it) })

    @Test
    fun aFinishedExportWaitsUntilSharedThenClears() = runTest {
        export.export(StoredSessionId("db-old"))

        assertEquals(ExportUi(file = f), export.state.value)
        assertEquals(listOf(StoredSessionId("db-old")), asked)

        export.shared(f)

        assertEquals(ExportUi(), export.state.value)
    }

    @Test
    fun aRefusalShowsItsWordsAndNoFile() = runTest {
        answer = { throw CoreError(0, "Nothing to export yet: Hermes has not saved this conversation.") }

        export.export(null)

        assertEquals(ExportUi(error = "Nothing to export yet: Hermes has not saved this conversation."), export.state.value)

        export.dismiss()

        assertEquals(ExportUi(), export.state.value)
    }

    @Test
    fun aSecondTapWhileOneRunsExportsOnce() = runTest(UnconfinedTestDispatcher()) {
        val written = CompletableDeferred<ExportFile>()
        answer = { written.await() }

        backgroundScope.launch { export.export(null) }
        backgroundScope.launch { export.export(null) }  // the double tap

        assertEquals(ExportUi(busy = true), export.state.value)
        written.complete(f)
        assertEquals(1, asked.size)
        assertEquals(ExportUi(file = f), export.state.value)
    }

    @Test
    fun aFileNotYetSharedAdmitsNoOtherExport() = runTest {
        export.export(null)

        export.export(StoredSessionId("db-other"))

        assertEquals(listOf<StoredSessionId?>(null), asked)
        assertEquals(ExportUi(file = f), export.state.value)
    }

    @Test
    fun aCancelledExportLeavesItIdle() = runTest(UnconfinedTestDispatcher()) {
        answer = { awaitCancellation() }
        val tap = backgroundScope.launch { export.export(null) }
        assertEquals(ExportUi(busy = true), export.state.value)

        tap.cancel()

        assertEquals(ExportUi(), export.state.value)
        answer = { f }
        export.export(null)  // the next tap is admitted
        assertEquals(ExportUi(file = f), export.state.value)
    }

    @Test
    fun aSharedOlderFileLeavesTheNewerOne() = runTest {
        val old = f.copy(path = "/c/exports/old-20261114.json", name = "old-20261114.json")
        export.export(null)

        export.shared(old)

        assertEquals(ExportUi(file = f), export.state.value)
    }

    @Test
    fun theRepliesSizeIsCarriedWhole() {
        assertEquals(ExportFile("/c/exports/a.json", "a.json", 42L), ExportFile.of(SessionExportReply("/c/exports/a.json", "a.json", 42)))
    }
}
