package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A reply the model is still writing stops on Stop: the core interrupts the turn and ends it as interrupted. */
@Smoke
@RunWith(AndroidJUnit4::class)
class StopSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun aReplyBeingWrittenStopsOnStop() {
        steps.setUpEndpoint()
        val before = steps.chat().entries.size

        compose.onNodeWithTag("draft").performTextInput("Write a 2000-word essay on the history of the printing press.")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(600_000) { !steps.chat().streaming.isNullOrEmpty() }  // the model is writing
        compose.onNodeWithTag("stop").performClick()
        compose.waitUntil(120_000) { !steps.chat().running }

        val turn = steps.chat().entries.drop(before)
        Log.i(SmokeSteps.TAG, "stopped turn: $turn")
        assertEquals("the turn ended as interrupted: $turn", TurnStatus.INTERRUPTED,
                     turn.filterIsInstance<Entry.Reply>().lastOrNull()?.status)
    }
}
