package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.jobs.wallTime
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
import com.nousresearch.hermes.bridge.protocol.ProviderUsageAccount
import com.nousresearch.hermes.bridge.protocol.ProviderUsageWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * What the core says about one model: its price as the core's own $/Mtok strings, never parsed (EJ-60, DC-M10: the
 * core already formats exact money, `models_pricing.py:112-127`, and the app does no arithmetic on it); [free] = the
 * core's own flag; [locked] = in `unavailable_models` (Desktop's "Pro"); [resetsAt] = the core's ISO time its own rate
 * limit ends.
 */
data class ModelFacts(val input: String? = null, val output: String? = null, val free: Boolean = false,
                      val locked: Boolean = false, val resetsAt: String? = null)

/** One subscription usage line: [usedPercent] null means the core does not know; [resetsAt] the core's ISO time. */
data class UsageWindow(val label: String, val usedPercent: Int?, val resetsAt: String?)

/**
 * What the core's list says about a provider, built once from one `model.options` row and never patched (EJ-17): the
 * picker, a confirm and a Back all render this same value, and a refresh replaces it. [warning]: the core's words;
 * [limited]: the whole account hit its rate limit, until [limitedUntil] (null when the core does not know); [usage]:
 * one line per window, or per account of a credential pool; [models]: only the models with a fact.
 */
data class ProviderFacts(val warning: String? = null, val limited: Boolean = false, val limitedUntil: String? = null,
                         val usage: List<UsageWindow> = emptyList(), val models: Map<String, ModelFacts> = emptyMap()) {
    companion object {
        val NONE = ProviderFacts()
    }
}

/**
 * The facts in [row], as of the wall clock [nowMs]. Total: a field it cannot read is absent.
 * - A blank warning is none.
 * - An `account` limit stands while its reset is absent, unreadable (kept as the core wrote it) or ahead; one
 *   already past is no limit (a stale catalog clears itself, as Desktop's `providerLimit`).
 * - A model's price keeps the core's strings, `""` (unknown) read as none; it is locked when the core lists it
 *   unavailable, and keeps its own `models` limit while that is not past.
 * - A credential pool (`usage.accounts`) is one line per account, never one average (the contract's rule): a limited
 *   account is spent until its reset, a ready one is its tightest live window, and any other is not known. Without
 *   accounts, the live windows show tightest first; a rolled-over one is dropped.
 */
fun ProviderFacts.Companion.of(row: ModelOptionProvider, nowMs: Long): ProviderFacts {
    val live = { at: String? -> at == null || (epochMs(at)?.let { it > nowMs } ?: true) }
    val limit = row.limit
    val limited = limit?.scope == "account" && live(limit.resetsAt)
    val locked = row.unavailableModels.orEmpty().toSet()
    val resets = limit?.models.orEmpty().takeIf { limit?.scope == "models" }.orEmpty()
    val models = row.models.orEmpty().mapNotNull { id ->
        val price = row.pricing?.get(id)
        val facts = ModelFacts(input = price?.input?.takeIf { it.isNotEmpty() }, output = price?.output?.takeIf { it.isNotEmpty() },
                               free = price?.free == true, locked = id in locked, resetsAt = resets[id]?.takeIf(live))
        (id to facts).takeIf { facts != ModelFacts() }
    }.toMap()
    val accounts = row.usage?.accounts.orEmpty()
    val usage = if (accounts.isNotEmpty()) {
        accounts.mapIndexed { index, account -> account.line(index, live) }
    } else {
        row.usage?.windows.orEmpty().filter { live(it.resetsAt) }.sortedByDescending { it.usedPercent }
            .map { UsageWindow(it.label, it.percent, it.resetsAt) }
    }
    return ProviderFacts(warning = row.warning?.trim()?.takeIf { it.isNotEmpty() }, limited = limited,
                         limitedUntil = limit?.resetsAt.takeIf { limited }, usage = usage, models = models)
}

private val ProviderUsageWindow.percent: Int get() = usedPercent.roundToInt().coerceIn(0, 100)

/** One pool account's line (Desktop's `PoolUsageTip`): its label, else "Account N" by its place in the core's order. */
private fun ProviderUsageAccount.line(index: Int, live: (String?) -> Boolean): UsageWindow {
    val name = label.ifEmpty { "Account ${index + 1}" }
    val tightest = windows.orEmpty().filter { live(it.resetsAt) }.maxByOrNull { it.usedPercent }
    return when {
        state == "limited" && live(resetsAt) -> UsageWindow(name, 100, resetsAt)
        state == "ready" && tightest != null -> UsageWindow(name, tightest.percent, tightest.resetsAt)
        else -> UsageWindow(name, null, null)  // unknown, unavailable, stale, or a limit already over
    }
}

private const val SIX_HOURS_MS = 6 * 3_600_000L

/**
 * The core's ISO time [iso] in the phone's [zone]: the clock alone (`18:05`) on the same day as [nowMs] or less than
 * 6 h ahead (Desktop's `formatReset`), else the day too (`2026-11-17 18:05`, as [runTime] writes); unreadable text as
 * [wallTime] shows it. The core writes these in UTC (`inventory.py:269`), so [wallTime] alone would print its hour.
 */
fun resetTime(iso: String, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val at = epochMs(iso, zone) ?: return wallTime(iso)
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }
    val clock = SimpleDateFormat("HH:mm", Locale.ROOT).apply { timeZone = zone }
    val soon = at - nowMs in 0 until SIX_HOURS_MS
    return if (soon || day.format(Date(at)) == day.format(Date(nowMs))) clock.format(Date(at))
    else "${day.format(Date(at))} ${clock.format(Date(at))}"
}

private val ISO_TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:\d{2})?$""")

/**
 * `yyyy-MM-ddTHH:mm[:ss[.fraction]]` with an optional `Z` or `±HH:MM`, as epoch ms; null when unreadable. With no
 * offset the time is the core's own wall time, the phone's [zone] (the core runs on the phone).
 */
private fun epochMs(iso: String, zone: TimeZone = TimeZone.getDefault()): Long? {
    val parts = ISO_TIME.matchEntire(iso.trim())?.groupValues ?: return null
    val offset = parts[7]
    val at = when (offset) {
        "" -> zone
        "Z" -> TimeZone.getTimeZone("UTC")
        else -> TimeZone.getTimeZone("GMT$offset")
    }
    return try {
        GregorianCalendar(at, Locale.ROOT).apply {
            isLenient = false
            clear()
            set(parts[1].toInt(), parts[2].toInt() - 1, parts[3].toInt(), parts[4].toInt(), parts[5].toInt(),
                parts[6].ifEmpty { "0" }.toInt())
        }.timeInMillis
    } catch (_: IllegalArgumentException) {  // a field out of range: not a time
        null
    }
}
