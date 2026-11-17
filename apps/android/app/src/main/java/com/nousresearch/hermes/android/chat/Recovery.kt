package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.setup.AuthKind
import com.nousresearch.hermes.bridge.protocol.MessageCompletePayload
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Why the chat's turn failed, as the core classified it (`error_surface`, advisory): an immutable copy taken at the
 * completion (§5: no screen reads the wire type). [layer] / [code] / [retryable] are the core's; [provider] / [model]
 * the failing session's, captured when it failed; [resetsAt] epoch seconds when the provider said its limit lifts;
 * [words] the core's `error`; [billingUrl] the page the same completion's billing wall names (M7's http(s) rule, D3).
 */
data class Failure(val layer: String, val code: String, val retryable: Boolean, val provider: String? = null,
                   val model: String? = null, val resetsAt: Double? = null, val words: String = "",
                   val billingUrl: String? = null) {
    companion object
}

/** The fixes a recovery card can offer, each routed to an existing control (EJ-34: the card's switch is exhaustive). */
enum class RecoveryAction { RETRY, CONTINUE, CHOOSE_MODEL, SWITCH_PROVIDER, UPDATE_KEY, SIGN_IN_AGAIN, NEW_CHAT, COMPRESS,
                            EDIT_MESSAGE, OPEN_BILLING }

/** One main fix and at most one second (Desktop main's card, bet 8, DC-P9); [main] null: the error supports none. */
data class RecoveryPlan(val main: RecoveryAction?, val second: RecoveryAction? = null) { companion object }

/** What Continue sends after a cut reply, as Desktop's composer does (`assistant-message.tsx:1086-1090`). */
const val CONTINUE_WORDS = "continue"

/** The layers where another model may answer (Desktop main's `SWITCH_PROVIDER_LAYERS` less auth and billing, D4). */
private val PROVIDER_SIDE = setOf("provider", "endpoint")

/** The codes a shorter conversation fixes. */
private val TOO_LONG = setOf("context_overflow", "payload_too_large")

private const val AUTH = "auth"
private const val BILLING = "billing"
private const val FREE_TIER = "free_tier_"
private const val CONTENT_POLICY = "content_policy"

/**
 * The failure [done] carries: null unless the turn failed (`status: error`) with the core's descriptor (D9: none, no
 * card). Layer and code trimmed, a blank code read as `unknown` (Desktop's); a blank provider or model is none; a reset
 * time kept only when it is a real one (finite, after the epoch); the billing page as M7's wall keeps it. The core's
 * extra keys (`auth_kind`, `api_key_env`, `provider_label`) are outside the contract and never decoded (DC-P10). Total.
 */
fun Failure.Companion.of(done: MessageCompletePayload): Failure? {
    val surface = done.errorSurface?.takeIf { done.status == TurnStatus.ERROR } ?: return null
    return Failure(
        layer = surface.layer.trim(),
        code = surface.code.trim().ifEmpty { "unknown" },
        retryable = surface.retryable,
        provider = surface.provider?.trim()?.ifEmpty { null },
        model = surface.model?.trim()?.ifEmpty { null },
        resetsAt = surface.resetsAt?.takeIf { it.isFinite() && it > 0 },
        words = done.error.orEmpty(),
        billingUrl = done.billing?.let { BillingWall.of(it).url },
    )
}

/**
 * The card's plan for [failure] (bet 8's mapping, Desktop main's `errorCardActions`), a pure total function of the
 * shipped `error_surface` and how the app holds the failing provider's credential ([auth], D1); the first rule that
 * applies wins:
 * - a credential failure: Sign in again (the Nous sign-in) or Update API key (a key the app stored) for a named
 *   provider, else Switch provider (D2); then Retry, as fixing it changes the outcome (D5);
 * - out of credits: the wall's page when the core named one, then Switch provider; else Switch provider alone (D3);
 * - too long: Compress, then a new chat; no such model: Choose a model;
 * - the Nous free tier: Retry when the core says a later send can work, and Switch provider; else Switch provider;
 * - a cut reply: Continue; a safety refusal: Edit message; a full disk: Retry (D4);
 * - any other retryable failure: Retry; a provider-side one that is not: Choose a model; anything else none (D4), so
 *   an unknown code still has a defined fallback (§8).
 */
fun RecoveryPlan.Companion.of(failure: Failure, auth: AuthKind?): RecoveryPlan {
    val code = failure.code
    return when {
        failure.layer == AUTH || code.startsWith(AUTH) -> when {
            failure.provider != null && auth == AuthKind.SIGN_IN -> RecoveryPlan(RecoveryAction.SIGN_IN_AGAIN, RecoveryAction.RETRY)
            failure.provider != null && auth == AuthKind.KEY -> RecoveryPlan(RecoveryAction.UPDATE_KEY, RecoveryAction.RETRY)
            else -> RecoveryPlan(RecoveryAction.SWITCH_PROVIDER, RecoveryAction.RETRY)
        }
        failure.layer == BILLING || code.startsWith(BILLING) ->
            if (failure.billingUrl != null) RecoveryPlan(RecoveryAction.OPEN_BILLING, RecoveryAction.SWITCH_PROVIDER)
            else RecoveryPlan(RecoveryAction.SWITCH_PROVIDER)
        code in TOO_LONG -> RecoveryPlan(RecoveryAction.COMPRESS, RecoveryAction.NEW_CHAT)
        code == "model_not_found" -> RecoveryPlan(RecoveryAction.CHOOSE_MODEL)
        code.startsWith(FREE_TIER) ->
            if (failure.retryable) RecoveryPlan(RecoveryAction.RETRY, RecoveryAction.SWITCH_PROVIDER)
            else RecoveryPlan(RecoveryAction.SWITCH_PROVIDER)
        code == "truncated" -> RecoveryPlan(RecoveryAction.CONTINUE)
        code.startsWith(CONTENT_POLICY) -> RecoveryPlan(RecoveryAction.EDIT_MESSAGE)
        code == "disk_full" -> RecoveryPlan(RecoveryAction.RETRY)
        failure.retryable -> RecoveryPlan(RecoveryAction.RETRY)
        failure.layer in PROVIDER_SIDE -> RecoveryPlan(RecoveryAction.CHOOSE_MODEL)
        else -> RecoveryPlan(null)
    }
}

/**
 * Desktop's "Hermes error details" block (`error-surface.ts:306-334`), the text View error details shows and Copy
 * details copies: the failure's time [at] (epoch ms) and the app's version [app] beside the core's identity of it, a
 * line left out when its value is none. It names a provider and a code, never a key (§3.2): `api_key_env` is never
 * decoded. Times are UTC, to the second (D6).
 */
fun Failure.details(at: Long?, app: String): String = listOfNotNull(
    "── Hermes error details ──",
    at?.let { "time: ${utc(it)}" },
    "layer: $layer",
    "code: $code",
    "retryable: $retryable",
    resetsAt?.let { "resets_at: ${utc((it * 1000).toLong())}" },
    provider?.let { "provider: $it" },
    model?.let { "model: $it" },
    app.takeIf { it.isNotBlank() }?.let { "app: $it" },
    "error: $words",
).joinToString("\n")

/** [epochMs] as ISO-8601 UTC to the second, as `Instant.toString()` writes a whole second (`java.time` needs API 26). */
private fun utc(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))
