package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.SetupRefusal
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.contract.EndpointProbe
import com.nousresearch.hermes.bridge.contract.ServerKind
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The custom-endpoint catalogue: find, search, list, edit, save and delete the endpoints saved on this phone. */
class EndpointSetupTest : SetupFakes() {
    @Test
    fun anEndpointIsSavedItsKeyKeptAndItsModelBecomesTheDefault() = runTest {
        var request: JsonObject? = null
        endpointSaved = { request = it; """{"id":"host-ollama","key_env":"HERMES_CUSTOM_HOST_OLLAMA_API_KEY","forget":null}""" }
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
        endpointSaved = { """{"id":"host-ollama","key_env":"","forget":"HERMES_CUSTOM_HOST_OLLAMA_API_KEY"}""" }
        disconnect = { throw CoreError(4005, "no credentials found for host-ollama") }
        setup.open()
        setup.addEndpoint()

        setup.saveEndpoint("Host Ollama", "http://127.0.0.1:11434/v1", "gemma4", null)

        assertTrue(held.isEmpty())
        assertEquals(listOf("save_endpoint", "secrets_changed", "model.disconnect", "config.set", "reopen_chat"), log.drop(1))
    }

    @Test
    fun aKeylessSaveGivesBackTheSlotItsEntryNoLongerNames() = runTest {
        held["HERMES_CUSTOM_HOST_OLLAMA_API_KEY"] = "sk-old".toByteArray()
        val names = KeyNames.InMemory().apply { this["host-ollama"] = "HERMES_CUSTOM_HOST_OLLAMA_API_KEY" }
        val setup = ProviderSetup(core, secrets, bridge, session = { "s1" }, reopenChat = { log += "reopen_chat" }, keyNames = names)
        endpointSaved = { """{"id":"host-ollama","key_env":"","forget":"HERMES_CUSTOM_HOST_OLLAMA_API_KEY"}""" }
        var disconnected: String? = null
        disconnect = { params -> disconnected = params["slug"]?.jsonPrimitive?.content; throw CoreError(4005, "no credentials found for host-ollama") }
        configSet = { """{"key":"model","confirm_required":true,"confirm_message":"gemma4 costs $$$"}""" }  // stops on Models
        setup.open()
        setup.addEndpoint()

        setup.saveEndpoint("Host Ollama", "http://127.0.0.1:11434/v1", "gemma4", null)

        assertFalse(held.containsKey("HERMES_CUSTOM_HOST_OLLAMA_API_KEY"))
        assertNull(names["host-ollama"])
        assertEquals(listOf("save_endpoint", "secrets_changed", "model.disconnect", "config.set"), log.drop(1))
        assertEquals("host-ollama", disconnected)
        assertFalse("no Forget offered for a keyless endpoint", (setup.state.value?.step as SetupStep.Models).keyHeld)
    }

    @Test
    fun aSaveWithANewKeyForgetsNothing() = runTest {
        setup.open()
        setup.addEndpoint()

        setup.saveEndpoint("Host Ollama", "http://127.0.0.1:11434/v1", "gemma4", "sk-new".toByteArray())

        assertTrue(log.none { it == "model.disconnect" })
        assertArrayEquals("sk-new".toByteArray(), held["HERMES_CUSTOM_HOST_OLLAMA_API_KEY"])
    }

    @Test
    fun aBareHostIsNotSavedButSentToFindServer() = runTest {
        setup.open()
        setup.addEndpoint()
        val key = "sk-x".toByteArray()

        setup.saveEndpoint("DGX", "spark-98d1", "gemma4", key)

        assertTrue(setup.state.value?.error.orEmpty(), setup.state.value?.error.orEmpty().contains("tap Find server"))
        assertTrue(setup.step() is SetupStep.Endpoint)
        assertArrayEquals(ByteArray(4), key)
        assertTrue(held.isEmpty())
        assertEquals(emptyList<String>(), log.drop(1))
    }

    @Test
    fun anIncompleteEndpointIsRefusedInTheCoresWords() = runTest {
        endpointSaved = { """{"error":"An endpoint needs a name, a model and an http(s) base URL."}""" }
        setup.open()
        setup.addEndpoint()

        setup.saveEndpoint("", "http://127.0.0.1:11434/v1", "gemma4", "sk-x".toByteArray())

        assertEquals("An endpoint needs a name, a model and an http(s) base URL.", setup.state.value?.error)
        assertTrue(setup.step() is SetupStep.Endpoint)
        assertTrue(held.isEmpty())
        assertEquals(listOf("save_endpoint"), log.drop(1))
    }

