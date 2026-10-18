package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelOptionsParams
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** A provider key stored in the app's keystore reaches the embedded core, and only through its environment. */
@RunWith(AndroidJUnit4::class)
class CoreSecretsTest {
    private val key = "DEEPSEEK_API_KEY"
    private val dummy = "sk-hermes-android-test-not-a-real-key"
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
    private val core: CoreGateway by lazy {
        val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
        (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway
    }

    private fun deepseekAuthenticated(): Boolean? = runBlocking {
        core.call(Methods.MODEL_OPTIONS, ModelOptionsParams(includeUnconfigured = true)).providers.single { it.slug == "deepseek" }.authenticated
    }

    private fun store(value: String?) {
        if (value == null) app.secrets.delete(key) else app.secrets.put(key, value.toByteArray())
        EmbeddedPython.call("secrets_changed", "")
    }

    @After
    fun forgetTheKey() = store(null)

    @Test
    fun aStoredKeyAuthenticatesItsProviderAndDeletingItTakesItBack() {
        core
        store(null)
        assertEquals(false, deepseekAuthenticated())

        store(dummy)
        assertEquals(true, deepseekAuthenticated())

        store(null)
        assertEquals(false, deepseekAuthenticated())
    }

    @Test
    fun theKeyReachesNeitherDotEnvNorTheAppsLog() {
        core
        store(dummy)
        deepseekAuthenticated()

        val dotenv = File(app.filesDir, ".hermes/.env")
        assertFalse(dotenv.isFile && dotenv.readText().contains(dummy))
        val log = Runtime.getRuntime().exec(arrayOf("logcat", "-d")).inputStream.bufferedReader().readText()
        assertFalse("the key is in the app's logcat", log.contains(dummy))
    }
}
