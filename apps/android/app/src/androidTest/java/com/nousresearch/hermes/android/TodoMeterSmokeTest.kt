package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.bridge.protocol.Events
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real turn on the phone's model where the agent plans with its todo tool and works through the plan: the plan
 * shows above the composer while it works, the chat mirrors the core's last revision, and the core's usage reading
 * puts the context meter beside the model.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class TodoMeterSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun thePlanAndTheContextFillReachTheScreen() {
        steps.setUpEndpoint()
        val ready = steps.app.core.value as CoreState.Ready
        val revisions = Collections.synchronizedList(mutableListOf<Long>())
        val toolRevisions = Collections.synchronizedList(mutableListOf<Long?>())
        val tools = Collections.synchronizedList(mutableListOf<String>())
        val usageTicks = AtomicInteger()
        val lastUsage = AtomicReference<String?>()
        val sawPlan = AtomicBoolean()
        val watching = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            ready.gateway.events.collect { event ->
                when (event.type) {
                    Events.TODO_UPDATED.name -> event.decode(Events.TODO_UPDATED)?.let { revisions += it.revision }
                    Events.SESSION_USAGE.name -> usageTicks.incrementAndGet()
                    Events.TOOL_START.name -> event.decode(Events.TOOL_START)?.let { tools += it.name }
                    Events.TOOL_COMPLETE.name -> event.decode(Events.TOOL_COMPLETE)?.let { if (it.todos != null) toolRevisions += it.revision }
                    Events.MESSAGE_COMPLETE.name -> lastUsage.set(event.decode(Events.MESSAGE_COMPLETE)?.usage?.toString())
                }
            }
        }
        val mirroring = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            ready.chat.state.collect { if (it.running && it.todos?.active == true) sawPlan.set(true) }
        }
        try {
            val before = steps.chat().entries.size
            fun turnEnded() = steps.chat().entries.drop(before).any { it is Entry.Reply || it is Entry.Notice } && !steps.chat().running
            compose.onNodeWithTag("draft").performTextInput(
                "Use the todo_list tool to plan exactly three steps: boil water, steep the tea, pour the tea. " +
                "Then, one step at a time, mark the step in_progress with todo_list and then completed with todo_list. " +
                "When all three are completed, reply with the single word done.")
            compose.onNodeWithTag("send").performClick()
            val began = System.currentTimeMillis()
            compose.waitUntil(600_000) { steps.shown("todo-strip") || turnEnded() }
            if (steps.shown("todo-strip")) {
                Log.i(SmokeSteps.TAG, "strip shown after ${System.currentTimeMillis() - began} ms")
                compose.onNodeWithTag("todo-toggle").performClick()
                steps.screenshot("b6-todos")
            }
            compose.waitUntil(900_000) { turnEnded() }
            val turn = steps.chat().entries.drop(before + 1)
            Log.i(SmokeSteps.TAG, "todo turn after ${System.currentTimeMillis() - began} ms: $turn")
            Log.i(SmokeSteps.TAG, "tools $tools; todo.updated revisions $revisions; tool.complete revisions $toolRevisions")

            val tooled = turn.filterIsInstance<Entry.Tool>().map { it.name }
            assertTrue("no todo.updated frame arrived; the turn's tools: $tooled", revisions.isNotEmpty())
            assertTrue("the plan was never shown as active while the turn ran", sawPlan.get())
            assertEquals("the chat mirrors the core's last revision", revisions.last(), steps.chat().todos?.revision)
            val meter = steps.chat().meter
            Log.i(SmokeSteps.TAG, "meter $meter; session.usage ticks ${usageTicks.get()}; last message.complete usage ${lastUsage.get()}")
            assertNotNull("no meter after the turn; last message.complete usage: ${lastUsage.get()}", meter)
            assertTrue("the meter is not on the screen", steps.shown("context-meter"))
            assertTrue("the meter has no window: $meter", meter!!.max > 0)
            steps.screenshot("b6-meter")
        } finally {
            watching.cancel()
            mirroring.cancel()
        }
    }
}
