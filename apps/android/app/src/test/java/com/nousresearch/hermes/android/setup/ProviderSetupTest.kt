package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.SecretStore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setting up a provider: the core lists them, the key goes to the keystore, the chosen model becomes the default. */
class ProviderSetupTest {
    private val answers = mutableMapOf<String, String>()   // bridge calls answered by these tests
    private val sent = mutableListOf<Pair<String, String>>()
    private var subnet: String? = null
    private var subnetFound: suspend () -> String? = { subnet }  // the shell's Wi-Fi adapter
    private fun argOf(function: String) = Json.parseToJsonElement(sent.last { it.first == function }.second).jsonObject
    private val held = mutableMapOf<String, ByteArray>()
    private val secrets = object : SecretStore {
        override fun put(name: String, value: ByteArray) { held[name] = value.copyOf() }
        override fun get(name: String) = held[name]?.copyOf()  // a fresh copy, as the keystore decrypts one
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
    private val bridge = AppBridge { function, request ->
        if (function == "delete_custom_endpoint") dropListed(Json.parseToJsonElement(request).jsonObject.getValue("id").jsonPrimitive.content)
        answers[function]?.let { sent += function to request; return@AppBridge it }
        when (function) {
            "secrets_changed" -> { secretsChanged(); "{}" }
            "save_custom_endpoint" -> {
                sent += function to request
                log += "save_endpoint"
                endpointSaved(Json.parseToJsonElement(request).jsonObject)
            }
            else -> error("unexpected bridge call $function")
        }
    }
    private val setup = ProviderSetup(core, secrets, bridge, session = { "s1" }, reopenChat = { log += "reopen_chat" },
                                    subnet = { subnetFound() })

    private fun ProviderSetup.step() = state.value?.step

    /** As the Python half does: a deleted endpoint is no longer listed. */
    private fun dropListed(id: String) {
        val listed = answers["list_custom_endpoints"] ?: return
        val kept = Json.parseToJsonElement(listed).jsonObject.getValue("endpoints").jsonArray
            .filter { it.jsonObject.getValue("id").jsonPrimitive.content != id }
        answers["list_custom_endpoints"] = JsonObject(mapOf("endpoints" to JsonArray(kept))).toString()
    }

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

    @Test
    fun aKeyHeldOnThePhoneCanBeForgottenFromItsProvidersModels() = runTest {
        setup.open()
        setup.pick("deepseek")
        deepseekRow = SIGNED_IN_DEEPSEEK  // as the core lists a configured provider: no key_env
        setup.submitKey("sk-held".toByteArray())  // the app keeps which variable it stored the key under
        assertTrue((setup.step() as SetupStep.Models).keyHeld)
        log.clear()
        disconnect = { deepseekRow = UNAUTHENTICATED_DEEPSEEK; """{"slug":"deepseek","name":"DeepSeek","disconnected":true}""" }

        setup.forgetKey()

        assertNull(held["DEEPSEEK_API_KEY"])
        assertEquals(listOf("secrets_changed", "model.disconnect", "model.options"), log)
        val listed = (setup.step() as SetupStep.Providers).choices.first { it.slug == "deepseek" }
        assertEquals(false, listed.ready)
    }

    @Test
    fun aProviderSignedInWithoutAKeyOnThePhoneOffersNothingToForget() = runTest {
        // Signed in by a key the keystore does not hold (another secret source, or an older .env).
        deepseekRow = SIGNED_IN_DEEPSEEK  // as the core lists a configured provider: no key_env
        setup.open()

        setup.pick("deepseek")
        setup.forgetKey()

        assertEquals(false, (setup.step() as SetupStep.Models).keyHeld)
        assertTrue(log.none { it == "model.disconnect" })
    }

    private val dgx = """{"id":"dgx","name":"DGX","base_url":"http://spark-98d1:11434/v1","model":"gemma4:12b-it-q8_0","has_key":true}"""

    @Test
    fun theProviderListShowsTheSavedEndpoints() = runTest {
        answers["list_custom_endpoints"] = """{"endpoints":[$dgx]}"""
        setup.open()

        assertEquals(listOf("dgx"), (setup.state.value?.step as SetupStep.Providers).saved.map { it.id })
    }

    @Test
    fun findingAServerFillsItsModels() = runTest {
        answers["probe_endpoint"] = """{"outcome":"found","base_url":"http://spark-98d1:11434/v1","kind":"ollama","models":["gemma4:12b-it-q8_0","gemma3:12b-it-q8_0"]}"""
        setup.open(); setup.addEndpoint()

        setup.findServer("spark-98d1", null)

        assertEquals(listOf("gemma4:12b-it-q8_0", "gemma3:12b-it-q8_0"), (setup.state.value?.step as SetupStep.Endpoint).probe?.models)
        assertNull(setup.state.value?.error)
        assertEquals("spark-98d1", argOf("probe_endpoint")["address"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRefusingServerIsExplainedAndTheTypedKeyIsWiped() = runTest {
        answers["probe_endpoint"] = """{"outcome":"refused","base_url":null,"kind":null,"models":[]}"""
        setup.open(); setup.addEndpoint()
        val key = "sk-x".toByteArray()

        setup.findServer("spark-98d1.tailf0dece.ts.net", key)

        assertTrue(setup.state.value?.error!!.contains("short name"))
        assertEquals("the typed key reaches the probe", "sk-x", argOf("probe_endpoint")["api_key"]!!.jsonPrimitive.content)
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test
    fun searchingTheNetworkListsWhatItFound() = runTest {
        subnet = "192.168.1.37/24"
        answers["discover_endpoints"] = """{"found":[{"base_url":"http://192.168.1.20:11434/v1","kind":"ollama","models":["m1"]}]}"""
        setup.open(); setup.addEndpoint()

        setup.searchNetwork()

        assertEquals("192.168.1.37/24", argOf("discover_endpoints")["cidr"]!!.jsonPrimitive.content)
        assertEquals(listOf("http://192.168.1.20:11434/v1"), (setup.state.value?.step as SetupStep.Endpoint).scan?.map { it.baseUrl })
    }

    @Test
    fun anEditKeepsItsIdAndItsKeyWhenNoNewKeyIsTyped() = runTest {
        answers["list_custom_endpoints"] = """{"endpoints":[$dgx]}"""
        held["HERMES_CUSTOM_DGX_API_KEY"] = "k".toByteArray()
        endpointSaved = { """{"id":"dgx","key_env":"HERMES_CUSTOM_DGX_API_KEY"}""" }
        setup.open()
        setup.editEndpoint("dgx")

        setup.saveEndpoint("DGX", "http://spark-98d1:11434/v1", "gemma3:12b-it-q8_0", null)

        val sentSave = argOf("save_custom_endpoint")
        assertEquals("dgx", sentSave["id"]!!.jsonPrimitive.content)
        // has_key means "a NEW key is in the keystore": an edit without one sends false and the
        // Python half keeps the entry's key_env (Task 4 fix b2b4a37221).
        assertEquals(false, sentSave["has_key"]!!.jsonPrimitive.boolean)
        assertTrue("the held key is untouched", held.containsKey("HERMES_CUSTOM_DGX_API_KEY"))
    }

    @Test
    fun deletingAnEndpointForgetsItsKeyAndDisconnectsIt() = runTest {
        answers["list_custom_endpoints"] = """{"endpoints":[$dgx]}"""
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":false}"""
        held["HERMES_CUSTOM_DGX_API_KEY"] = "k".toByteArray()
        var disconnected: String? = null
        disconnect = { params -> disconnected = params["slug"]?.jsonPrimitive?.content; """{"slug":"dgx","name":"DGX","disconnected":true}""" }
        var heldAtReload: Boolean? = null
        secretsChanged = { log += "secrets_changed"; heldAtReload = held.containsKey("HERMES_CUSTOM_DGX_API_KEY") }
        setup.open()

        setup.deleteEndpoint("dgx")

        assertFalse(held.containsKey("HERMES_CUSTOM_DGX_API_KEY"))
        assertEquals("the keystore lets go before the core re-reads it", false, heldAtReload)
        assertEquals("dgx", disconnected)
        assertEquals(emptyList<String>(), (setup.step() as SetupStep.Providers).saved.map { it.id })
    }

    @Test
    fun aFailedProbeDropsTheModelsAnEarlierAddressListed() = runTest {
        answers["probe_endpoint"] = """{"outcome":"found","base_url":"http://a:11434/v1","kind":"ollama","models":["a-model"]}"""
        setup.open(); setup.addEndpoint()
        setup.findServer("a", null)
        answers["probe_endpoint"] = """{"outcome":"unreachable","base_url":null,"kind":null,"models":[]}"""

        setup.findServer("b", null)

        assertNull("b could be saved with a's model", (setup.step() as SetupStep.Endpoint).probe)
        assertTrue(setup.state.value?.error!!.startsWith("Nothing answered"))
    }

    @Test
    fun aKeyTypedOutsideTheFormIsStillWiped() = runTest {
        setup.open()  // the providers list, not the endpoint form
        val probing = "sk-a".toByteArray()
        val saving = "sk-b".toByteArray()

        setup.findServer("spark-98d1", probing)
        setup.saveEndpoint("DGX", "http://spark-98d1:11434/v1", "m", saving)

        assertArrayEquals(ByteArray(4), probing)
        assertArrayEquals(ByteArray(4), saving)
    }

    @Test
    fun aSearchThatFindsNothingSaysSoAndStopsScanning() = runTest {
        subnet = "192.168.1.37/24"
        answers["discover_endpoints"] = """{"found":[]}"""
        setup.open(); setup.addEndpoint()

        setup.searchNetwork()

        assertEquals("No model server answered on this network.", setup.state.value?.error)
        assertEquals(false, (setup.step() as SetupStep.Endpoint).scanning)
    }

    @Test
    fun aSearchWithoutWiFiSaysSoAndStopsScanning() = runTest {
        setup.open(); setup.addEndpoint()

        setup.searchNetwork()

        assertEquals("Connect to a Wi-Fi network to search it.", setup.state.value?.error)
        assertEquals(false, (setup.step() as SetupStep.Endpoint).scanning)
    }

    @Test
    fun aFailingWiFiAdapterIsShownByItsKind() = runTest {
        subnetFound = { throw IllegalStateException("no ConnectivityManager") }
        setup.open(); setup.addEndpoint()

        setup.searchNetwork()

        assertEquals("Something went wrong (IllegalStateException).", setup.state.value?.error)
        assertEquals(false, (setup.step() as SetupStep.Endpoint).scanning)
    }

    @Test
    fun aCancelledSearchStopsScanning() = runTest {
        subnetFound = { awaitCancellation() }
        setup.open(); setup.addEndpoint()
        val searching = launch { setup.searchNetwork() }
        runCurrent()
        assertEquals(true, (setup.step() as SetupStep.Endpoint).scanning)

        searching.cancel()
        searching.join()

        assertEquals(false, (setup.step() as SetupStep.Endpoint).scanning)
    }

    @Test
    fun deletingTheDefaultEndpointStartsTheChatAfreshOnTheProviderList() = runTest {
        answers["list_custom_endpoints"] = """{"endpoints":[$dgx]}"""
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":true}"""
        setup.open()

        setup.deleteEndpoint("dgx")

        // The live session keeps the model it was opened with (L11), and the core has detached it.
        assertEquals("reopen_chat", log.last { it == "reopen_chat" })
        assertEquals(emptyList<String>(), (setup.step() as SetupStep.Providers).saved.map { it.id })
        // No default model is left: the list says so instead of looking as if nothing happened.
        assertTrue((setup.step() as SetupStep.Providers).defaultGone)
    }

    @Test
    fun deletingAnotherEndpointLeavesTheChatAlone() = runTest {
        answers["list_custom_endpoints"] = """{"endpoints":[$dgx]}"""
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":false}"""
        setup.open()

        setup.deleteEndpoint("dgx")

        assertTrue("reopen_chat" !in log)
        assertFalse((setup.step() as SetupStep.Providers).defaultGone)
    }

    @Test
    fun aDeleteWhoseKeyCouldNotBeForgottenAtTheCoreSaysSoAndNoLongerListsTheEndpoint() = runTest {
        answers["list_custom_endpoints"] = """{"endpoints":[$dgx]}"""
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":false}"""
        disconnect = { throw CoreError(5035, "HERMES_CUSTOM_DGX_API_KEY is managed elsewhere") }
        setup.open()

        setup.deleteEndpoint("dgx")

        assertEquals("HERMES_CUSTOM_DGX_API_KEY is managed elsewhere", setup.state.value?.error)
        assertEquals(emptyList<String>(), (setup.step() as SetupStep.Providers).saved.map { it.id })
    }

    @Test
    fun aDefaultDeletedWhileItsKeyCouldNotBeForgottenStillSaysNoDefaultIsLeft() = runTest {
        answers["list_custom_endpoints"] = """{"endpoints":[$dgx]}"""
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":true}"""
        disconnect = { throw CoreError(5035, "HERMES_CUSTOM_DGX_API_KEY is managed elsewhere") }
        setup.open()

        setup.deleteEndpoint("dgx")

        assertTrue((setup.step() as SetupStep.Providers).defaultGone)
    }

    @Test
    fun theListKeepsSayingTheDefaultIsGoneUntilSetupIsOpenedAgain() = runTest {
        answers["list_custom_endpoints"] = """{"endpoints":[$dgx]}"""
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":true}"""
        setup.open()
        setup.deleteEndpoint("dgx")

        setup.addEndpoint()
        setup.back()
        assertTrue((setup.step() as SetupStep.Providers).defaultGone)

        setup.close()
        setup.open()
        assertFalse((setup.step() as SetupStep.Providers).defaultGone)
    }

    private companion object {
        const val READY_NOUS = """{"slug":"nous","name":"Nous Portal","models":["nous/hermes-4"],"authenticated":true}"""
        const val SIGNED_IN_DEEPSEEK = """{"slug":"deepseek","name":"DeepSeek","models":["deepseek-v4-pro"],"authenticated":true}"""
        const val UNAUTHENTICATED_DEEPSEEK =
            """{"slug":"deepseek","name":"DeepSeek","models":[],"authenticated":false,"auth_type":"api_key","key_env":"DEEPSEEK_API_KEY"}"""
    }
}
