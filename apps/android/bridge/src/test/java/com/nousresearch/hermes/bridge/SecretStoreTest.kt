package com.nousresearch.hermes.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Asking whether a secret is held, or which are, leaves no copy of one behind. */
class SecretStoreTest {
    private val handedOut = mutableListOf<ByteArray>()
    private val store = object : SecretStore {
        override fun put(name: String, value: ByteArray) = Unit
        override fun get(name: String): ByteArray? = if (name == "HELD") "sk-secret".toByteArray().also { handedOut += it } else null
        override fun delete(name: String) = Unit
        override fun all(): Map<String, ByteArray> =
            listOf("A_KEY", "B_KEY").associateWith { "sk-$it".toByteArray().also { copy -> handedOut += copy } }
    }

    @Test
    fun containsSaysWhetherASecretIsHeldAndWipesWhatItRead() {
        assertTrue(store.contains("HELD"))
        assertFalse(store.contains("ABSENT"))

        assertTrue(handedOut.single().all { it == 0.toByte() })
    }

    @Test
    fun namesListWhatIsHeldAndWipesEveryValueItRead() {
        assertEquals(setOf("A_KEY", "B_KEY"), store.names())

        assertEquals(2, handedOut.size)
        assertTrue(handedOut.all { copy -> copy.all { it == 0.toByte() } })
    }
}
