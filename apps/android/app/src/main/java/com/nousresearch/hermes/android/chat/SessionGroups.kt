package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.SessionListRow
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * A heading of the sessions list, as the desktop sidebar has them (apps/desktop/src/lib/time.ts `calendarBucket`):
 * conversations by the calendar bucket of the day they began, and sessions that came in through the gateway
 * (a messaging platform, a scheduled job) under [Source], the way the sidebar groups gateway sessions apart.
 */
sealed interface SessionHeading {
    data object Today : SessionHeading
    data object Yesterday : SessionHeading
    data object ThisWeek : SessionHeading
    data object LastWeek : SessionHeading
    data object ThisMonth : SessionHeading
    /** An earlier month; [month] is 1-based. */
    data class Month(val year: Int, val month: Int) : SessionHeading
    /** A session the core gave no start time. */
    data object Older : SessionHeading
    data class Source(val name: String) : SessionHeading
}

data class SessionGroup(val heading: SessionHeading, val sessions: List<SessionListRow>)

/** Sources of the conversations the user had in a chat client; every other source came in through the gateway. */
private val CONVERSATION_SOURCES = setOf(ChatSession.SOURCE, "", "tui", "cli", "desktop")

/**
 * [rows] (newest first, as the core lists them) under their headings: conversations first, each bucket in the
 * order its newest session comes, then one group per gateway source. Order inside a group is kept.
 */
fun groupSessions(rows: List<SessionListRow>, nowMillis: Long, zone: TimeZone = TimeZone.getDefault(),
                  locale: Locale = Locale.getDefault()): List<SessionGroup> {
    val (conversations, gateway) = rows.partition { it.source in CONVERSATION_SOURCES }
    val calendar = Buckets(nowMillis, zone, locale)
    return (conversations.groupBy { calendar.heading(it.startedAt) } + gateway.groupBy { SessionHeading.Source(it.source) })
        .map { (heading, sessions) -> SessionGroup(heading, sessions) }
}

private class Buckets(nowMillis: Long, private val zone: TimeZone, private val locale: Locale) {
    private val today = dayStart(nowMillis)
    private val now = calendar(today)
    private val weekStart = calendar(today).apply {
        while (get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) add(Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis
    private val lastWeekStart = calendar(weekStart).apply { add(Calendar.DAY_OF_MONTH, -7) }.timeInMillis

    private fun calendar(millis: Long): Calendar = Calendar.getInstance(zone, locale).apply { timeInMillis = millis }

    private fun dayStart(millis: Long): Long = calendar(millis).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun heading(startedAt: Double): SessionHeading {
        if (startedAt <= 0) return SessionHeading.Older
        val day = dayStart((startedAt * 1000).toLong())
        // Days, rounded: a day across a clock change is 23 or 25 hours long.
        val daysAgo = ((today - day) / 86_400_000.0).roundToLong()
        val began = calendar(day)
        return when {
            daysAgo <= 0 -> SessionHeading.Today
            daysAgo == 1L -> SessionHeading.Yesterday
            day >= weekStart -> SessionHeading.ThisWeek
            day >= lastWeekStart -> SessionHeading.LastWeek
            began.get(Calendar.YEAR) == now.get(Calendar.YEAR) && began.get(Calendar.MONTH) == now.get(Calendar.MONTH) -> SessionHeading.ThisMonth
            else -> SessionHeading.Month(began.get(Calendar.YEAR), began.get(Calendar.MONTH) + 1)
        }
    }
}

/** How long ago a session began, as short as the sidebar writes it: now, 5m, 3h, 2d; empty when unknown. */
fun sessionAge(startedAt: Double, nowMillis: Long): String {
    if (startedAt <= 0) return ""
    val seconds = (nowMillis / 1000.0 - startedAt).toLong()
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3_600}h"
        else -> "${seconds / 86_400}d"
    }
}
