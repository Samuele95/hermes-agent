package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The CP4 smoke: set up a custom endpoint from the screen, send "ping", get a real reply. `scripts/smoke.sh`
 * runs it with the endpoint (`-e baseUrl … -e model …`); connectedAndroidTest leaves [Smoke] tests out.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun aCustomEndpointSetUpOnTheScreenAnswersPing() {
        steps.setUpEndpoint()

        val began = System.currentTimeMillis()
        val reply = steps.say("ping").filterIsInstance<Entry.Reply>().lastOrNull()
        Log.i(SmokeSteps.TAG, "reply after ${System.currentTimeMillis() - began} ms: $reply")
        assertEquals("the turn completed: $reply / ${steps.chat().entries}", TurnStatus.COMPLETE, reply?.status)
        assertTrue("the reply has text", reply!!.text.isNotBlank() && reply.error == null)
    }
}

/** A test that needs something outside the device (an endpoint); `scripts/smoke.sh` runs it. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Smoke
