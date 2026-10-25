package com.nousresearch.hermes.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.chat.Prompt
import com.nousresearch.hermes.android.turn.SystemNotifier
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A question that waits, or a reply that is done, reaches the user as a notification while the app is not on screen. */
@RunWith(AndroidJUnit4::class)
class SystemNotifierTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var visible = false
    private val notifier = SystemNotifier(context) { visible }
    private val approval = Prompt.Approval("srq-1", "r1", "rm -rf build", "recursive delete", listOf(ApprovalChoice.ONCE))

    private fun shown(id: Int): StatusBarNotification? = manager.activeNotifications.firstOrNull { it.id == id }

    // The system posts and cancels asynchronously: wait for what the app asked for, in order.
    private fun posted(id: Int): Notification {
        waitUntil("notification $id is shown") { shown(id) != null }
        return shown(id)!!.notification
    }

    private fun gone(id: Int) = waitUntil("notification $id is gone") { shown(id) == null }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting until $what" }
            SystemClock.sleep(50)
        }
    }

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        notifier.shown()
    }

    @After
    fun clear() = notifier.shown()

    @Test
    fun aWaitingQuestionIsNotifiedUntilItIsSettled() {
        notifier.promptPending(approval)

        val posted = posted(SystemNotifier.QUESTION_ID)
        assertEquals("Hermes asks to run a command", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("rm -rf build", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        if (Build.VERSION.SDK_INT >= 26) assertEquals(SystemNotifier.QUESTIONS, posted.channelId)

        notifier.promptsSettled()

        gone(SystemNotifier.QUESTION_ID)
    }

    @Test
    fun theLockScreenSaysOnlyThatHermesAsks() {
        notifier.promptPending(Prompt.Secret("srq-2", "TAVILY_API_KEY", "Tavily API key"))

        val posted = posted(SystemNotifier.QUESTION_ID)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("Hermes needs a key", posted.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertNull(posted.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun aFinishedReplyIsNotifiedWithItsText() {
        notifier.turnFinished(Entry.Reply("Three meetings today.", TurnStatus.COMPLETE))

        val posted = posted(SystemNotifier.REPLY_ID)
        assertEquals("Hermes replied", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Three meetings today.", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun nothingIsPostedWhileTheAppIsOnScreen() {
        visible = true

        notifier.promptPending(approval)
        visible = false
        notifier.turnFinished(Entry.Reply("done", TurnStatus.COMPLETE))  // posted after the question would have been

        posted(SystemNotifier.REPLY_ID)
        assertNull(shown(SystemNotifier.QUESTION_ID))
    }

    @Test
    fun openingTheAppClearsWhatItWouldSay() {
        val app = context.applicationContext as HermesApplication
        app.notifier.promptPending(approval)
        app.notifier.turnFinished(Entry.Reply("done", TurnStatus.COMPLETE))
        posted(SystemNotifier.QUESTION_ID)
        posted(SystemNotifier.REPLY_ID)

        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntil("the app's notices are gone") { shown(SystemNotifier.QUESTION_ID) == null && shown(SystemNotifier.REPLY_ID) == null }
        }
    }
}
