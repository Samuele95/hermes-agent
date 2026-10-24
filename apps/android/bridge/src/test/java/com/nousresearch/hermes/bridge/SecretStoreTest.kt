package com.nousresearch.hermes.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asking whether a secret is held leaves no copy of it behind. */
class SecretStoreTest {
    private val handedOut = mutableListOf<ByteArray>()
    private val store = object : SecretStore {
        override fun put(name: String, value: ByteArray) = Unit
        override fun get(name: String): ByteArray? = if (name == "HELD") "sk-secret".toByteArray().also { handedOut += it } else null
        override fun delete(name: String) = Unit
        override fun all(): Map<String, ByteArray> = emptyMap()
    }

    @Test
    fun containsSaysWhetherASecretIsHeldAndWipesWhatItRead() {
        assertTrue(store.contains("HELD"))
        assertFalse(store.contains("ABSENT"))

        assertTrue(handedOut.single().all { it == 0.toByte() })
    }
}
