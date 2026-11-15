package com.nousresearch.hermes.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.Entry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text another app shares reaches the real app and core as an unsent draft (chat-42, bet 8). No model is needed:
 * nothing here is sent. The intents are started from the shell, as a share sheet starts them.
 */
@RunWith(AndroidJUnit4::class)
class IncomingTextDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val component = "${instrumentation.targetContext.packageName}/com.nousresearch.hermes.android.MainActivity"

    /** The intent the rule started the activity with; it tracks the activity by it, so [restoreTheIntent] puts it back. */
    private lateinit var launched: Intent

    @Before
    fun openTheChat() {
        launched = Intent(compose.activity.intent)
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        compose.waitUntil(60_000) {
            listOf("draft", "setup").any { compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodes(hasTestTag("setup")).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("setup-close").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("draft")).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * The activity's own `setIntent` (onNewIntent) hides it from the rule's scenario, which matches it by its start
     * intent: without this the scenario never sees it destroyed.
     */
    @After
    fun restoreTheIntent() {
        compose.runOnUiThread { compose.activity.intent = launched }
    }

    /** [cmd] run by the shell, read to its end so it has finished. It is split at spaces, unquoted: no text here has one. */
    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(cmd)).use { String(it.readBytes()) }

    private fun chat(): ChatSession = ((instrumentation.targetContext.applicationContext as HermesApplication).core.value as CoreState.Ready).chat

    private fun drafted(words: String) = compose.onAllNodes(hasTestTag("draft") and hasText(words, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aTextSharedWithAdbLandsInTheDraftAndIsNotSent() {
        shell("am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT shared-from-adb-42 -n $component")

        compose.waitUntil(15_000) { drafted("shared-from-adb-42") }
        compose.onNodeWithTag("offer").assertIsDisplayed()
        val now = chat().state.value
        assertNull(now.offer)  // taken into the draft, once
        assertFalse(now.busy)  // nothing went out
        assertTrue(now.entries.none { it is Entry.User && it.text.contains("shared-from-adb-42") })

        compose.onNodeWithTag("draft").performTextClearance()
    }

    @Test
    fun aShareReplayedFromRecentsIsNotOfferedAgain() {
        // FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY: Recents replays the intent the task began with (D10).
        shell("am start -W -f 0x00100000 -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT replayed-from-recents -n $component")
        SystemClock.sleep(3_000)

        assertFalse(drafted("replayed-from-recents"))
        assertNull(chat().state.value.offer)
    }
}
