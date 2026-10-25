package com.nousresearch.hermes.android.setup

import android.content.SharedPreferences

/**
 * Which variable the app stored each provider's key under. The core lists a provider that is signed in
 * without its key variable (only unconfigured rows name one), so the app keeps the name itself to know a
 * key it can forget. Names only, never values.
 */
interface KeyNames {
    operator fun get(slug: String): String?

    operator fun set(slug: String, name: String)

    fun remove(slug: String)

    /** Kept for this process only. */
    class InMemory : KeyNames {
        private val names = mutableMapOf<String, String>()
        override fun get(slug: String) = names[slug]
        override fun set(slug: String, name: String) { names[slug] = name }
        override fun remove(slug: String) { names.remove(slug) }
    }
}

/** [KeyNames] in the app's preferences. */
class PrefsKeyNames(private val prefs: SharedPreferences) : KeyNames {
    override fun get(slug: String): String? = prefs.getString(slug, null)
    override fun set(slug: String, name: String) = prefs.edit().putString(slug, name).apply()
    override fun remove(slug: String) = prefs.edit().remove(slug).apply()
}
