package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.SessionListRow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The sessions list's headings, as the desktop sidebar's: by when a conversation began, and messaging by where it came from. */
class SessionGroupsTest {
    private val zone = TimeZone.getTimeZone("Europe/Rome")

    /** Seconds since the epoch of a local time in [zone]; months are 1-based here. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Double =
        Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, hour, 0) }.timeInMillis / 1000.0

    private val now = (at(2026, 10, 21, 18) * 1000).toLong()  // a Wednesday

    private fun row(id: String, startedAt: Double, source: String = "android") =
        SessionListRow(id = id, startedAt = startedAt, messageCount = 2, source = source)

    private fun groups(rows: List<SessionListRow>, locale: Locale = Locale.UK) =
        groupSessions(rows, now, zone, locale).map { group -> group.heading to group.sessions.map { it.id } }

    @Test
    fun conversationsAreHeadedByWhenTheyBegan() {
        val rows = listOf(row("today", at(2026, 10, 21, 9)), row("yesterday", at(2026, 10, 20, 23)),
                          row("monday", at(2026, 10, 19)), row("last-thu", at(2026, 10, 15)), row("oct-2", at(2026, 10, 2)),
                          row("sep", at(2026, 9, 5)), row("old", at(2025, 12, 1)), row("undated", 0.0))

        assertEquals(listOf(SessionHeading.Today to listOf("today"), SessionHeading.Yesterday to listOf("yesterday"),
                            SessionHeading.ThisWeek to listOf("monday"), SessionHeading.LastWeek to listOf("last-thu"),
                            SessionHeading.ThisMonth to listOf("oct-2"), SessionHeading.Month(2026, 9) to listOf("sep"),
                            SessionHeading.Month(2025, 12) to listOf("old"), SessionHeading.Older to listOf("undated")),
                     groups(rows))
    }

    @Test
    fun theWeekStartsWhereTheLocaleSaysItDoes() {
        // Sunday 18 October: this week where weeks start on Sunday, last week where they start on Monday.
        val sunday = listOf(row("sunday", at(2026, 10, 18)))

        assertEquals(listOf(SessionHeading.ThisWeek to listOf("sunday")), groups(sunday, Locale.US))
        assertEquals(listOf(SessionHeading.LastWeek to listOf("sunday")), groups(sunday, Locale.UK))
    }

    @Test
    fun messagingAndScheduledSessionsAreGroupedByWhereTheyCameFrom() {
        val rows = listOf(row("tg-1", at(2026, 10, 21, 17), "telegram"), row("app", at(2026, 10, 21, 16)),
                          row("cron-1", at(2026, 10, 21, 15), "cron"), row("tg-2", at(2026, 10, 1), "telegram"),
                          row("tui", at(2026, 10, 21, 10), "tui"), row("blank", at(2026, 10, 21, 9), ""))

        assertEquals(listOf(SessionHeading.Today to listOf("app", "tui", "blank"),
                            SessionHeading.Source("telegram") to listOf("tg-1", "tg-2"),
                            SessionHeading.Source("cron") to listOf("cron-1")),
                     groups(rows))
    }

    @Test
    fun aSessionsAgeIsShortLikeTheDesktops() {
        val seconds = now / 1000.0
        assertEquals(listOf("now", "5m", "3h", "2d", ""),
                     listOf(seconds - 30, seconds - 5 * 60, seconds - 3 * 3600, seconds - 2 * 86400 - 60, 0.0).map { sessionAge(it, now) })
    }
}
