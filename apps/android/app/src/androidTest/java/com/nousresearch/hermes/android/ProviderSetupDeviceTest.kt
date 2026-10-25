package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.setup.ProviderSetup
import com.nousresearch.hermes.android.setup.SetupStep
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelDisconnectParams
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The app's setup against the embedded core: a pasted key signs a provider in and its model becomes the default. */
@RunWith(AndroidJUnit4::class)
class ProviderSetupDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
    private val config = File(app.filesDir, ".hermes/config.yaml")
    private var configBefore: ByteArray? = null
    private val ready: CoreState.Ready by lazy {
        val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
        state as? CoreState.Ready ?: error("the core did not start: $state")
    }
    private val setup: ProviderSetup get() = ready.setup
    private val auth = File(app.filesDir, ".hermes/auth.json")

    /** What the app does to forget a key: the keystore, then the core's own credential rows. */
    private fun forgetDeepSeek() {
        app.secrets.delete("DEEPSEEK_API_KEY")
        EmbeddedPython.call("secrets_changed", "")
        runCatching { runBlocking { ready.gateway.call(Methods.MODEL_DISCONNECT, ModelDisconnectParams("deepseek")) } }
    }

    @Before
    fun keepTheConfig() = runBlocking {
        // A fresh core opens setup on its own at start: let that listing land before the test drives setup.
        withTimeout(120_000) { setup.state.first { it == null || (it.step !is SetupStep.Loading && !it.busy) } }
        configBefore = config.takeIf { it.isFile }?.readBytes()
        forgetDeepSeek()
    }

    @After
    fun restore() {
        forgetDeepSeek()
        configBefore?.let(config::writeBytes)
        setup.close()
        // A pool row left here would list DeepSeek as signed in after the next restart, with no key anywhere.
        val pool = auth.takeIf { it.isFile }?.let { JSONObject(it.readText()).optJSONObject("credential_pool") }
        assertFalse(pool?.optJSONArray("deepseek")?.length()?.let { it > 0 } ?: false)
    }

    @Test
    fun aPastedKeySignsDeepSeekInAndItsModelBecomesTheDefault() = runBlocking {
        setup.open()
        setup.pick("deepseek")
        assertTrue(setup.state.value?.step is SetupStep.Key)

        setup.submitKey("sk-hermes-android-test-not-a-real-key".toByteArray())
        val models = (setup.state.value?.step as? SetupStep.Models ?: error("no models: ${setup.state.value}")).provider.models
        assertTrue("the core lists DeepSeek's models", models.isNotEmpty())

        val before = ready.chat.sessionId
        setup.choose(models.first())
        assertNull("setup closes: ${setup.state.value}", setup.state.value)
        assertNotEquals("the chat starts a session that knows the new default", before, ready.chat.sessionId)
        val written = config.readText()
        assertTrue(written, written.contains("provider: deepseek") && written.contains("default: ${models.first()}"))
        // Not .env, not auth.json's credential pool, not any other file: the key lives in the keystore only.
        val holding = app.filesDir.walkTopDown().filter { it.isFile && it.length() < 64L shl 20 }
            .filter { file -> file.readBytes().let { String(it, Charsets.ISO_8859_1).contains("sk-hermes-android-test") } }.toList()
        assertEquals(emptyList<File>(), holding)

        // Forgetting it from the setup screen takes it off the phone and out of the core's credential rows.
        setup.open()
        setup.pick("deepseek")
        assertTrue("the held key can be forgotten: ${setup.state.value}", (setup.state.value?.step as? SetupStep.Models)?.keyHeld == true)
        setup.forgetKey()
        assertNull(app.secrets.get("DEEPSEEK_API_KEY"))
        val listed = (setup.state.value?.step as? SetupStep.Providers ?: error("not back on the list: ${setup.state.value}")).choices
        assertFalse("DeepSeek no longer signed in", listed.first { it.slug == "deepseek" }.ready)
        val pool = auth.takeIf { it.isFile }?.let { JSONObject(it.readText()).optJSONObject("credential_pool") }
        assertFalse(pool?.optJSONArray("deepseek")?.length()?.let { it > 0 } ?: false)
    }
}
