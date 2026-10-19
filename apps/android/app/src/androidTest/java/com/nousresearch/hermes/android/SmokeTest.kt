package com.nousresearch.hermes.android

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    private val args = InstrumentationRegistry.getArguments()

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aCustomEndpointSetUpOnTheScreenAnswersPing() {
        val baseUrl = requireNotNull(args.getString("baseUrl")) { "no endpoint given: run scripts/smoke.sh" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication

        compose.waitUntil(120_000) { shown("setup") || shown("draft") }
        if (!shown("setup")) compose.onNodeWithTag("open-setup").performClick()
        compose.waitUntil(60_000) { shown("add-endpoint") }
        compose.onNodeWithTag("add-endpoint").performClick()
        compose.onNodeWithTag("endpoint-name").performTextInput(args.getString("name", "Smoke endpoint"))
        compose.onNodeWithTag("endpoint-url").performTextInput(baseUrl)
        compose.onNodeWithTag("endpoint-model").performTextInput(args.getString("model")!!)
        args.getString("key")?.let { compose.onNodeWithTag("endpoint-key").performTextInput(it) }
        compose.onNodeWithTag("endpoint-save").performClick()

        compose.waitUntil(120_000) { shown("draft") }
        compose.onNodeWithTag("draft").performTextInput("ping")
        compose.onNodeWithTag("send").performClick()

        val began = System.currentTimeMillis()
        compose.waitUntil(args.getString("replyTimeoutMs", "900000").toLong()) { shown("entry-reply") || shown("entry-notice") }
        val chat = (app.core.value as CoreState.Ready).chat.state.value
        val reply = chat.entries.filterIsInstance<Entry.Reply>().lastOrNull()
        Log.i(TAG, "reply after ${System.currentTimeMillis() - began} ms: $reply")
        assertEquals("the turn completed: $reply / ${chat.entries}", TurnStatus.COMPLETE, reply?.status)
        assertTrue("the reply has text", reply!!.text.isNotBlank() && reply.error == null)
    }

    private companion object {
        const val TAG = "HermesSmoke"
    }
}

/** A test that needs something outside the device (an endpoint); `scripts/smoke.sh` runs it. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Smoke
