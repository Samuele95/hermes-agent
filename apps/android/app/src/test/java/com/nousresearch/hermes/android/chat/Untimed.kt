package com.nousresearch.hermes.android.chat

/**
 * This entry with no time: a prompt's or reply's [Entry.User.at], [Entry.Reply.at] and [Entry.Reply.tookMs] null, a
 * notice's [Entry.Notice.tookMs] null (the length of a turn a bare `error` ended), any other entry as it is. For tests
 * about other rules: the times the clock gave are pinned by their own tests (P3).
 */
fun Entry.untimed(): Entry = when (this) {
    is Entry.User -> copy(at = null)
    is Entry.Reply -> copy(at = null, tookMs = null)
    is Entry.Notice -> copy(tookMs = null)
    else -> this
}

/** These entries with no time ([Entry.untimed]), for tests about other rules (P3). */
fun List<Entry>.untimed(): List<Entry> = map { it.untimed() }

/** This chat with no time in its entries and no turn start, for tests about other rules (P3). */
fun Chat.untimed(): Chat = copy(entries = entries.untimed(), turnStartedAt = null)
