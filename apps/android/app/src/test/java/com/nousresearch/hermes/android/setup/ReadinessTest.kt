package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.bridgeJson
import com.nousresearch.hermes.bridge.protocol.SetupRuntimeCheckResult
import org.junit.Assert.assertEquals
import org.junit.Test

// What the pill shows is mapped once from the core's check: ok is ready, a refusal is needs setup or unavailable in the core's words.
class ReadinessTest {
    /** One `setup.runtime_check` answer as the core writes it. */
    private fun check(json: String) = bridgeJson.decodeFromString(SetupRuntimeCheckResult.serializer(), json)

    private val refused = check("""{"ok":false,"provider":"deepseek","model":"m","error":" No usable credentials found for deepseek. "}""")

    @Test
    fun anOkCheckIsReadyWithTheCoresProviderAndModel() {
        assertEquals(Readiness.Ready("deepseek", "deepseek-v4-pro"),
                     Readiness.of(check("""{"ok":true,"provider":"deepseek","model":"deepseek-v4-pro","source":"env"}"""),
                                  configured = false))
        assertEquals(Readiness.Ready(null, null), Readiness.of(check("""{"ok":true}"""), configured = false))
        assertEquals("a blank name is none", Readiness.Ready(null, null),
                     Readiness.of(check("""{"ok":true,"provider":"","model":" "}"""), configured = true))
    }

    @Test
    fun aRefusalWithAProviderSetUpIsUnavailableInTheCoresWords() {
        assertEquals(Readiness.Unavailable("No usable credentials found for deepseek."), Readiness.of(refused, configured = true))
    }

    @Test
    fun aRefusalWithNoProviderNeedsSetup() {
        assertEquals(Readiness.NeedsSetup("No usable credentials found for deepseek."), Readiness.of(refused, configured = false))
    }

    @Test
    fun aRefusalWithoutWordsReadsDesktopsSentence() {
        for (answer in listOf("""{"ok":false}""", """{"ok":false,"error":"   "}""")) {
            assertEquals(answer, Readiness.NeedsSetup(Readiness.NOT_READY), Readiness.of(check(answer), configured = false))
            assertEquals(answer, Readiness.Unavailable(Readiness.NOT_READY), Readiness.of(check(answer), configured = true))
        }
    }
}
