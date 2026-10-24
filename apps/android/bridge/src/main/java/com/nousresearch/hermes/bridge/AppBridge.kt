package com.nousresearch.hermes.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's calls into its own Python half: `hermes_android.<function>(arg)`, JSON strings in and out. */
fun interface AppBridge {
    suspend fun call(function: String, arg: String): String

    /** The keystore changed: the core re-reads its secrets (variables no longer held are revoked). */
    suspend fun secretsChanged() {
        call("secrets_changed", "")
    }
}

/** [AppBridge] over this process's embedded interpreter, called off the caller's thread. */
object EmbeddedBridge : AppBridge {
    override suspend fun call(function: String, arg: String): String =
        withContext(Dispatchers.IO) { EmbeddedPython.call(function, arg) }
}
