package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.setup.AuthKind
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.MessageCompletePayload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// A failed turn keeps the core's failure (`error_surface`), and the recovery card's plan is read from it alone: one main
// fix, at most one second, never one the error does not support (chat-16, bet 8, F7).
class RecoveryTest {
    /** One `message.complete` as `prompt_turn.py` writes it, read as the event wire reads it (unknown keys dropped). */
    private fun done(json: String): MessageCompletePayload =
        CoreEvent("message.complete", "s1", Json.parseToJsonElement(json)).decode(Events.MESSAGE_COMPLETE)!!

    /** A failed completion carrying [surface] as its `error_surface`. */
    private fun failed(surface: String, error: String = "boom") =
        done("""{"text":"","status":"error","error":"$error","recoverable":true,"error_surface":$surface}""")

    private val unauthorized = done("""{"text":"Authentication failed. Your message was not answered.","status":"error",
        "error":"401 Unauthorized","recoverable":true,
        "error_surface":{"layer":"auth","code":"auth","retryable":false,"provider":"deepseek","model":"deepseek-v4-pro",
        "provider_label":"DeepSeek","auth_kind":"api_key","api_key_env":"DEEPSEEK_API_KEY"}}""")

    private val deepseekWall = """{"provider":"deepseek","provider_label":"DeepSeek","model":"deepseek-v4-pro",
        "billing_url":"https://platform.deepseek.com/top_up","is_nous":false,"message":"Out of credits."}"""

    /** A turn the provider refused for billing, as ChatTest's `BILLED`, with [block] as its wall. */
    private fun billed(block: String = deepseekWall) = done("""{"text":"Billing or credits exhausted: 402","status":"error",
        "error":"402 Insufficient Balance","recoverable":true,"failure_reason":"billing",
        "error_surface":{"layer":"billing","code":"billing","retryable":false},"billing":$block}""")

    private fun plan(layer: String, code: String, retryable: Boolean, provider: String? = null, auth: AuthKind? = null,
                     billingUrl: String? = null): RecoveryPlan =
        RecoveryPlan.of(Failure(layer, code, retryable, provider, billingUrl = billingUrl), auth)

    @Test
    fun aFailedCompletionCarriesTheCoresFailureAndNothingOutsideTheContract() {
        assertEquals(Failure("auth", "auth", false, "deepseek", "deepseek-v4-pro", null, "401 Unauthorized"), Failure.of(unauthorized))

        assertNull("a completed turn has none", Failure.of(done("""{"text":"ok","status":"complete",
            "error_surface":{"layer":"provider","code":"rate_limit","retryable":true}}""")))
        assertNull("a stopped turn has none", Failure.of(done("""{"text":"","status":"interrupted"}""")))
        assertNull("a failure with no descriptor has none (D9)", Failure.of(done("""{"text":"","status":"error","error":"timeout"}""")))

        assertNull("no reset time", Failure.of(failed("""{"layer":"provider","code":"rate_limit","retryable":true,"resets_at":0}"""))!!.resetsAt)
        assertNull("a blank provider is none", Failure.of(failed("""{"layer":"provider","code":"x","retryable":true,"provider":"  "}"""))!!.provider)
        assertEquals("a blank code reads unknown", "unknown", Failure.of(failed("""{"layer":"provider","code":"","retryable":true}"""))!!.code)

        assertEquals("https://platform.deepseek.com/top_up", Failure.of(billed())!!.billingUrl)
        assertNull("a page the app cannot open is none (M7)",
                   Failure.of(billed(deepseekWall.replace("https://platform.deepseek.com/top_up", "javascript:x")))!!.billingUrl)
    }

    @Test
    fun anAuthFailureNamesTheFixByHowTheAppHoldsTheProvidersCredential() {
        assertEquals(RecoveryPlan(RecoveryAction.UPDATE_KEY, RecoveryAction.RETRY), plan("auth", "auth", false, "deepseek", AuthKind.KEY))
        assertEquals(RecoveryPlan(RecoveryAction.SIGN_IN_AGAIN, RecoveryAction.RETRY),
                     plan("auth", "auth_permanent", false, "nous", AuthKind.SIGN_IN))
        assertEquals("a credential the app cannot tell (D2)", RecoveryPlan(RecoveryAction.SWITCH_PROVIDER, RecoveryAction.RETRY),
                     plan("auth", "auth", false, "openai-codex", null))
        assertEquals("no provider named (D2)", RecoveryPlan(RecoveryAction.SWITCH_PROVIDER, RecoveryAction.RETRY),
                     plan("auth", "auth", false, null, AuthKind.KEY))
    }

