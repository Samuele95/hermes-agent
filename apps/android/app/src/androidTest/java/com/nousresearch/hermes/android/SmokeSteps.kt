package com.nousresearch.hermes.android

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.Chat
import com.nousresearch.hermes.android.chat.Entry
import java.io.File

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

    /**
     * Saves the screen as `<name>.png` in the app's external files dir (`adb pull` reaches it). What covers the app
     * is closed first: Android 16's compatibility dialog over a debuggable app with 4 KB-aligned libraries, opened at
     * every start (Dv13), which holds the focus as the `android` window, then the keyboard it hands the focus back to.
     */
    fun screenshot(name: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun dump(service: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys $service"))
            .bufferedReader().use { it.readText() }
        repeat(3) {
            val covered = Regex("""mCurrentFocus=Window\{\S+ u0 android\}""").containsMatchIn(dump("window")) ||
                "mInputShown=true" in dump("input_method")
            if (!covered) return@repeat
            ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            SystemClock.sleep(1_000)
        }
        compose.waitForIdle()
        File(app.getExternalFilesDir(null), "$name.png").outputStream().use {
            ui.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    companion object {
        const val TAG = "HermesSmoke"
    }
}
