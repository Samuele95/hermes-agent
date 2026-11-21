package com.nousresearch.hermes.android.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether the transcript follows its newest row (chat-17): Desktop's stick-to-bottom, as the view's own state. */
class FollowTest {
    @Test
    fun aReaderDraggingBackStopsTheFollow() {
        assertFalse(follows(was = true, atEnd = false, draggedBack = true))
    }

    @Test
    fun contentGrowingBelowAFollowerKeepsIt() {
        // A delta lands before the follow scroll: the end moved away, the reader did not.
        assertTrue(follows(was = true, atEnd = false, draggedBack = false))
    }

    @Test
    fun aReaderAwayStaysAwayWhileTheReplyGrows() {
        assertFalse(follows(was = false, atEnd = false, draggedBack = false))
    }

    @Test
    fun reachingTheEndFollowsAgain() {
        assertTrue(follows(was = false, atEnd = true, draggedBack = false))
        // With nothing below there is nothing to read back.
        assertTrue(follows(was = false, atEnd = true, draggedBack = true))
    }
}
