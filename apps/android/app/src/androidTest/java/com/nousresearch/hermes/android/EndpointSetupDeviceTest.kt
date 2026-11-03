package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.setup.ProviderSetup
import com.nousresearch.hermes.android.setup.SetupStep
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.contract.SavedEndpoint
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelDisconnectParams
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A custom endpoint's whole life through the app's core and its real bridge, against the host's Ollama
 * (`adb reverse tcp:11434 tcp:11434`): found from a bare address, saved with a key as the default model, edited (same id,
 * key kept), deleted: nothing of it is left in config.yaml, the keystore or the core's credential rows, and the chat,
 * whose default it was, starts afresh.
 */
@RunWith(AndroidJUnit4::class)
class EndpointSetupDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
    private val config = File(app.filesDir, ".hermes/config.yaml")
    private val auth = File(app.filesDir, ".hermes/auth.json")
    private var configBefore: ByteArray? = null
    private val ready: CoreState.Ready by lazy {
        val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
        state as? CoreState.Ready ?: error("the core did not start: $state")
    }
    private val setup: ProviderSetup get() = ready.setup

    private companion object {
        const val ID = "hermes-device-test"  // the core's id rule applied to NAME
        const val NAME = "Hermes Device Test"
        const val KEY_ENV = "HERMES_CUSTOM_HERMES_DEVICE_TEST_API_KEY"
        const val KEY = "sk-hermes-endpoint-test-not-a-real-key"
    }

    /** The core's credential rows for this endpoint, under its id or the legacy `custom:<name>` keys. */
    private fun pooled(): Int {
        val pool = auth.takeIf { it.isFile }?.let { JSONObject(it.readText()).optJSONObject("credential_pool") } ?: return 0
        return pool.keys().asSequence().filter { ID in it || "hermes" in it }.sumOf { pool.optJSONArray(it)?.length() ?: 0 }
    }

    private fun saved(): List<SavedEndpoint> {
        val listed = setup.state.value?.step as? SetupStep.Providers ?: error("not on the provider list: ${setup.state.value}")
        return listed.saved
    }

    private fun forgetTheEndpoint() {
        app.secrets.delete(KEY_ENV)
        EmbeddedPython.call("secrets_changed", "")
        runCatching { runBlocking { ready.gateway.call(Methods.MODEL_DISCONNECT, ModelDisconnectParams(ID)) } }
    }

    @Before
    fun keepTheConfig() = runBlocking {
        // A fresh core opens setup on its own at start: let that listing land before the test drives setup.
        withTimeout(120_000) { setup.state.first { it == null || (it.step !is SetupStep.Loading && !it.busy) } }
        configBefore = config.takeIf { it.isFile }?.readBytes()
        forgetTheEndpoint()
    }

    @After
    fun restore() {
        setup.close()
        forgetTheEndpoint()
        configBefore?.let(config::writeBytes)
    }

    @Test
    fun anEndpointIsFoundSavedEditedAndDeletedWithNothingLeft() = runBlocking {
        // Found from a bare address: the core's probe names the server's URL and its models.
        setup.open()
        setup.addEndpoint()
        setup.findServer("127.0.0.1", null)
        val probe = (setup.state.value?.step as? SetupStep.Endpoint)?.probe ?: error("nothing found: ${setup.state.value}")
        assertEquals("http://127.0.0.1:11434/v1", probe.baseUrl)
        assertTrue("Ollama lists its models", probe.models.isNotEmpty())
        val model = probe.models.first()

        // Saved with a key: the endpoint becomes the default model and the chat a new session.
        val first = ready.chat.sessionId
        setup.saveEndpoint(NAME, probe.baseUrl!!, model, KEY.toByteArray())
        assertNull("setup closes: ${setup.state.value}", setup.state.value)
        assertNotEquals(first, ready.chat.sessionId)
        assertArrayEquals(KEY.toByteArray(), app.secrets.get(KEY_ENV))
        assertTrue(config.readText(), config.readText().contains("provider: $ID"))
        // A real turn on it: the key reaches the core, and the core had every chance to keep a credential row for it.
        ready.chat.send("Reply with the single word pong.")
        val answered = withTimeout(300_000) { ready.chat.state.first { !it.running && it.entries.any { e -> e is Entry.Reply } } }
        assertEquals(TurnStatus.COMPLETE, answered.entries.filterIsInstance<Entry.Reply>().last().status)
        setup.open()
        assertEquals(listOf(SavedEndpoint(ID, NAME, probe.baseUrl!!, model, true)), saved().filter { it.id == ID })

        // Edited: renamed, same id, the key it had kept (none typed), still the default.
        setup.editEndpoint(ID)
        assertEquals(ID, (setup.state.value?.step as? SetupStep.Endpoint)?.editing?.id)
        val second = ready.chat.sessionId
        setup.saveEndpoint("Hermes Renamed", probe.baseUrl!!, model, null)
        assertNull("setup closes: ${setup.state.value}", setup.state.value)
        assertNotEquals(second, ready.chat.sessionId)
        assertArrayEquals(KEY.toByteArray(), app.secrets.get(KEY_ENV))
        setup.open()
        assertEquals(listOf(SavedEndpoint(ID, "Hermes Renamed", probe.baseUrl!!, model, true)), saved().filter { it.id == ID })

        // Deleted (what the confirm button calls): gone from config.yaml, the keystore and the core's credential rows.
        setup.editEndpoint(ID)
        val third = ready.chat.sessionId
        setup.deleteEndpoint(ID)
        assertNull("deleted without an error: ${setup.state.value?.error}", setup.state.value?.error)
        assertEquals(emptyList<SavedEndpoint>(), saved().filter { it.id == ID })
        assertTrue("the list says no default is left", (setup.state.value?.step as SetupStep.Providers).defaultGone)
        val written = config.readText()
        assertFalse(written, written.contains(ID))
        assertNull(app.secrets.get(KEY_ENV))
        assertEquals(0, pooled())
        assertNotEquals("the chat, whose default it was, starts afresh", third, ready.chat.sessionId)
    }

    @Test
    fun aKeylessReAddUnderASavedNameGivesItsKeyBack() = runBlocking {
        // Added again (not edited) under the saved name with no key: config.yaml calls it keyless, so no key is left.
        setup.open()
        setup.addEndpoint()
        setup.findServer("127.0.0.1", null)
        val probe = (setup.state.value?.step as? SetupStep.Endpoint)?.probe ?: error("nothing found: ${setup.state.value}")
        val baseUrl = probe.baseUrl!!
        val model = probe.models.first()
        setup.saveEndpoint(NAME, baseUrl, model, KEY.toByteArray())
        assertNull("setup closes: ${setup.state.value}", setup.state.value)
        assertArrayEquals(KEY.toByteArray(), app.secrets.get(KEY_ENV))

        setup.open()
        setup.addEndpoint()
        setup.saveEndpoint(NAME, baseUrl, model, null)
        assertNull("setup closes: ${setup.state.value}", setup.state.value)

        assertNull("the key its entry no longer names is gone", app.secrets.get(KEY_ENV))
        assertEquals(0, pooled())
        setup.open()
        assertEquals(listOf(SavedEndpoint(ID, NAME, baseUrl, model, false)), saved().filter { it.id == ID })
    }

    @Test
    fun aHandWrittenNumericEntryIsEditedAndDeletedInPlace() = runBlocking {
        // An entry typed by hand (or by another client) under an unquoted number loads with an int key; the app finds
        // it by the core's provider-key rule, so it is edited and deleted in place, not saved again beside itself.
        setup.open()
        setup.addEndpoint()
        setup.findServer("127.0.0.1", null)
        val probe = (setup.state.value?.step as? SetupStep.Endpoint)?.probe ?: error("nothing found: ${setup.state.value}")
        val baseUrl = probe.baseUrl!!
        val model = probe.models.first()
        setup.close()
        val entry = "  4242:\n    name: Numeric Box\n    base_url: $baseUrl\n    model: $model\n"
        val lines = config.takeIf { it.isFile }?.readLines() ?: emptyList()
        config.writeText(when {
            "providers: {}" in lines -> lines.joinToString("\n") { if (it == "providers: {}") "providers:\n" + entry.trimEnd() else it } + "\n"
            "providers:" in lines -> lines.joinToString("\n") { if (it == "providers:") "providers:\n" + entry.trimEnd() else it } + "\n"
            else -> lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n") + "providers:\n" + entry
        })
        try {
            setup.open()
            assertEquals(listOf("Numeric Box"), saved().filter { it.id == "4242" }.map { it.name })

            setup.editEndpoint("4242")
            setup.saveEndpoint("Numeric Renamed", baseUrl, model, null)
            setup.open()
            assertEquals(listOf(SavedEndpoint("4242", "Numeric Renamed", baseUrl, model, false)),
                         saved().filter { it.baseUrl == baseUrl && (it.id == "4242" || "numeric" in it.id) })

            setup.editEndpoint("4242")
            setup.deleteEndpoint("4242")
            assertNull("deleted without an error: ${setup.state.value?.error}", setup.state.value?.error)
            assertEquals(emptyList<SavedEndpoint>(), saved().filter { it.id == "4242" })
            val written = config.readText()
            assertFalse(written, written.contains("4242") || written.contains("numeric-renamed"))
        } finally {
            runCatching { ready.gateway.call(Methods.MODEL_DISCONNECT, ModelDisconnectParams("4242")) }
        }
    }

    @Test
    fun anAddressThatNamesNoServerIsAnsweredInWords() = runBlocking {
        // Each names no server: no host at all (on the phone that reached its own loopback, here the host's Ollama
        // through adb reverse), an unbracketed IPv6 literal, a port out of range (a crash in the bridge call before).
        for (address in listOf("http://", "::1", "http://127.0.0.1:99999")) {
            setup.open()
            setup.addEndpoint()
            setup.findServer(address, null)
            assertEquals(address, "That is not a server address: use a host name, host:port or an http(s) URL.",
                         setup.state.value?.error)
            assertNull(address, (setup.state.value?.step as? SetupStep.Endpoint ?: error("left the form: ${setup.state.value}")).probe)
            setup.close()
        }
    }
}
