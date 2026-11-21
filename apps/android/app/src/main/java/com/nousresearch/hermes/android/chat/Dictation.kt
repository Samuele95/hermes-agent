package com.nousresearch.hermes.android.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent

// Dictation through the phone's own speech recognizer (chat-30, D48, F13): speech recognition is a platform service
// reached through an activity callback, like share and copy, so the composer only receives text, and the app holds no
// microphone permission — the recognizer's activity does, and the audio goes to the phone's speech service (HEX).

/** `Activity.RESULT_OK`, inlined so the reading below touches no Android object and runs on the JVM. */
private const val RESULT_OK = -1

/**
 * What the recognizer heard: its first result when it answered OK with words, else nothing (§3.1; EJ-55: a cancel, a
 * no-match or an error is an absent result, not an error). A later result never stands in for a blank first one.
 */
fun heardOf(resultCode: Int, results: List<String>?): String? =
    results?.firstOrNull()?.trim()?.takeIf { resultCode == RESULT_OK && it.isNotEmpty() }

/**
 * The draft after [heard] (Desktop's insertText, use-composer-draft.ts:467-471): a blank draft takes the words alone
 * (D5); otherwise they follow on a new line, with no second break when the draft already ends with one. Nothing heard
 * leaves the draft as it was; the user's own text is never trimmed or rewritten.
 */
fun dictated(draft: String, heard: String?): String {
    val words = heard?.trim().orEmpty()
    return when {
        words.isEmpty() -> draft
        draft.isBlank() -> words
        draft.endsWith('\n') -> draft + words
        else -> draft + "\n" + words
    }
}

/** The system speech recognizer's intent (bet 15): free-form speech, in the phone's own language. */
fun dictationIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

/** Whether an activity on the phone answers [intent] (§3.3); visible on API 30+ only through the manifest's <queries> entry. */
fun recognizerAnswers(packages: PackageManager, intent: Intent): Boolean =
    @Suppress("DEPRECATION") packages.queryIntentActivities(intent, 0).isNotEmpty()
