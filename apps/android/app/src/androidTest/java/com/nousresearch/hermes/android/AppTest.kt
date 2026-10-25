package com.nousresearch.hermes.android

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole loop: the screen sends through the chat, the bridge and the embedded core, and an answer comes back. */
@RunWith(AndroidJUnit4::class)
class AppTest {
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

    @Test
    fun aMessageSentFromTheScreenIsAnsweredByTheEmbeddedCore() {
        // A fresh install opens on provider setup; "Later" leads to the chat.
        compose.waitUntil(60_000) {
            listOf("draft", "setup").any { compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodes(hasTestTag("setup")).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("setup-close").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("draft")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("draft").performTextInput("hello")
        compose.onNodeWithTag("send").performClick()

        // No provider is configured on a fresh install, so the answer may well be an error: what is
        // pinned here is that the core answers the screen at all.
        compose.waitUntil(120_000) {
            compose.onAllNodes(hasTestTag("entry-reply")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("entry-notice")).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