    @Test
    fun anOutOfCreditsFailureOpensTheWallsPageOnlyWhenTheCoreNamedOne() {
        assertEquals(RecoveryPlan(RecoveryAction.OPEN_BILLING, RecoveryAction.SWITCH_PROVIDER),
                     plan("billing", "billing", false, billingUrl = "https://platform.deepseek.com/top_up"))
        assertEquals("no page (D3)", RecoveryPlan(RecoveryAction.SWITCH_PROVIDER, null), plan("billing", "billing_unverified", false))
    }

    @Test
    fun eachCodeTheShippedCoreSendsMapsToItsFix() {
        val table = listOf(
            Triple("provider", "context_overflow", true) to RecoveryPlan(RecoveryAction.COMPRESS, RecoveryAction.NEW_CHAT),
            Triple("provider", "payload_too_large", false) to RecoveryPlan(RecoveryAction.COMPRESS, RecoveryAction.NEW_CHAT),
            Triple("provider", "model_not_found", false) to RecoveryPlan(RecoveryAction.CHOOSE_MODEL),
            Triple("provider", "free_tier_rate_limited", true) to RecoveryPlan(RecoveryAction.RETRY, RecoveryAction.SWITCH_PROVIDER),
            Triple("provider", "free_tier_model_not_free", false) to RecoveryPlan(RecoveryAction.SWITCH_PROVIDER),
            Triple("provider", "truncated", true) to RecoveryPlan(RecoveryAction.CONTINUE),
            Triple("provider", "content_policy_blocked", false) to RecoveryPlan(RecoveryAction.EDIT_MESSAGE),
            Triple("disk", "disk_full", false) to RecoveryPlan(RecoveryAction.RETRY),
            Triple("streaming", "stream_drop", true) to RecoveryPlan(RecoveryAction.RETRY),
            Triple("gateway", "session_busy", true) to RecoveryPlan(RecoveryAction.RETRY),
            Triple("runtime", "agent_init_failed", true) to RecoveryPlan(RecoveryAction.RETRY),
            Triple("provider", "rate_limit", true) to RecoveryPlan(RecoveryAction.RETRY),
        )
        for ((given, expected) in table) {
            val (layer, code, retryable) = given
            assertEquals("$layer/$code", expected, plan(layer, code, retryable))
        }
    }

    @Test
    fun anUnknownCodeFallsToRetryOrChooseModel() {
        assertEquals(RecoveryPlan(RecoveryAction.RETRY), plan("provider", "brand_new_code", true))
        for ((layer, code) in listOf("provider" to "upstream_blocked", "endpoint" to "ssl_cert_verification", "provider" to "format_error")) {
            assertEquals("$layer/$code", RecoveryPlan(RecoveryAction.CHOOSE_MODEL), plan(layer, code, false))
        }
    }

    @Test
    fun aFailureTheAppCannotFixOffersNoActionAndTheDetailsNameNoKey() {
        for ((layer, code) in listOf("gateway" to "interpreter_shutdown", "gateway" to "RuntimeError", "weird" to "x")) {
            assertEquals("$layer/$code (D4)", RecoveryPlan(null), plan(layer, code, false))
        }

        assertEquals("── Hermes error details ──\ntime: 2026-11-15T08:00:00Z\nlayer: provider\ncode: rate_limit\nretryable: true\n" +
                         "resets_at: 2026-11-15T09:00:00Z\nprovider: openrouter\nmodel: x/y\napp: 0.1.0\nerror: 429 Too Many Requests",
                     Failure("provider", "rate_limit", true, "openrouter", "x/y", 1_794_733_200.0, "429 Too Many Requests")
                         .details(1_794_729_600_000, "0.1.0"))

        val details = Failure.of(unauthorized)!!.details(null, "")
        assertFalse(details, "time:" in details)
        assertFalse(details, "app:" in details)
        assertFalse(details, "DEEPSEEK_API_KEY" in details)
        assertFalse(details, "api_key" in details)
    }
}
