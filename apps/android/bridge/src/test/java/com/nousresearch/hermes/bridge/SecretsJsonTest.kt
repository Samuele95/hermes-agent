package com.nousresearch.hermes.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keystore's secrets as the JSON the core's secret source reads, built in bytes only and wiped behind. */
class SecretsJsonTest {
    @Test
    fun anyValueComesOutAsTheSameStringInValidJson() {
        val values = mapOf("PLAIN" to "sk-abc123", "QUOTES" to "a\"b\\c", "CONTROL" to "line\nnext\ttab\u0001",
                           "UNICODE" to "clé-😀", "EMPTY" to "")

        val json = SecretsJson.encode(values.mapValues { (_, v) -> v.toByteArray() })

        val decoded = Json.parseToJsonElement(json.decodeToString()).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
        assertEquals(values, decoded)
        // The reader is Python's json.loads, which (unlike this parser) refuses raw control characters in strings.
        assertTrue(json.none { (it.toInt() and 0xff) < 0x20 })
    }

    @Test
    fun theValuesAreWipedOnceWritten() {
        val held = mapOf("A" to "sk-one".toByteArray(), "B" to "sk-two".toByteArray())

        SecretsJson.encode(held)

        assertTrue(held.values.all { value -> value.all { it == 0.toByte() } })
    }

    @Test
    fun noSecretsIsAnEmptyObject() {
        assertEquals("{}", SecretsJson.encode(emptyMap()).decodeToString())
    }
}
