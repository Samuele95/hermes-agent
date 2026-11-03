package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.Usage
import java.util.Locale

/** How full the session's context window is, as the core last measured it; [estimated] when the figure is the core's estimate. */
data class ContextMeter(val used: Long, val max: Long, val percent: Int, val estimated: Boolean) {
    /** Near the window's end (the blueprint's §8 default): time to start anew or compress. */
    val warns: Boolean get() = percent >= 80

    companion object {
        /** The core's usage as a meter; null when it reports no context window (nothing measured yet, EJ-1). */
        fun of(usage: Usage): ContextMeter? {
            val max = usage.contextMax?.takeIf { it > 0 } ?: return null
            val used = (usage.contextUsed ?: 0L).coerceIn(0L, max)
            val percent = (usage.contextPercent ?: ((used * 100 + max / 2) / max)).coerceIn(0L, 100L).toInt()
            return ContextMeter(used, max, percent, usage.contextEstimated == true)
        }
    }
}

/** A token count the way the desktop prints one: 999, 1k, 12.3k, 1.5M (apps/shared/src/format.ts compactNumber). */
fun compactTokens(count: Long): String {
    fun scaled(value: Double, suffix: String) = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0") + suffix
    return when {
        count <= 0 -> "0"
        count >= 999_950 -> scaled(count / 1e6, "M")
        count >= 1_000 -> scaled(count / 1e3, "k")
        else -> count.toString()
    }
}
