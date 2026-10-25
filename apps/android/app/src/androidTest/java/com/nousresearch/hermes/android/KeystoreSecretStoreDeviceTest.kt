package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.KeystoreSecretStore
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** On the device the wrapping key lives in the AndroidKeyStore and cannot be read out. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreDeviceTest {
    private val alias = "hermes-secrets-test"
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "secret-store-test")

    @After
    fun removeTheTestKeyAndBlobs() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        dir.deleteRecursively()
    }

    @Test
    fun aSecretRoundTripsUnderTheKeystoreKey() {
        KeystoreSecretStore(dir) { KeystoreSecretStore.androidKey(alias) }.put("HERMES_TEST_SECRET", "s3cret".toByteArray())

        val read = KeystoreSecretStore(dir) { KeystoreSecretStore.androidKey(alias) }.get("HERMES_TEST_SECRET")

        assertArrayEquals("s3cret".toByteArray(), read)
    }

    @Test
    fun theKeystoreKeyCannotBeExported() {
        assertNull(KeystoreSecretStore.androidKey(alias).encoded)
    }
}
