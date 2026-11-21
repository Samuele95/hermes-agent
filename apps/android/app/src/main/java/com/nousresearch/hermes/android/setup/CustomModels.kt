package com.nousresearch.hermes.android.setup

import android.content.SharedPreferences

/**
 * Model ids the user typed for a provider, kept on this phone once the core took one (DC-M9): the catalog is a hint
 * list, the core decides. Ids only, never a secret.
 */
interface CustomModels {
    /** The ids kept for the provider [slug], in the order they were added. */
    operator fun get(slug: String): List<String>

    /** Keeps [model] for the provider [slug] after the ones it holds; an id already held is ignored. */
    fun add(slug: String, model: String)

    /** Kept for this process only. */
    class InMemory : CustomModels {
        private val ids = mutableMapOf<String, List<String>>()
        override fun get(slug: String) = ids[slug].orEmpty()
        override fun add(slug: String, model: String) { ids[slug] = (get(slug) + model).distinct() }
    }
}

/** [CustomModels] in the app's preferences: one key per provider slug, its ids joined by a newline (an id holds no whitespace). */
class PrefsCustomModels(private val prefs: SharedPreferences) : CustomModels {
    override fun get(slug: String): List<String> = prefs.getString(slug, null)?.split("\n")?.filter { it.isNotEmpty() }.orEmpty()
    override fun add(slug: String, model: String) = prefs.edit().putString(slug, (get(slug) + model).distinct().joinToString("\n")).apply()
}

/**
 * The model id in what the user typed, or null: trimmed, not empty, no whitespace. Desktop's `customModelSlug`
 * (`custom-models.ts:74-78`): beyond that, "the gateway's switch result is the only authority on validity".
 */
fun customModelId(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
