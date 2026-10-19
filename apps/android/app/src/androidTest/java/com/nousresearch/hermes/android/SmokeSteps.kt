package com.nousresearch.hermes.android

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.Chat
import com.nousresearch.hermes.android.chat.Entry

/**
 * What the smoke tests do on the real screen, against the endpoint `scripts/smoke.sh` or
 * `scripts/restart_smoke.sh` passes (`-e baseUrl … -e model … -e key …`).
 */
class SmokeSteps(private val compose: ComposeTestRule) {
    val args = InstrumentationRegistry.getArguments()
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
    private val replyTimeoutMs = args.getString("replyTimeoutMs", "900000").toLong()

    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun shown(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    fun chat(): Chat = (app.core.value as CoreState.Ready).chat.state.value

    /** Sets up the passed endpoint from the setup screen, opening it from the chat when the app is configured. */
    fun setUpEndpoint() {
        val baseUrl = requireNotNull(args.getString("baseUrl")) { "no endpoint given: run scripts/smoke.sh" }
        compose.waitUntil(120_000) { shown("setup") || shown("draft") }
        if (!shown("setup")) compose.onNodeWithTag("open-setup").performClick()
        compose.waitUntil(60_000) { shown("add-endpoint") }
        compose.onNodeWithTag("add-endpoint").performClick()
        compose.onNodeWithTag("endpoint-name").performTextInput(args.getString("name", "Smoke endpoint"))
        compose.onNodeWithTag("endpoint-url").performTextInput(baseUrl)
        compose.onNodeWithTag("endpoint-model").performTextInput(requireNotNull(args.getString("model")))
        args.getString("key")?.let { compose.onNodeWithTag("endpoint-key").performTextInput(it) }
        compose.onNodeWithTag("endpoint-save").performClick()
        awaitChat()
    }

    fun awaitChat() = compose.waitUntil(120_000) { shown("draft") }

    /** Sends [text] from the screen and returns what its turn added to the transcript (tools, then the reply). */
    fun say(text: String): List<Entry> {
        val before = chat().entries.size
        compose.onNodeWithTag("draft").performTextInput(text)
        compose.onNodeWithTag("send").performClick()
        val began = System.currentTimeMillis()
        compose.waitUntil(replyTimeoutMs) { chat().entries.drop(before).any { it is Entry.Reply || it is Entry.Notice } && !chat().running }
        val turn = chat().entries.drop(before + 1)
        Log.i(TAG, "turn after ${System.currentTimeMillis() - began} ms: $turn")
        return turn
    }

    companion object {
        const val TAG = "HermesSmoke"
    }
}
