package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.turn.TurnService
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionHistoryParams
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The context sheet on the phone's model, through the core: the meter opens the core's breakdown, and Compress runs
 * the core's compression under the turn service, after which the chat shows the transcript the core kept.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class CompressSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun aLongChatIsCompressedFromTheMeterAndShowsTheCoresTranscript() {
        steps.setUpEndpoint()
        val ready = steps.app.core.value as CoreState.Ready
        fun coreUsers() = runBlocking {
            ready.gateway.call(Methods.SESSION_HISTORY, SessionHistoryParams(sessionId = ready.chat.sessionId))
        }.messages.filter { it.role == "user" && it.displayKind == null }.map { it.text.orEmpty() }
        fun shownUsers() = steps.chat().entries.filterIsInstance<Entry.User>().map { it.text }
        val statuses = Collections.synchronizedList(mutableListOf<String>())
        val storedIds = Collections.synchronizedList(mutableListOf<String>())
        val watching = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            ready.gateway.events.collect { event ->
                when (event.type) {
                    Events.STATUS_UPDATE.name -> event.decode(Events.STATUS_UPDATE)?.let { statuses += "${it.kind}:${it.text}" }
                    Events.SESSION_INFO.name -> event.decode(Events.SESSION_INFO)?.let { storedIds += it.storedSessionId }
                }
            }
        }
        try {
            steps.say("Remember the word kiwi7311 and reply with the single word ok.")
            steps.say("Reply with the single word two.")
            steps.say("Reply with the single word three.")

            // The meter opens the sheet, which reads the core's breakdown.
            compose.waitUntil(60_000) { steps.shown("context-meter") }
            val meterBefore = steps.chat().meter
            compose.onNodeWithTag("context-meter").performClick()
            compose.waitUntil(120_000) { ready.context.state.value.let { it != null && (it.breakdown != null || it.error != null) } }
            val opened = ready.context.state.value!!
            val breakdown = opened.breakdown
            assertNotNull("the core's breakdown (error: ${opened.error})", breakdown)
            assertTrue("categories: $breakdown", breakdown!!.categories.isNotEmpty())
            Log.i(SmokeSteps.TAG, "breakdown: ${breakdown.categories.joinToString { "${it.id}=${it.tokens}" }} " +
                                  "used ${breakdown.contextUsed}/${breakdown.contextMax} (${breakdown.contextSource})")
            steps.screenshot("b7-context")

            // Compress, keeping the last exchange verbatim: a three-exchange chat has something to summarize.
            val before = shownUsers()
            compose.onNodeWithTag("context-focus").performScrollTo().performTextInput("here 1")
            compose.onNodeWithTag("compress").performScrollTo().performClick()
            val began = System.currentTimeMillis()
            var held = false
            compose.waitUntil(900_000) {
                if (!TurnService.turns.isIdle) held = true
                ready.context.state.value.let { it != null && !it.busy && (it.outcome != null || it.error != null) }
            }
            val done = ready.context.state.value!!
            Log.i(SmokeSteps.TAG, "compress answered after ${System.currentTimeMillis() - began} ms: outcome=${done.outcome} error=${done.error}")
            assertNull("the core's compression (statuses: $statuses)", done.error)
            assertNotNull("an outcome", done.outcome)
            assertTrue("the turn service held the compression", held)
            assertTrue("the hold was released", TurnService.turns.isIdle)
            assertEquals("the chat shows the core's transcript", coreUsers(), shownUsers())
            if (done.outcome!!.startsWith("Compressed")) assertTrue("fewer prompts shown: $before → ${shownUsers()}", shownUsers().size < before.size)
            assertNull("no status left on the idle chat (D5)", steps.chat().activity)
            // D7: the breakdown was re-read after the compression.
            compose.waitUntil(120_000) { ready.context.state.value?.let { it.breakdown != null || it.error != null } == true }
            assertNotNull("the breakdown re-read (error: ${ready.context.state.value?.error})", ready.context.state.value?.breakdown)
            Log.i(SmokeSteps.TAG, "meter before $meterBefore after ${steps.chat().meter}; shown users $before → ${shownUsers()}")
            steps.screenshot("b7-compressed")
        } finally {
            watching.cancel()
            Log.i(SmokeSteps.TAG, "statuses: $statuses; stored ids: $storedIds")
        }
    }
}
