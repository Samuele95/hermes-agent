package com.nousresearch.hermes.android

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.chat.Prompt
import com.nousresearch.hermes.android.turn.SystemNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A question the agent asks while the app is in the background reaches the user as a notification; opening it
 * brings the chat back with the question, and the answer finishes the turn.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class BackgroundQuestionSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)
    private val notifications = steps.app.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    private fun question(): Notification? = notifications.activeNotifications.firstOrNull { it.id == SystemNotifier.QUESTION_ID }?.notification

    private fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting until $what" }
            SystemClock.sleep(200)
        }
    }

    @Test
    fun aQuestionAskedInTheBackgroundIsNotifiedAndAnsweredFromTheApp() {
        steps.setUpEndpoint()
        val before = steps.chat().entries.size

        compose.onNodeWithTag("draft").performTextInput(
            "Use the clarify tool to ask me which colour I prefer (choices: red, green, blue), then tell me my answer.")
        compose.onNodeWithTag("send").performClick()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

        waitUntil("the question is notified", 600_000) { question() != null }
        val posted = question()!!
        Log.i(SmokeSteps.TAG, "notified: ${posted.extras.getString(Notification.EXTRA_TITLE)} / ${posted.extras.getCharSequence(Notification.EXTRA_TEXT)}")
        assertEquals("Hermes has a question", posted.extras.getString(Notification.EXTRA_TITLE))

        posted.contentIntent.send()  // what tapping it does
        waitUntil("the app is back and its notice gone", 30_000) { question() == null }
        val asked = steps.chat().prompts.single() as Prompt.Clarify
        (steps.app.core.value as CoreState.Ready).chat.clarify(asked, mapOf(asked.questions.first().qid to "blue"))
        waitUntil("the turn ends", 600_000) { !steps.chat().running }

        val reply = steps.chat().entries.drop(before).filterIsInstance<Entry.Reply>().last()
        Log.i(SmokeSteps.TAG, "reply after the answer: ${reply.text}")
        assertTrue("the reply carries the answer: ${reply.text}", reply.text.contains("blue", ignoreCase = true))
    }
}
