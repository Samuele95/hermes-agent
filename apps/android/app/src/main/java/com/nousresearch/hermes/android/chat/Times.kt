package com.nousresearch.hermes.android.chat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

/**
 * The moment a time is shown at ([now], epoch ms, the screen's clock at the tap) and how the phone writes its clock
 * ([h24]: the 24-hour clock is on; [zone]: the phone's).
 */
data class ShownAt(val now: Long, val h24: Boolean, val zone: TimeZone = TimeZone.getDefault())

/**
 * When a message landed, as the chat words it on a tap (Desktop's `formatMessageTimestamp`, chat-15). The words are
 * English whatever the phone's language, as every word of the app is (D3); the screen words [Yesterday].
 */
sealed interface MessageTime {
    /** Today: the clock alone ("3:27 PM", "15:27"). */
    data class Today(val clock: String) : MessageTime

    /** The calendar day before; the screen words it "Yesterday, [clock]". */
    data class Yesterday(val clock: String) : MessageTime

    /**
     * Any other day, earlier or later (a phone clock that was behind): "Sep 28, 3:27 PM", with the year when it is
     * another calendar year's ("Sep 28, 2025, 3:27 PM", D4).
     */
    data class Day(val text: String) : MessageTime

    companion object {
        /** [at] (epoch ms) as read at [shown]: by calendar days in its zone, never by 24-hour spans. Total: never throws. */
        fun of(at: Long, shown: ShownAt): MessageTime {
            val clock = format(if (shown.h24) "HH:mm" else "h:mm a", shown.zone, at)
            // Days, rounded: a day across a clock change is 23 or 25 hours long.
            val days = ((dayStart(shown.now, shown.zone) - dayStart(at, shown.zone)) / 86_400_000.0).roundToLong()
            return when (days) {
                0L -> Today(clock)
                1L -> Yesterday(clock)
                else -> {
                    val sameYear = year(at, shown.zone) == year(shown.now, shown.zone)
                    Day(format(if (sameYear) "MMM d" else "MMM d, yyyy", shown.zone, at) + ", " + clock)
                }
            }
        }

        private fun format(pattern: String, zone: TimeZone, at: Long): String =
            SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = zone }.format(at)

        private fun calendar(millis: Long, zone: TimeZone): Calendar =
            Calendar.getInstance(zone, Locale.ENGLISH).apply { timeInMillis = millis }

        private fun dayStart(millis: Long, zone: TimeZone): Long = calendar(millis, zone).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        private fun year(millis: Long, zone: TimeZone): Int = calendar(millis, zone).get(Calendar.YEAR)
    }
}

/**
 * A measured turn's length as Desktop's ⏱ writes it (`formatElapsed` over whole seconds, at least one): "38s", "1:04",
 * "62:05"; minutes are never folded into hours. Ties round up, as JS `Math.round` does for a positive length.
 */
fun turnLength(tookMs: Long): String {
    val s = maxOf(1L, (tookMs / 1000.0).roundToLong())
    return if (s < 60) "${s}s" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}