    @Test
    fun anErrorLeftByAnotherExchangeDoesNotStopTheSavedEndpointBecomingTheDefault() = runTest {
        answers["probe_endpoint"] = """{"found":null,"failure":"unreachable"}"""
        onSave = { setup.findServer("elsewhere", null) }  // an unrelated exchange fails while the save runs
        setup.open()
        setup.addEndpoint()

        setup.saveEndpoint("Host Ollama", "http://127.0.0.1:11434/v1", "gemma4", null)

        assertEquals(listOf("config.set", "reopen_chat"), log.takeLast(2))
        assertNull(setup.state.value)
    }

    @Test
    fun aFailedSaveMakesNothingTheDefaultEvenWhenAnotherExchangeShowsModels() = runTest {
        endpointSaved = { """{"error":"An endpoint needs a name, a model and an http(s) base URL."}""" }
        onSave = { setup.pick("copilot") }  // another exchange moves setup to a provider's models while the save runs
        setup.open()
        setup.addEndpoint()

        setup.saveEndpoint("", "http://127.0.0.1:11434/v1", "gemma4", null)

        // The refusal belongs to the form the user left: the Models step they moved to does not show it.
        assertNull(setup.state.value?.error)
        assertEquals("copilot", (setup.step() as SetupStep.Models).provider.slug)
        assertTrue("config.set" !in log)
    }

    @Test
    fun theProviderListShowsTheSavedEndpoints() = runTest {
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
        setup.open()

        assertEquals(listOf("dgx"), (setup.state.value?.step as SetupStep.Providers).saved.map { it.id })
    }

    @Test
    fun findingAServerFillsItsModels() = runTest {
        answers["probe_endpoint"] = """{"found":{"base_url":"http://spark-98d1:11434/v1","kind":"ollama","models":["gemma4:12b-it-q8_0","gemma3:12b-it-q8_0"]},"failure":null}"""
        setup.open(); setup.addEndpoint()

        setup.findServer("spark-98d1", null)

        val found = (setup.state.value?.step as SetupStep.Endpoint).found
        assertEquals(listOf("gemma4:12b-it-q8_0", "gemma3:12b-it-q8_0"), found?.models)
        assertEquals(ServerKind.OLLAMA, found?.kind)
        assertNull(setup.state.value?.error)
        assertEquals("spark-98d1", argOf("probe_endpoint")["address"]!!.jsonPrimitive.content)
    }

    @Test
    fun aServerFoundOffersTheTypedModelWhenItListsIt() = runTest {
        answers["probe_endpoint"] = """{"found":{"base_url":"http://spark-98d1:11434/v1","kind":"ollama","models":["gemma4:12b-it-q8_0","qwen3"]},"failure":null}"""
        setup.open(); setup.addEndpoint()

        setup.findServer("spark-98d1", null, model = " qwen3 ")

        val form = setup.step() as SetupStep.Endpoint
        assertEquals("qwen3", form.suggestedModel)
        assertEquals(1, form.generation)
        assertFalse("the typed model stays in Kotlin", argOf("probe_endpoint").containsKey("model"))
    }

    @Test
    fun aTypedModelTheServerDoesNotListGivesWayToItsFirst() = runTest {
        answers["probe_endpoint"] = """{"found":{"base_url":"http://192.168.1.20:1234/v1","kind":"lmstudio","models":["gemma4:12b-it-q8_0","qwen3"]},"failure":null}"""
        setup.open(); setup.addEndpoint()

        setup.findServer("192.168.1.20:1234", null, model = "gemm4")  // a typo before Find
        assertEquals("gemma4:12b-it-q8_0", (setup.step() as SetupStep.Endpoint).suggestedModel)

        setup.findServer("192.168.1.20:1234", null, model = "")
        assertEquals("gemma4:12b-it-q8_0", (setup.step() as SetupStep.Endpoint).suggestedModel)
    }

    @Test
    fun eachServerFoundCountsOnceAndAFailedProbeNotAtAll() = runTest {
        answers["probe_endpoint"] = """{"found":{"base_url":"http://a:11434/v1","kind":"ollama","models":["a-model"]},"failure":null}"""
        setup.open(); setup.addEndpoint()

        setup.findServer("a", null)
        assertEquals(1, (setup.step() as SetupStep.Endpoint).generation)
        setup.findServer("a", null)  // the same answer again: an equal server is still a new find
        assertEquals(2, (setup.step() as SetupStep.Endpoint).generation)

        answers["probe_endpoint"] = """{"found":null,"failure":"unreachable"}"""
        setup.findServer("b", null)
        assertEquals(2, (setup.step() as SetupStep.Endpoint).generation)
        assertNull((setup.step() as SetupStep.Endpoint).found)

        subnet = "192.168.1.37/24"
        answers["discover_endpoints"] = """{"found":[{"base_url":"http://192.168.1.20:11434/v1","kind":"ollama","models":["m1"]}]}"""
        setup.searchNetwork()
        val form = setup.step() as SetupStep.Endpoint
        assertEquals(1, form.scan?.size)
        assertEquals(2, form.generation)
    }

