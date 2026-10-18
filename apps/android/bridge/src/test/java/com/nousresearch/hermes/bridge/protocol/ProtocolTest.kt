package com.nousresearch.hermes.bridge.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The generated types speak the core's wire format: snake_case names, defaults left to the core. */
class ProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun aRequestCarriesOnlyTheFieldsTheAppSets() {
        val wire = json.encodeToString(Methods.PROMPT_SUBMIT.params, PromptSubmitParams(sessionId = "s1", text = JsonPrimitive("héllo 😀")))

        assertEquals("""{"session_id":"s1","text":"héllo 😀"}""", wire)
    }

    @Test
    fun aResultDecodesFromTheWireIgnoringFieldsTheAppDoesNotModel() {
        assertEquals(PingResult(pong = true), json.decodeFromString(Methods.PING.result, """{"pong":true,"latency_ms":3}"""))
    }

    @Test
    fun paramsWithoutFieldsAreAnEmptyObject() {
        assertEquals("{}", json.encodeToString(Methods.PING.params, PingParams))
    }
}
