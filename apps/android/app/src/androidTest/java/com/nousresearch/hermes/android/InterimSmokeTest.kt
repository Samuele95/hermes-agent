package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real turn on the phone's model where the agent says what it is about to do, runs a tool, then answers: the
 * commentary stays in the transcript as its own segment, in the order the core sent it beside the tool.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class InterimSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun commentaryBesideAToolStaysInTheTranscript() {
        steps.setUpEndpoint()
        val gateway = (steps.app.core.value as CoreState.Ready).gateway
        val frames = Collections.synchronizedList(mutableListOf<String>())
        val watching = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.collect { if (it.type in setOf("message.interim", "tool.start")) frames += it.type }
        }
        try {
            val turn = steps.say("Before you use any tool, write one short sentence saying what you are about to do. " +
                                 "Then run the shell command date with the terminal tool. " +
                                 "Then tell me today's weekday in one sentence.")
            Log.i(SmokeSteps.TAG, "frames $frames")
            Log.i(SmokeSteps.TAG, "commentary turn $turn")
            Log.i(SmokeSteps.TAG, "interim-before-tool=${frames.indexOf("message.interim") < frames.indexOf("tool.start")}")
            steps.screenshot("b2-interim")

            val reply = turn.filterIsInstance<Entry.Reply>().last()
            // Desktop's #125951: a final answer the core also delivered as commentary (observed, not asserted here).
            Log.i(SmokeSteps.TAG, "interim-equals-reply=${turn.any { it is Entry.Interim && it.text == reply.text.trim() }}")
            assertTrue("the turn completed with an answer: $turn", reply.status == TurnStatus.COMPLETE && reply.text.isNotBlank())
            assertTrue("a tool ran: $turn", turn.any { it is Entry.Tool })
            assertTrue("the model wrote no commentary beside its tool: $frames / $turn", "message.interim" in frames)
            val order = turn.mapNotNull {
                when (it) {
                    is Entry.Interim -> "message.interim"
                    is Entry.Tool -> "tool.start"
                    else -> null
                }
            }
            assertEquals("the transcript keeps the wire order", frames.toList(), order)
        } finally {
            watching.cancel()
        }
    }
}
