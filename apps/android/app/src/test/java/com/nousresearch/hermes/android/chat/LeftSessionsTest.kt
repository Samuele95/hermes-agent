package com.nousresearch.hermes.android.chat

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** The sessions a chat left: one close each, once its build settled, unless the chat came back; every wait bounded. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a close runs as soon as it can
class LeftSessionsTest {
    private val closes = mutableListOf<String>()
    private var close: suspend (RuntimeSessionId) -> Unit = { closes += it.value }
    private var here: RuntimeSessionId? = null
    private val s1 = SessionRef(RuntimeSessionId("s1"), StoredSessionId("db1"))

    private fun TestScope.sessions() =
        LeftSessions(backgroundScope, Mutex(), stillHere = { it == here }, close = { close(it) })

    /** [step], failing fast in virtual time instead of hanging the run: a mutant's unbounded wait is a red test. */
    private suspend fun <T> soon(step: suspend () -> T): T = withTimeout(5.minutes) { step() }

    @Test
    fun aSessionLeftTwiceBeforeItSettlesIsClosedOnceAndBothLeavesHearIt() = runTest(UnconfinedTestDispatcher()) {
        val left = sessions()
        val a = left.leave(s1)
        val b = left.leave(s1)  // come back to and left again before its build announced itself

        assertSame(a, b)
        left.settled(RuntimeSessionId("s1"))

        assertEquals(listOf("s1"), closes)
        assertEquals(LeftSessions.Outcome.CLOSED, soon { a.await() })
        assertEquals(LeftSessions.Outcome.CLOSED, soon { b.await() })
    }

    @Test
    fun aBuildThatNeverSettlesIsLeftLiveAfterTheBound() = runTest(UnconfinedTestDispatcher()) {
        val left = sessions()

        assertEquals(LeftSessions.Outcome.UNSETTLED, soon { left.leave(s1).await() })
        assertEquals(emptyList<String>(), closes)

        left.settled(RuntimeSessionId("s1"))  // late: nothing waits on it any more
        assertEquals(emptyList<String>(), closes)

        assertEquals(LeftSessions.Outcome.CLOSED, soon { left.leave(s1).await() })  // settled now: closed at once
        assertEquals(listOf("s1"), closes)
    }

    @Test
    fun theChatComingBackKeepsTheSessionAndEndsItsRecord() = runTest(UnconfinedTestDispatcher()) {
        val left = sessions()
        here = RuntimeSessionId("s1")
        val a = left.leave(s1)
        var b: Deferred<LeftSessions.Outcome>? = null
        // The earliest a leave can follow: inside the outcome's completion, which runs as the outcome is set.
        a.invokeOnCompletion { here = null; b = left.leave(s1) }
        left.settled(RuntimeSessionId("s1"))

        assertEquals(LeftSessions.Outcome.KEPT, soon { a.await() })
        val again = checkNotNull(b)
        assertNotSame(a, again)  // a new record: the kept one had ended before its outcome was heard
        assertEquals(LeftSessions.Outcome.CLOSED, soon { again.await() })
        assertEquals(listOf("s1"), closes)
    }

    @Test
    fun aFailedCloseKeepsTheMarkSoTheNextLeaveTriesAgain() = runTest(UnconfinedTestDispatcher()) {
        val left = sessions()
        left.settled(RuntimeSessionId("s1"))
        close = { throw IllegalStateException("bridge gone") }

        assertEquals(LeftSessions.Outcome.FAILED, soon { left.leave(s1).await() })

        close = { closes += it.value }
        assertEquals(LeftSessions.Outcome.CLOSED, soon { left.leave(s1).await() })  // no new session.info needed
        assertEquals(listOf("s1"), closes)
    }

    @Test
    fun aRecordGoesWithItsOutcome() = runTest(UnconfinedTestDispatcher()) {
        val left = sessions()
        val a = left.leave(s1)

        assertSame(a, left.closing(StoredSessionId("db1")))
        assertNull(left.closing(StoredSessionId("db-other")))

        left.settled(RuntimeSessionId("s1"))
        soon { a.await() }
        assertNull(left.closing(StoredSessionId("db1")))
    }

    @Test
    fun aWaitForACloseIsBounded() = runTest(UnconfinedTestDispatcher()) {
        val left = sessions()
        left.settled(RuntimeSessionId("s1"))
        close = { awaitCancellation() }  // a close that never returns

        assertNull(soon { left.waitFor(left.leave(s1)) })  // the 60 s bound expires inside soon's 5 minutes
    }
}