    @Test
    fun anEditedEndpointOffersItsOwnModelWhenTheServerListsIt() = runTest {
        answers["list_custom_endpoints"] = listing(dgx)
        answers["probe_endpoint"] = """{"found":{"base_url":"http://spark-98d1:11434/v1","kind":"ollama","models":["llama3","gemma4:12b-it-q8_0"]},"failure":null}"""
        setup.open(); setup.editEndpoint("dgx")

        setup.findServer("spark-98d1", null, model = "gemma4:12b-it-q8_0")

        val form = setup.step() as SetupStep.Endpoint
        assertEquals("dgx", form.editing?.id)
        assertEquals("gemma4:12b-it-q8_0", form.suggestedModel)
    }

    @Test
    fun aRefusingServerIsExplainedAndTheTypedKeyIsWiped() = runTest {
        answers["probe_endpoint"] = """{"found":null,"failure":"refused"}"""
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
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
        held["HERMES_CUSTOM_DGX_API_KEY"] = "k".toByteArray()
        endpointSaved = { """{"id":"dgx","key_env":"HERMES_CUSTOM_DGX_API_KEY","forget":null}""" }
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
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
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
        answers["probe_endpoint"] = """{"found":{"base_url":"http://a:11434/v1","kind":"ollama","models":["a-model"]},"failure":null}"""
        setup.open(); setup.addEndpoint()
        setup.findServer("a", null)
        answers["probe_endpoint"] = """{"found":null,"failure":"unreachable"}"""

        setup.findServer("b", null)

        assertNull("b could be saved with a's model", (setup.step() as SetupStep.Endpoint).found)
        assertTrue(setup.state.value?.error!!.startsWith("Nothing answered"))
    }

    @Test
    fun aProbeReplyThatIsBothFoundAndFailedIsABugNotAServer() = runTest {
        answers["probe_endpoint"] = """{"found":{"base_url":"http://a:11434/v1","kind":"ollama","models":["a-model"]},"failure":"refused"}"""
        setup.open(); setup.addEndpoint()

        setup.findServer("a", null)

        assertEquals("Something went wrong (IllegalStateException).", setup.state.value?.error)
        assertNull((setup.step() as SetupStep.Endpoint).found)
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
        assertEquals(false, setup.state.value?.busy)
    }

    @Test
    fun aServerFoundAfterSetupWasClosedAndOpenedAgainIsNotShown() = runTest {
        answers["probe_endpoint"] = """{"found":{"base_url":"http://spark-98d1:11434/v1","kind":"ollama","models":["m1"]},"failure":null}"""
        val answer = CompletableDeferred<Unit>()
        beforeBridge = { if (it == "probe_endpoint") answer.await() }
        setup.open(); setup.addEndpoint()
        val finding = launch { setup.findServer("spark-98d1", null) }
        runCurrent()
        assertEquals(true, setup.state.value?.busy)

        setup.close(); setup.open()
        answer.complete(Unit)
        finding.join()

        assertTrue(setup.step().toString(), setup.step() is SetupStep.Providers)
        assertEquals(false, setup.state.value?.busy)
        assertNull(setup.state.value?.error)
    }

    @Test
    fun aSearchThatAnswersOnAFormOpenedSinceIsNotShown() = runTest {
        subnet = "192.168.1.37/24"
        answers["discover_endpoints"] = """{"found":[{"base_url":"http://192.168.1.20:11434/v1","kind":"ollama","models":["m1"]}]}"""
        val answer = CompletableDeferred<Unit>()
        beforeBridge = { if (it == "discover_endpoints") answer.await() }
        setup.open(); setup.addEndpoint()
        val searching = launch { setup.searchNetwork() }
        runCurrent()

        setup.close(); setup.open(); setup.addEndpoint()  // a fresh form, equal to the one the search started on
        answer.complete(Unit)
        searching.join()

        val form = setup.step() as SetupStep.Endpoint
        assertNull(form.scan)
        assertFalse(form.scanning)
        assertEquals(false, setup.state.value?.busy)
    }

