package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A message's time and a turn's length, as the chat words them on a tap and under an answer (chat-15). */
class TimesTest {
    private val zone = TimeZone.getTimeZone("Europe/Rome")

    /** Epoch ms of a local time in [zone]; months are 1-based here. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    private fun shown(now: Long, h24: Boolean = false) = ShownAt(now, h24, zone)

    @Test
    fun todayIsTheClockAlone() {
        val now = at(2026, 11, 16, 18, 0)

        assertEquals(MessageTime.Today("3:27 PM"), MessageTime.of(at(2026, 11, 16, 15, 27), shown(now)))
        assertEquals(MessageTime.Today("15:27"), MessageTime.of(at(2026, 11, 16, 15, 27), shown(now, h24 = true)))
        assertEquals(MessageTime.Today("9:05 AM"), MessageTime.of(at(2026, 11, 16, 9, 5), shown(now)))
        assertEquals(MessageTime.Today("09:05"), MessageTime.of(at(2026, 11, 16, 9, 5), shown(now, h24 = true)))
    }

    @Test
    fun theDayBeforeIsYesterdayByTheCalendarNotBy24Hours() {
        val pastMidnight = at(2026, 11, 16, 0, 10)

        assertEquals(MessageTime.Yesterday("11:50 PM"), MessageTime.of(at(2026, 11, 15, 23, 50), shown(pastMidnight)))
        assertEquals(MessageTime.Day("Nov 14, 11:00 PM"), MessageTime.of(at(2026, 11, 14, 23, 0), shown(pastMidnight)))
        assertEquals(MessageTime.Today("12:05 AM"), MessageTime.of(at(2026, 11, 16, 0, 5), shown(at(2026, 11, 16, 23, 59))))
    }

    @Test
    fun aDayAcrossTheClockChangeIsStillYesterday() {
        // Rome moves to summer time on 29 March 2026: that day is 23 hours long.
        assertEquals(MessageTime.Yesterday("3:27 PM"), MessageTime.of(at(2026, 3, 29, 15, 27), shown(at(2026, 3, 30, 9, 0))))
    }

    @Test
    fun anotherDayIsItsDateAndAnotherYearSaysWhich() {
        val now = at(2026, 11, 16, 10, 0)

        assertEquals(MessageTime.Day("Sep 28, 3:27 PM"), MessageTime.of(at(2026, 9, 28, 15, 27), shown(now)))
        assertEquals(MessageTime.Day("Sep 28, 15:27"), MessageTime.of(at(2026, 9, 28, 15, 27), shown(now, h24 = true)))
        assertEquals(MessageTime.Day("Sep 28, 2025, 3:27 PM"), MessageTime.of(at(2025, 9, 28, 15, 27), shown(now)))
        // A later day: the phone's clock was behind when the message came.
        assertEquals(MessageTime.Day("Nov 18, 3:27 PM"), MessageTime.of(at(2026, 11, 18, 15, 27), shown(now)))
    }

    @Test
    fun theWordsStayEnglishWhateverThePhonesLanguage() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.ITALY)
        try {
            val now = at(2026, 11, 16, 10, 0)
            assertEquals(MessageTime.Day("Sep 28, 3:27 PM"), MessageTime.of(at(2026, 9, 28, 15, 27), shown(now)))
            assertEquals(MessageTime.Today("3:27 PM"), MessageTime.of(at(2026, 11, 16, 15, 27), shown(at(2026, 11, 16, 18, 0))))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun aTurnReadsInWholeSecondsThenMinutesAndSeconds() {
        assertEquals("1s", turnLength(0))
        assertEquals("1s", turnLength(400))
        assertEquals("1s", turnLength(1_499))
        assertEquals("2s", turnLength(1_500))
        assertEquals("38s", turnLength(38_000))
        assertEquals("59s", turnLength(59_499))
        assertEquals("1:00", turnLength(59_500))
        assertEquals("1:04", turnLength(64_000))
        assertEquals("62:05", turnLength(3_725_000))
    }

    private fun event(type: String, payload: String? = null): CoreEvent =
        CoreEvent(type, "s1", payload?.let { Json.parseToJsonElement(it) })

    @Test
    fun aLiveTurnIsTimedByTheChatsClockAndAResumedOneIsNot() {
        var clockMs = at(2026, 11, 16, 15, 27)
        val started = Chat().sent("hi", at = clockMs).reduce(event("message.start")) { clockMs }
        clockMs += 64_000
        val done = started.reduce(event("message.complete", """{"text":"Hello","status":"complete"}""")) { clockMs }
        val reply = done.entries.last() as Entry.Reply

        assertEquals("1:04", turnLength(reply.tookMs!!))
        assertEquals(MessageTime.Today("3:28 PM"), MessageTime.of(reply.at!!, ShownAt(clockMs + 60_000, false, zone)))

        // A turn already running when the app attached: its start was never seen, so it is not timed.
        val resumed = Chat(streaming = "").reduce(event("message.complete", """{"text":"Hi","status":"complete"}""")) { clockMs }
        assertNull((resumed.entries.last() as Entry.Reply).tookMs)
    }
}
