package com.nousresearch.hermes.bridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The secrets the core reads as environment variables (provider keys), by variable name. */
interface SecretStore {
    fun put(name: String, value: ByteArray)

    fun get(name: String): ByteArray?

    fun delete(name: String)

    fun all(): Map<String, ByteArray>
}

/**
 * [SecretStore] as one AES-GCM blob per secret under [dir], named after the variable it feeds.
 * [key] supplies the wrapping key: in the app an AndroidKeyStore key ([androidKey]), whose material
 * never leaves the keystore, so a copied data directory decrypts nothing.
 */
class KeystoreSecretStore(private val dir: File, private val key: () -> SecretKey) : SecretStore {
    companion object {
        private val NAME = Regex("[A-Z_][A-Z0-9_]*")
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** The app's wrapping key, created in the AndroidKeyStore on first use. */
        fun androidKey(alias: String = "hermes-secrets"): SecretKey {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
    }

    override fun put(name: String, value: ByteArray) {
        require(NAME.matches(name)) { "not an environment variable name: $name" }
        // The cipher picks the IV: keystore keys only allow randomized encryption.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(value)
        dir.mkdirs()
        val staging = File(dir, ".$name.tmp")
        staging.writeBytes(blob)
        if (!staging.renameTo(File(dir, name))) throw java.io.IOException("could not store $name")
    }

    /** The secret, or null when absent or when its blob fails authentication. */
    override fun get(name: String): ByteArray? {
        val blob = File(dir, name).takeIf { NAME.matches(name) && it.isFile }?.readBytes() ?: return null
        if (blob.size <= IV_BYTES) return null
        return try {
            Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)) }
                .doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    override fun delete(name: String) {
        if (NAME.matches(name)) File(dir, name).delete()
    }

    override fun all(): Map<String, ByteArray> =
        (dir.list() ?: emptyArray()).filter(NAME::matches).sorted().mapNotNull { name -> get(name)?.let { name to it } }.toMap()
}
