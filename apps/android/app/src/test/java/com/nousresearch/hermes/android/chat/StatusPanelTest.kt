package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Status sheet (protocol-21, merged into sessions-12): the core's `/status` text, exactly as it wrote it. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a launched read runs until it suspends
class StatusPanelTest {
    private val text = "Hermes TUI Status\n\nSession ID: s1\nModel: (unknown) (unknown)\nAgent Running: No"
    private var status: suspend () -> String = { text }
    private val panel = StatusPanel(status = { status() })

    @Test
    fun openingShowsLoadingThenTheCoresTextAsItIs() = runTest {
        var seen: StatusUi? = null
        status = { seen = panel.state.value; text }

        panel.open()

        assertEquals(StatusUi(), seen)  // loading: no text, no error
        assertEquals(StatusUi(text = text), panel.state.value)
        assertEquals(text, panel.state.value?.text)  // byte for byte: never split, trimmed or rewritten (D10)
    }

    @Test
    fun aFailureIsShownInWords() = runTest {
        status = { throw CoreError(4001, "session not found") }
        panel.open()
        assertEquals(StatusUi(error = "session not found"), panel.state.value)

        status = { throw SetupRefusal(ChatSession.NO_STATUS) }
        panel.open()
        assertEquals(StatusUi(error = ChatSession.NO_STATUS), panel.state.value)
    }

    @Test
    fun aSheetClosedWhileReadingStaysClosed() = runTest(UnconfinedTestDispatcher()) {
        val answer = CompletableDeferred<String>()
        status = { answer.await() }
        backgroundScope.launch { panel.open() }
        assertEquals(StatusUi(), panel.state.value)

        panel.close()
        answer.complete(text)

        assertNull(panel.state.value)
    }

    @Test
    fun closingClearsIt() = runTest {
        panel.open()

        panel.close()

        assertNull(panel.state.value)
    }
}
