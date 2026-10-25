package com.nousresearch.hermes.bridge

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Secrets at rest are AES-GCM blobs named after the env var they feed; the key never leaves its provider. */
class KeystoreSecretStoreTest {
    private lateinit var dir: File
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store get() = KeystoreSecretStore(dir) { key }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("secrets").toFile()
    }

    @Test
    fun aStoredSecretReadsBackFromANewStore() {
        store.put("OPENROUTER_API_KEY", "sk-or-123".toByteArray())

        assertArrayEquals("sk-or-123".toByteArray(), store.get("OPENROUTER_API_KEY"))
    }

    @Test
    fun theFileOnDiskDoesNotContainTheSecret() {
        store.put("OPENROUTER_API_KEY", "sk-or-plaintext-marker".toByteArray())

        val onDisk = dir.walk().filter { it.isFile }.map { it.readBytes().toString(Charsets.ISO_8859_1) }.toList()
        assertEquals(1, onDisk.size)
        assertFalse(onDisk.single().contains("plaintext-marker"))
    }

    @Test
    fun allListsEveryStoredSecretByName() {
        store.put("OPENROUTER_API_KEY", "a".toByteArray())
        store.put("DEEPSEEK_API_KEY", "b".toByteArray())

        assertEquals(setOf("OPENROUTER_API_KEY", "DEEPSEEK_API_KEY"), store.all().keys)
    }

    @Test
    fun aDeletedSecretIsGone() {
        store.put("OPENROUTER_API_KEY", "a".toByteArray())

        store.delete("OPENROUTER_API_KEY")

        assertNull(store.get("OPENROUTER_API_KEY"))
        assertEquals(emptySet<String>(), store.all().keys)
    }

    @Test
    fun aBlobThatFailsAuthenticationReadsAsAbsent() {
        store.put("OPENROUTER_API_KEY", "a".toByteArray())
        val blob = dir.walk().first { it.isFile }
        blob.writeBytes(blob.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })

        assertNull(store.get("OPENROUTER_API_KEY"))
    }

    @Test
    fun onlyEnvVarNamesAreAccepted() {
        listOf("../escape", "lower_case", "", "1STARTS_WITH_DIGIT").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { store.put(name, "x".toByteArray()) }
        }
    }
}
