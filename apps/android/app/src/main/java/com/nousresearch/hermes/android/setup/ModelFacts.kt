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

/**
 * One subscription usage line in the state the core gave it (Desktop's `PoolUsageTip`): a closed set the screen renders
 * case by case, so a new state fails compilation until it has words and never falls to a percentage (EJ-34, GoF State).
 * [label]: the window's name, or the pool account's.
 */
sealed interface UsageWindow {
    val label: String

    /** A live window, [usedPercent] of it spent (0–100), rolling over at the core's ISO [resetsAt] (null: not said). */
    data class Known(override val label: String, val usedPercent: Int, val resetsAt: String?) : UsageWindow

    /** A pool account the core holds `limited` (spent) until its ISO [resetsAt]; null: the core does not know when. */
    data class Limited(override val label: String, val resetsAt: String?) : UsageWindow

    /** A pool account whose credential the core holds dead (`unavailable`): signing in again brings it back. */
    data class Unavailable(override val label: String) : UsageWindow

    /** A pool account whose usage the core does not know: `unknown`, a stale `ready`, or a reset already past. */
    data class Unknown(override val label: String) : UsageWindow
}

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

/** Whether the core's ISO time [at] is still ahead of [nowMs]; absent or unreadable stands (the core has not said it is over). */
private fun liveAt(nowMs: Long): (String?) -> Boolean = { at -> at == null || (epochMs(at)?.let { it > nowMs } ?: true) }

/**
 * The facts in [row], as of the wall clock [nowMs]. Total: a field it cannot read is absent.
 * - A blank warning is none.
 * - An `account` limit stands while its reset is absent, unreadable (kept as the core wrote it) or ahead; one
 *   already past is no limit (a stale catalog clears itself, as Desktop's `providerLimit`).
 * - A model's price keeps the core's strings, `""` (unknown) read as none; it is locked when the core lists it
 *   unavailable, and keeps its own `models` limit while that is not past.
 * - A credential pool (`usage.accounts`) is one line per account, never one average (the contract's rule), in the core's
 *   state: limited (with its reset, or none), unavailable, ready (its tightest live window) or not known (`unknown`, a
 *   ready one with no live window, a state the contract does not name, or any account whose reset is past). Without
 *   accounts, the live windows show tightest first; a rolled-over one is dropped.
 */
fun ProviderFacts.Companion.of(row: ModelOptionProvider, nowMs: Long): ProviderFacts {
    val live = liveAt(nowMs)
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
            .map { UsageWindow.Known(it.label, it.percent, it.resetsAt) }
    }
    return ProviderFacts(warning = row.warning?.trim()?.takeIf { it.isNotEmpty() }, limited = limited,
                         limitedUntil = limit?.resetsAt.takeIf { limited }, usage = usage, models = models)
}

/**
 * These facts as of a later wall clock [nowMs], by [of]'s own rule, so an open picker never shows a reset already past
 * as upcoming (improve #8; Desktop judges `providerLimit` / `poolUsage` against `Date.now()` at each render):
 * - an account limit whose time has passed is no limit;
 * - a model's passed reset is dropped (a model left with no fact drops out, as in [of]);
 * - a usage line whose reset has passed reads not known: a passed reset is not fresh quota, and these lines do not
 *   say whether they are a pool's accounts (whose line must stay) or the provider's windows.
 * Absent or unreadable times stand, as in [of]. A new value: these facts are never patched (EJ-17).
 */
fun ProviderFacts.asOf(nowMs: Long): ProviderFacts {
    val live = liveAt(nowMs)
    val over = limited && !live(limitedUntil)
    return ProviderFacts(
        warning = warning, limited = limited && !over, limitedUntil = limitedUntil.takeIf { !over },
        usage = usage.map { line ->
            when (line) {
                is UsageWindow.Known -> if (live(line.resetsAt)) line else UsageWindow.Unknown(line.label)
                is UsageWindow.Limited -> if (live(line.resetsAt)) line else UsageWindow.Unknown(line.label)
                is UsageWindow.Unavailable, is UsageWindow.Unknown -> line
            }
        },
        models = models.mapValues { (_, facts) -> facts.copy(resetsAt = facts.resetsAt?.takeIf(live)) }
            .filterValues { it != ModelFacts() })
}

private val ProviderUsageWindow.percent: Int get() = usedPercent.roundToInt().coerceIn(0, 100)

/** One pool account's line (Desktop's `PoolUsageTip`): its label, else "Account N" by its place in the core's order. */
private fun ProviderUsageAccount.line(index: Int, live: (String?) -> Boolean): UsageWindow {
    val name = label.ifEmpty { "Account ${index + 1}" }
    val tightest = windows.orEmpty().filter { live(it.resetsAt) }.maxByOrNull { it.usedPercent }
    return when {
        !live(resetsAt) -> UsageWindow.Unknown(name)  // a passed reset is not fresh quota (Desktop's poolUsage)
        state == "unavailable" -> UsageWindow.Unavailable(name)
        state == "limited" -> UsageWindow.Limited(name, resetsAt)
        state == "ready" && tightest != null -> UsageWindow.Known(name, tightest.percent, tightest.resetsAt)
        else -> UsageWindow.Unknown(name)  // unknown, a stale ready, or a state the contract does not name
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
