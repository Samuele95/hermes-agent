package com.nousresearch.hermes.android

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.dictationIntent
import com.nousresearch.hermes.android.chat.recognizerAnswers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dictation on the real app and its real core (chat-30, D48, F13; §8's device check): the app asks for no microphone
 * permission, the phone's own speech recognizer answers the app's intent, and the real composer shows the mic. No model
 * is needed: nothing is sent and nothing is spoken. The real recognizer is never launched, and the mic is never tapped
 * (D7): it would open another app's screen and record the room.
 */
@RunWith(AndroidJUnit4::class)
class DictationDeviceTest {
    companion object {
        private const val TAG = "Dictation"
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun openTheChat() {
        compose.waitUntil(60_000) {
            listOf("draft", "setup").any { compose.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
        }
        if (compose.onAllNodes(hasTestTag("setup")).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("setup-close").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("draft")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theAppAsksNoMicrophoneAndARecognizerAnswersHere() {
        val packages = context.packageManager
        val asked = packages.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        Log.i(TAG, "requested permissions: $asked")
        assertFalse("the recognizer's activity holds the microphone, not the app (D48)", Manifest.permission.RECORD_AUDIO in asked)
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))

        @Suppress("DEPRECATION") val answering = packages.queryIntentActivities(dictationIntent(), 0).map { it.activityInfo.packageName }
        Log.i(TAG, "the recognizer intent is answered by: $answering")
        assertTrue("an activity answers RECOGNIZE_SPEECH (the manifest's <queries>)", recognizerAnswers(packages, dictationIntent()))
    }

    @Test
    fun theRealChatShowsTheMic() {
        compose.onNodeWithTag("dictate").assertIsDisplayed().assertContentDescriptionEquals("Voice dictation")  // never clicked (D7)
    }
}
