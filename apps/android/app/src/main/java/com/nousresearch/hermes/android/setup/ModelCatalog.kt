package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.protocol.ConfigSetResult
import com.nousresearch.hermes.bridge.protocol.ModelOptionProvider
import com.nousresearch.hermes.bridge.protocol.ModelOptionsResult
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive

/**
 * One model the chat can switch to (models-08): [provider] the core's slug (what `--provider` takes), [providerName]
 * its name, [facts] what the core says about it (M5), [current] the chat runs it now, [custom] an id the user added
 * that the provider's list does not hold (DC-M9).
 */
data class ModelRow(val provider: String, val providerName: String, val model: String, val facts: ModelFacts = ModelFacts(),
                    val current: Boolean = false, val custom: Boolean = false) {
    /** The row's identity in the list (Desktop's row value): two providers may list the same id. */
    val key: String get() = "$provider:$model"
}

/**
 * What [ModelCatalog.of] reads the core's list against (D3): the wall clock [nowMs] its facts are judged at, and the
 * ids the user added per provider slug ([custom]; a slug absent holds none).
 */
data class CatalogAt(val nowMs: Long, val custom: Map<String, List<String>> = emptyMap())

/**
 * One list across every provider Hermes is signed in to, the chat's own provider first, the rest in the core's order
 * (bet 5): built once from one `model.options` answer by [of] and never patched (EJ-17). The only value the chat and
 * Setup share (§2.1); a refresh replaces it.
 */
data class ModelCatalog(val rows: List<ModelRow>) {
    /**
     * The rows whose model id, provider name or slug holds [query], case-folded, in the list's order (D8: the core's
     * curated order is never re-ranked). A blank query is every row.
     */
    fun filtered(query: String): List<ModelRow> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return rows
        return rows.filter { row -> listOf(row.model, row.providerName, row.provider).any { q in it.lowercase(Locale.ROOT) } }
    }

    /**
     * The id typed in [query] offered once per provider, in the list's order (D4, Desktop's `customModelCandidate`); none
     * when the text is no model id ([customModelId]) or a provider already lists it (the search shows that row, a locked
     * one included).
     */
    fun typed(query: String): List<ModelRow> {
        val id = customModelId(query) ?: return emptyList()
        if (rows.any { it.model.equals(id, ignoreCase = true) }) return emptyList()
        return rows.distinctBy { it.provider }.map { ModelRow(it.provider, it.providerName, id, custom = true) }
    }

    companion object
}

/**
 * The core's [options] as one list, its facts as of [at]'s clock. Total.
 * - The current pair is the core's own (`model` / `provider`, the session's live pair when asked with its id): a
 *   provider is the current one when `provider` is its slug, its name or an alias (Desktop's `catalogProviderMatches`),
 *   or, with no provider named, when the core marks it `is_current`.
 * - Only signed-in providers (`authenticated`), the current one first, the rest in the core's order.
 * - Per provider its listed models, then the ids the user added that it does not list, each once; a provider left with
 *   no model is dropped.
 */
fun ModelCatalog.Companion.of(options: ModelOptionsResult, at: CatalogAt): ModelCatalog {
    val model = options.model.trim()
    val provider = options.provider.trim()
    fun isCurrent(row: ModelOptionProvider) =
        if (provider.isEmpty()) row.isCurrent == true
        else provider == row.slug || provider == row.name || provider in row.aliases.orEmpty()
    val rows = options.providers.filter { it.authenticated == true }.sortedByDescending(::isCurrent).flatMap { row ->
        val current = isCurrent(row)
        val facts = ProviderFacts.of(row, at.nowMs)
        val listed = row.models.orEmpty().distinct()
        val added = at.custom[row.slug].orEmpty().filterNot { it in listed }.distinct()
        listed.map { id -> ModelRow(row.slug, row.name, id, facts.models[id] ?: ModelFacts(), current = current && id == model) } +
            added.map { id -> ModelRow(row.slug, row.name, id, current = current && id == model, custom = true) }
    }
    return ModelCatalog(rows)
}

/**
 * What the core made of a pick (`config.set`'s answer, read once: a screen never reads [ConfigSetResult], §5).
 * [Failed] is made by the switch from a refusal.
 */
sealed interface Switched {
    /** The chat runs [model] now. */
    data class Now(val model: String) : Switched

    /** The core keeps [model] for the next turn (`deferred`: a reply is running). */
    data class Next(val model: String) : Switched

    /** The core takes [model] only once the user confirms, with its own [message] (`confirm_required`; may be blank). */
    data class Confirm(val model: String, val message: String) : Switched

    /** The core refused the pick, in its [words] (may be blank). */
    data class Failed(val words: String) : Switched

    companion object
}

/**
 * The core's [result] for the pick of [asked]. Total: the model is the core's normalised `value` when that is a
 * non-blank string, else [asked].
 */
fun Switched.Companion.of(result: ConfigSetResult, asked: String): Switched {
    val model = (result.value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: asked
    return when {
        result.confirmRequired == true -> Switched.Confirm(asked, result.confirmMessage?.trim().orEmpty())
        result.deferred == true -> Switched.Next(model)
        else -> Switched.Now(model)
    }
}