    @Test
    fun aRefusalThatAnswersAfterTheUserWentBackIsNotShown() = runTest {
        answers["probe_endpoint"] = """{"found":null,"failure":"unreachable"}"""
        val answer = CompletableDeferred<Unit>()
        beforeBridge = { if (it == "probe_endpoint") answer.await() }
        setup.open(); setup.addEndpoint()
        val finding = launch { setup.findServer("spark-98d1", null) }
        runCurrent()

        setup.back()
        answer.complete(Unit)
        finding.join()

        assertTrue(setup.step().toString(), setup.step() is SetupStep.Providers)
        assertNull(setup.state.value?.error)
    }

    @Test
    fun aSaveThatAnswersAfterTheUserLeftMakesNothingTheDefault() = runTest {
        val answer = CompletableDeferred<Unit>()
        onSave = { answer.await() }
        setup.open(); setup.addEndpoint()
        val saving = launch { setup.saveEndpoint("Host Ollama", "http://127.0.0.1:11434/v1", "gemma4", null) }
        runCurrent()

        setup.close(); setup.open()
        answer.complete(Unit)
        saving.join()

        assertTrue("save_endpoint" in log)  // saved all the same: listed the next time
        assertTrue("config.set" !in log)
        assertTrue("reopen_chat" !in log)
        assertTrue(setup.step().toString(), setup.step() is SetupStep.Providers)
        assertEquals(false, setup.state.value?.busy)
    }

    @Test
    fun aCancelledProbeLeavesTheFormUsable() = runTest {
        beforeBridge = { if (it == "probe_endpoint") awaitCancellation() }
        setup.open(); setup.addEndpoint()
        val finding = launch { setup.findServer("spark-98d1", null) }
        runCurrent()
        assertEquals(true, setup.state.value?.busy)

        finding.cancel()
        finding.join()

        assertEquals(false, setup.state.value?.busy)
        assertTrue(setup.step() is SetupStep.Endpoint)
    }

    @Test
    fun deletingTheDefaultEndpointStartsTheChatAfreshOnTheProviderList() = runTest {
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
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
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":false}"""
        setup.open()

        setup.deleteEndpoint("dgx")

        assertTrue("reopen_chat" !in log)
        assertFalse((setup.step() as SetupStep.Providers).defaultGone)
    }

    @Test
    fun aDeleteWhoseKeyCouldNotBeForgottenAtTheCoreSaysSoAndNoLongerListsTheEndpoint() = runTest {
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":false}"""
        disconnect = { throw CoreError(5035, "HERMES_CUSTOM_DGX_API_KEY is managed elsewhere") }
        setup.open()

        setup.deleteEndpoint("dgx")

        assertEquals("HERMES_CUSTOM_DGX_API_KEY is managed elsewhere", setup.state.value?.error)
        assertEquals(emptyList<String>(), (setup.step() as SetupStep.Providers).saved.map { it.id })
    }

    @Test
    fun aDefaultDeletedWhileItsKeyCouldNotBeForgottenStillSaysNoDefaultIsLeft() = runTest {
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":true}"""
        disconnect = { throw CoreError(5035, "HERMES_CUSTOM_DGX_API_KEY is managed elsewhere") }
        setup.open()

        setup.deleteEndpoint("dgx")

        assertTrue((setup.step() as SetupStep.Providers).defaultGone)
    }

    @Test
    fun theListKeepsSayingTheDefaultIsGoneUntilSetupIsOpenedAgain() = runTest {
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
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

    @Test
    fun aSlotNoEntryNamesIsForgottenWhenSetupLists() = runTest {
        held["HERMES_CUSTOM_GONE_API_KEY"] = "orphan".toByteArray()
        held["HERMES_CUSTOM_DGX_API_KEY"] = "named".toByteArray()
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))

        setup.open()

