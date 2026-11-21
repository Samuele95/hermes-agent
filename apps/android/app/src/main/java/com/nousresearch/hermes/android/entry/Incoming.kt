package com.nousresearch.hermes.android.entry

/**
 * The action the "New chat" launcher shortcut and the quick-settings tile start the app with: a new chat, no words
 * (D2). Namespace-based, so it is the same in both build types.
 */
const val NEW_CHAT = "com.nousresearch.hermes.android.NEW_CHAT"

/**
 * Text another app shared with Hermes: offered as a draft in the current chat, never a prompt (bet 8, DC-M7). Another
 * app's words are not the user's prompt, and the agent can run tools, so the user reads them before anything is sent.
 * [text] is trimmed and at most [MAX_TEXT] chars, [cut] when the share was longer (D4); [subject] is one line, at
 * most [MAX_SUBJECT] chars (Chrome's page title beside its link).
 */
data class IncomingText(val text: String, val subject: String? = null, val cut: Boolean = false) {
    /** The words the composer takes: the subject on the first line, unless the text already holds it (D5). */
    val draft: String get() = if (subject == null || text.contains(subject)) text else subject + "\n" + text

    companion object {
        /** The cap on shared text, concrete by intent (§5). */
        const val MAX_TEXT = 20_000
        const val MAX_SUBJECT = 200
        /** `Intent.ACTION_SEND`, spelt out so the rule runs on the JVM without Android (HEX). */
        private const val SEND = "android.intent.action.SEND"

        /**
         * The offer an intent's [action], [type], [text] and [subject] make, or null for anything that is not shareable
         * text: only a SEND of `text/plain` with words in it (no SEND_MULTIPLE, VIEW, image or markup; an untyped
         * intent is none). The words are trimmed and capped at [MAX_TEXT], never splitting a surrogate pair (EJ-1).
         */
        fun of(action: String?, type: String?, text: CharSequence?, subject: String?): IncomingText? {
            if (action != SEND) return null
            if (!type?.substringBefore(';')?.trim().equals("text/plain", ignoreCase = true)) return null
            val words = text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            var end = minOf(words.length, MAX_TEXT)
            if (end < words.length && words[end - 1].isHighSurrogate()) end -= 1
            return IncomingText(words.substring(0, end).trimEnd(), lineOf(subject), cut = end < words.length)
        }

        /** [subject] as one short line: whitespace runs made one space, trimmed, cut at [MAX_SUBJECT]; empty is none. */
        private fun lineOf(subject: String?): String? =
            subject?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_SUBJECT)?.takeIf { it.isNotEmpty() }
    }
}
