package com.nousresearch.hermes.android

import com.nousresearch.hermes.bridge.CoreError
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** What a failed step tells the user: the core's words, else only the kind of failure; a cancellation goes on. */
class FailuresTest {
    @Test
    fun theCoresRefusalIsShownInItsOwnWords() {
        assertEquals("DeepSeek did not accept the key.", forUser(CoreError(0, "DeepSeek did not accept the key.")))
    }

    @Test
    fun anyOtherFailureIsNamedOnlyByItsKind() {
        assertEquals("Something went wrong (RuntimeException).", forUser(RuntimeException("Traceback ... sk-secret")))
    }

    @Test
    fun aCancellationIsNotAFailureAndGoesOn() {
        assertThrows(CancellationException::class.java) { forUser(CancellationException("screen closed")) }
    }
}
