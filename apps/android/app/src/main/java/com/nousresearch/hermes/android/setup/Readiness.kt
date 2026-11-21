package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.protocol.SetupRuntimeCheckResult

/**
 * Whether the provider the core is set up for will answer, as its last `setup.runtime_check` said (batch-3 F6). A
 * closed set the model pill renders case by case, so its `when` is exhaustive and a new state cannot fall to a
 * default "ready" (EJ-34). [Unknown] is "cannot tell": the check itself failed or passed its budget, never a "no"
 * (EJ-55, DC-M11). Answered per check and never stored (§2.5).
 */
sealed interface Readiness {
    /** A check is on its way; the first answer has not come yet. */
    data object Checking : Readiness

    /** The core resolved a runtime for [provider] and [model] (either absent when the core did not name it). */
    data class Ready(val provider: String?, val model: String?) : Readiness

    /** No provider is set up; [words] are the core's, else [NOT_READY]. */
    data class NeedsSetup(val words: String) : Readiness

    /** A provider is set up but cannot run (a revoked key, a credential the config names but nothing holds), in the core's [words]. */
    data class Unavailable(val words: String) : Readiness

    /** The check did not finish (5097: its budget passed; a refusal; an answer the app cannot read): cannot tell. */
    data object Unknown : Readiness

    companion object {
        /** Desktop's sentence when the core gives no words (`runtime-readiness.ts:61`). */
        const val NOT_READY = "Add a provider credential before sending your first message."
    }
}

/**
 * The core's [check], read once: `ok` is [Readiness.Ready] (a blank name is none) and [configured] is not read; a
 * refusal is in the core's words, else [Readiness.NOT_READY], and is [Readiness.Unavailable] when `setup.status` says a
 * provider is [configured], else [Readiness.NeedsSetup] (Desktop's `checksDisagree`). Total; never [Readiness.Unknown]
 * or [Readiness.Checking]: those are not the core's answers.
 */
fun Readiness.Companion.of(check: SetupRuntimeCheckResult, configured: Boolean): Readiness {
    if (check.ok) return Readiness.Ready(check.provider?.takeIf { it.isNotBlank() }, check.model?.takeIf { it.isNotBlank() })
    val words = check.error?.trim()?.takeIf { it.isNotEmpty() } ?: Readiness.NOT_READY
    return if (configured) Readiness.Unavailable(words) else Readiness.NeedsSetup(words)
}