        assertFalse(held.containsKey("HERMES_CUSTOM_GONE_API_KEY"))
        assertTrue(held.containsKey("HERMES_CUSTOM_DGX_API_KEY"))
        assertEquals(1, log.count { it == "secrets_changed" })
        assertTrue(log.none { it == "model.disconnect" })  // a slot does not name its slug
    }

    @Test
    fun aSlotLeftByAFailedForgetIsGoneAtTheNextListing() = runTest {
        held["HERMES_CUSTOM_DGX_API_KEY"] = "k".toByteArray()
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":false}"""
        setup.open()
        failDeletes = 2  // the delete's forget, then the listing's retry right after it

        setup.deleteEndpoint("dgx")

        assertTrue(setup.state.value?.error != null)
        assertEquals(emptyList<String>(), (setup.step() as SetupStep.Providers).saved.map { it.id })
        assertTrue("the keystore still fails", held.containsKey("HERMES_CUSTOM_DGX_API_KEY"))

        setup.close()
        setup.open()

        assertFalse(held.containsKey("HERMES_CUSTOM_DGX_API_KEY"))
    }

    @Test
    fun theReconcileNeverTouchesOtherSecrets() = runTest {
        val others = listOf("DEEPSEEK_API_KEY", "TERMINAL_SSH_PRIVATE_KEY", "TELEGRAM_BOT_TOKEN",
                            "HERMES_CUSTOMER_API_KEY", "HERMES_CUSTOM_DGX_TOKEN")
        others.forEach { held[it] = "x".toByteArray() }
        answers["list_custom_endpoints"] = listing()

        setup.open()

        assertEquals(others.toSet(), held.keys)
        assertTrue(log.none { it == "secrets_changed" })
    }

    @Test
    fun aListingThatFailsForgetsNothing() = runTest {
        held["HERMES_CUSTOM_GONE_API_KEY"] = "orphan".toByteArray()  // no list answer: the bridge call fails

        setup.open()

        assertTrue(held.containsKey("HERMES_CUSTOM_GONE_API_KEY"))
    }

    @Test
    fun aSlotStoredWhileTheListIsReadIsKept() = runTest {
        onList = { held["HERMES_CUSTOM_NEW_API_KEY"] = "k".toByteArray() }  // a save landing after config was read
        answers["list_custom_endpoints"] = listing()

        setup.open()

        assertTrue(held.containsKey("HERMES_CUSTOM_NEW_API_KEY"))
    }

    // EndpointSetup itself: a delete settles blueprint §2 CustomEndpoint (d) in one place, in its order.
    private val effects = mutableListOf<String>()
    private var forgetting: suspend (String, String) -> Unit = { slug, keyEnv -> effects += "forget $slug $keyEnv" }
    private val endpoints = EndpointSetup(bridge, secrets, subnet = { subnet },
                                          keep = { slug, keyEnv, _ -> effects += "keep $slug $keyEnv" },
                                          forget = { slug, keyEnv -> forgetting(slug, keyEnv) },
                                          onDefaultGone = { effects += "default gone" })

    /** What [action] threw, if anything. */
    private suspend fun failureOf(action: suspend () -> Unit): Exception? =
        try { action(); null } catch (failed: Exception) { failed }

    @Test
    fun aDeleteForgetsTheSlotThenReopensTheChatWhenItWasTheDefault() = runTest {
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":true}"""

        val deletion = endpoints.delete("dgx")

        assertEquals(listOf("forget dgx HERMES_CUSTOM_DGX_API_KEY", "default gone"), effects)
        assertEquals(Deletion(wasDefault = true), deletion)
        assertNull(deletion.notForgotten)
    }

    @Test
    fun aDeleteWhoseForgetFailsStillReopensTheChatAndHandsBackTheFailure() = runTest {
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":true}"""
        val failure = IllegalStateException("keystore unavailable")
        forgetting = { _, _ -> throw failure }

        val deletion = endpoints.delete("dgx")

        assertTrue("default gone" in effects)
        assertTrue(deletion.wasDefault)
        assertSame(failure, deletion.notForgotten)
    }

    @Test
    fun aCancelledForgetIsNotAFailedOne() = runTest {
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":false}"""
        val cancelled = CancellationException("setup closed")
        forgetting = { _, _ -> throw cancelled }

        assertSame(cancelled, failureOf { endpoints.delete("dgx") })
    }

    @Test
    fun theFormsRefusalsAreTheAppsOwnWords() = runTest {
        val key = "sk-x".toByteArray()
        val bareHost = failureOf { endpoints.save(SetupStep.Endpoint(), "DGX", "spark-98d1", "gemma4", key) }
        assertTrue(bareHost.toString(), bareHost is SetupRefusal && "tap Find server" in bareHost.message.orEmpty())
        assertArrayEquals(ByteArray(4), key)
        assertEquals(emptyList<Pair<String, String>>(), sent)

        answers["probe_endpoint"] = """{"found":null,"failure":"refused"}"""
        val probed = failureOf { endpoints.find(SetupStep.Endpoint(), "spark-98d1.tailf0dece.ts.net", null) }
        assertTrue(probed.toString(), probed is SetupRefusal)
        assertEquals(problem(EndpointProbe.REFUSED), probed?.message)

        val searched = failureOf { endpoints.search(SetupStep.Endpoint()) }  // no Wi-Fi: subnet is null
        assertTrue(searched.toString(), searched is SetupRefusal)
        assertEquals("Connect to a Wi-Fi network to search it.", searched?.message)
    }
}
