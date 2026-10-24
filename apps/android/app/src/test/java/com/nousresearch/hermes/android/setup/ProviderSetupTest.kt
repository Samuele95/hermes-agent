package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setting up a provider: the core lists them, the key goes to the keystore, the chosen model becomes the default. */
class ProviderSetupTest {
    private val held = mutableMapOf<String, ByteArray>()
    private val secrets = object : SecretStore {
        override fun put(name: String, value: ByteArray) { held[name] = value.copyOf() }
        override fun get(name: String) = held[name]
        override fun delete(name: String) { held.remove(name) }
        override fun all() = held.toMap()
    }
    private val log = mutableListOf<String>()
    private var deepseekRow = UNAUTHENTICATED_DEEPSEEK
    private var nousRow = """{"slug":"nous","name":"Nous Portal","models":[],"authenticated":false,"auth_type":"oauth_device_code","key_env":""}"""
    private val polls = ArrayDeque(listOf("""{"status":"pending"}""", """{"status":"approved"}"""))
    private var disconnect: (JsonObject) -> String = { """{"slug":"deepseek","name":"DeepSeek","disconnected":true}""" }
    private var configSet: (JsonObject) -> String = { """{"key":"model","value":"deepseek-v4-pro","scope":"global"}""" }
    private val core = FakeCoreGateway { method, params ->
        log += method
        when (method) {
            "setup.status" -> """{"provider_configured":false}"""
            "model.options" -> """{"providers":[$deepseekRow, $nousRow,
                {"slug":"openai-codex","name":"OpenAI Codex","models":[],"authenticated":false,"auth_type":"oauth_external","key_env":""},
                {"slug":"openrouter","name":"OpenRouter","models":[],"authenticated":false,"auth_type":"api_key","key_env":""},
                {"slug":"copilot","name":"GitHub Copilot","models":["gpt-5"],"authenticated":true}]}"""
            "config.set" -> configSet(params)
            "model.disconnect" -> disconnect(params)
            "nous.login.start" -> """{"login_id":"L1","user_code":"NOUS-1234","verification_url":"https://portal.test/device?user_code=NOUS-1234","expires_in":600,"poll_interval":1}"""
            "nous.login.poll" -> polls.removeFirst().also { if ("approved" in it) nousRow = READY_NOUS }
            "nous.login.cancel" -> """{"cancelled":true}"""
            else -> error("unexpected $method")
        }
    }
    private var endpointSaved: (JsonObject) -> String = { """{"id":"host-ollama","key_env":"HERMES_CUSTOM_HOST_OLLAMA_API_KEY"}""" }
    private var secretsChanged: () -> Unit = { log += "secrets_changed" }
    private val setup = ProviderSetup(core, secrets, secretsChanged = { secretsChanged() }, session = { "s1" },
                                      reopenChat = { log += "reopen_chat" },
                                      saveEndpoint = { request -> log += "save_endpoint"; endpointSaved(Json.parseToJsonElement(request).jsonObject) })

    private fun ProviderSetup.step() = state.value?.step

    @Test
    fun aBridgeFailureMidStepIsShownByItsKindAndTheAppGoesOn() = runTest {
        // EmbeddedPython.call throws RuntimeException carrying the Python traceback: never words for the screen.
        secretsChanged = { throw RuntimeException("Traceback (most recent call last): ... sk-not-for-the-screen") }
        setup.open()
        setup.pick("deepseek")

        setup.submitKey("sk-test".toByteArray())

        assertEquals(false, setup.state.value?.busy)
        assertEquals("Something went wrong (RuntimeException).", setup.state.value?.error)
    }

    @Test
    fun aFreshCoreNeedsSetup() = runTest {
        assertTrue(setup.needed())
    }

    @Test
    fun theProvidersOfferedAreTheKeyBasedOnesAndThoseAlreadySignedIn() = runTest {
        setup.open()

        val offered = (setup.step() as SetupStep.Providers).choices
        assertEquals(listOf("deepseek", "nous", "copilot"), offered.map { it.slug })
        assertEquals(listOf("DEEPSEEK_API_KEY", null, null), offered.map { it.keyEnv })
        assertEquals(listOf(false, false, true), offered.map { it.ready })
        assertEquals(listOf(false, true, false), offered.map { it.signIn })
    }

    @Test
    fun aProviderAlreadySignedInGoesStraightToItsModels() = runTest {
        setup.open()

        setup.pick("copilot")

        assertEquals(listOf("gpt-5"), (setup.step() as SetupStep.Models).provider.models)
    }

    @Test
    fun aPastedKeyGoesToTheKeystoreThenTheCoreAndOpensTheProvidersModels() = runTest {
        setup.open()
        setup.pick("deepseek")
        assertEquals("deepseek", (setup.step() as SetupStep.Key).provider.slug)
        deepseekRow = """{"slug":"deepseek","name":"DeepSeek","models":["deepseek-flash","deepseek-v4-pro"],"authenticated":true}"""
        val key = "sk-test".toByteArray()

        setup.submitKey(key)

        assertArrayEquals("sk-test".toByteArray(), held["DEEPSEEK_API_KEY"])
        assertArrayEquals("the caller's copy is wiped", ByteArray(7), key)
        assertEquals(listOf("model.options", "secrets_changed", "model.options"), log)
        assertTrue(core.calls.last().second.getValue("refresh").jsonPrimitive.boolean)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), (setup.step() as SetupStep.Models).provider.models)
    }

    @Test
    fun aKeyTheCoreDoesNotTakeIsForgottenEverywhereAndSaidSo() = runTest {
        setup.open()
        setup.pick("deepseek")

        setup.submitKey("sk-test".toByteArray())

        assertTrue(held.isEmpty())
        // The core keeps env-backed credential rows across restarts until the provider is disconnected.
        assertEquals(listOf("model.options", "secrets_changed", "model.options", "secrets_changed", "model.disconnect"), log)
        assertEquals("deepseek", core.calls.last().second.getValue("slug").jsonPrimitive.content)
        assertTrue(setup.step() is SetupStep.Key)
        assertTrue(setup.state.value?.error != null)
    }

    @Test
    fun aProviderWithNothingLeftToDisconnectIsNoFailure() = runTest {
        disconnect = { throw CoreError(4005, "no credentials found for deepseek") }
        setup.open()
        setup.pick("deepseek")

        setup.submitKey("sk-test".toByteArray())

        assertEquals("DeepSeek did not accept the key.", setup.state.value?.error)
    }

    @Test
    fun theChosenModelBecomesTheDefaultThroughTheChatsSessionThenTheChatStartsAfreshAndSetupCloses() = runTest {
        setup.open()
        setup.pick("copilot")

        setup.choose("gpt-5")

        // A session that existed before the switch runs its first turn on the old default model.
        assertEquals(listOf("config.set", "reopen_chat"), log.takeLast(2))
        val params = core.calls.last().second
        assertEquals(listOf("s1", "model", "gpt-5 --provider copilot --global"),
                     listOf("session_id", "key", "value").map { params.getValue(it).jsonPrimitive.content })
        assertNull(setup.state.value)
    }

    @Test
    fun anExpensiveModelIsUsedOnlyOnceTheUserConfirms() = runTest {
        configSet = { params ->
            if (params["confirm_expensive_model"]?.jsonPrimitive?.boolean == true) """{"key":"model","value":"gpt-5"}"""
            else """{"key":"model","confirm_required":true,"confirm_message":"gpt-5 costs $$$"}"""
        }
        setup.open()
        setup.pick("copilot")

        setup.choose("gpt-5")
        assertEquals(Confirm("gpt-5", "gpt-5 costs $$$"), setup.state.value?.confirm)

        setup.choose("gpt-5", confirmed = true)
        assertNull(setup.state.value)
    }

    @Test
    fun aRefusalFromTheCoreIsShownAndTheStepStays() = runTest {
        configSet = { throw CoreError(4001, "config.set model requires a live session") }
        setup.open()
        setup.pick("copilot")

        setup.choose("gpt-5")

        assertEquals("config.set model requires a live session", setup.state.value?.error)
        assertEquals(false, setup.state.value?.busy)
        assertTrue(setup.step() is SetupStep.Models)
    }

    @Test
    fun anEndpointIsSavedItsKeyKeptAndItsModelBecomesTheDefault() = runTest {
        var request: JsonObject? = null
        endpointSaved = { request = it; """{"id":"host-ollama","key_env":"HERMES_CUSTOM_HOST_OLLAMA_API_KEY"}""" }
        setup.open()
        setup.addEndpoint()
        val key = "sk-x".toByteArray()

        setup.saveEndpoint("Host Ollama", "http://127.0.0.1:11434/v1", "gemma4", key)

        assertEquals(listOf("Host Ollama", "http://127.0.0.1:11434/v1", "gemma4", "true"),
                     listOf("name", "base_url", "model", "has_key").map { request!!.getValue(it).jsonPrimitive.content })
        assertArrayEquals("sk-x".toByteArray(), held["HERMES_CUSTOM_HOST_OLLAMA_API_KEY"])
        assertArrayEquals(ByteArray(4), key)
        assertEquals(listOf("save_endpoint", "secrets_changed", "config.set", "reopen_chat"), log.drop(1))
        assertEquals("gemma4 --provider host-ollama --global", core.calls.last().second.getValue("value").jsonPrimitive.content)
        assertNull(setup.state.value)
    }

    @Test
    fun anEndpointWithoutAKeyTouchesNoSecret() = runTest {
        endpointSaved = { """{"id":"host-ollama","key_env":""}""" }
        setup.open()
        setup.addEndpoint()

        setup.saveEndpoint("Host Ollama", "http://127.0.0.1:11434/v1", "gemma4", null)

        assertTrue(held.isEmpty())
        assertEquals(listOf("save_endpoint", "config.set", "reopen_chat"), log.drop(1))
    }

    @Test
    fun anIncompleteEndpointIsRefusedInTheCoresWords() = runTest {
        endpointSaved = { """{"error":"An endpoint needs a name, a model and an http(s) base URL."}""" }
        setup.open()
        setup.addEndpoint()

        setup.saveEndpoint("Host Ollama", "127.0.0.1", "gemma4", "sk-x".toByteArray())

        assertEquals("An endpoint needs a name, a model and an http(s) base URL.", setup.state.value?.error)
        assertTrue(setup.step() is SetupStep.Endpoint)
        assertTrue(held.isEmpty())
        assertEquals(listOf("save_endpoint"), log.drop(1))
    }

    @Test
    fun nousPortalSignsInWithACodeAndThenOffersItsModels() = runTest {
        setup.open()

        val signing = launch { setup.pick("nous") }
        runCurrent()
        val shown = setup.step() as SetupStep.SignIn
        assertEquals("NOUS-1234" to "https://portal.test/device?user_code=NOUS-1234", shown.userCode to shown.url)
        signing.join()

        assertEquals(listOf("nous/hermes-4"), (setup.step() as SetupStep.Models).provider.models)
        assertEquals(listOf("nous.login.start", "nous.login.poll", "nous.login.poll", "model.options"), log.drop(1))
        assertTrue(core.calls.last().second.getValue("refresh").jsonPrimitive.boolean)
    }

    @Test
    fun aSignInThatEndsWithoutApprovalReturnsToTheListWithTheCoresWords() = runTest {
        polls.clear()
        polls += """{"status":"expired","error_message":"The sign-in code expired."}"""
        setup.open()

        setup.pick("nous")

        assertTrue(setup.step() is SetupStep.Providers)
        assertEquals("The sign-in code expired.", setup.state.value?.error)
    }

    @Test
    fun goingBackDuringASignInCancelsIt() = runTest {
        polls.clear()
        repeat(5) { polls += """{"status":"pending"}""" }
        setup.open()
        val signing = launch { setup.pick("nous") }
        runCurrent()

        setup.back()
        signing.join()

        assertEquals("nous.login.cancel", log.last())
        assertEquals("L1", core.calls.last().second.getValue("login_id").jsonPrimitive.content)
        assertTrue(setup.step() is SetupStep.Providers)
    }

    @Test
    fun backReturnsToTheProviderList() = runTest {
        setup.open()
        setup.pick("deepseek")

        setup.back()

        assertTrue(setup.step() is SetupStep.Providers)
    }

    private companion object {
        const val READY_NOUS = """{"slug":"nous","name":"Nous Portal","models":["nous/hermes-4"],"authenticated":true}"""
        const val UNAUTHENTICATED_DEEPSEEK =
            """{"slug":"deepseek","name":"DeepSeek","models":[],"authenticated":false,"auth_type":"api_key","key_env":"DEEPSEEK_API_KEY"}"""
    }
}
