package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setting up a provider: the core lists them, the key goes to the keystore, the chosen model becomes the default. */
class ProviderSetupTest : SetupFakes() {
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
        assertEquals("DeepSeek did not accept the key.", setup.state.value?.error)
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
    fun theModelsCarryTheirFactsFromTheCoresList() = runTest {
        copilotRow = """{"slug":"copilot","name":"GitHub Copilot","models":["gpt-5"],"authenticated":true,
            "pricing":{"gpt-5":{"input":"$1.25","output":"$10.00","free":false}},"warning":"Copilot is slow today"}"""
        setup.open()

        setup.pick("copilot")

        val facts = (setup.step() as SetupStep.Models).provider.facts
        assertEquals("$1.25", facts.models["gpt-5"]?.input)
        assertEquals("Copilot is slow today", facts.warning)
    }

    @Test
    fun aLockedModelIsNeverChosen() = runTest {
        copilotRow = """{"slug":"copilot","name":"GitHub Copilot","models":["gpt-5","gpt-5-pro"],"authenticated":true,
            "unavailable_models":["gpt-5-pro"]}"""
        setup.open()
        setup.pick("copilot")

        setup.choose("gpt-5-pro")

        assertTrue(log.toString(), "config.set" !in log)
        assertTrue(setup.step() is SetupStep.Models)
        assertNull(setup.state.value?.error)
    }

    @Test
    fun aTypedIdIsChosenAsAnyModelIsAndKeptOnceTheCoreTookIt() = runTest {
        setup.open()
        setup.pick("copilot")

        setup.chooseCustom(" gpt-6-preview ")

        val params = core.calls.last { it.first == "config.set" }.second
        assertEquals(listOf("s1", "model", "gpt-6-preview --provider copilot --global"),
                     listOf("session_id", "key", "value").map { params.getValue(it).jsonPrimitive.content })
        assertEquals("reopen_chat", log.last())
        assertNull(setup.state.value)
        assertEquals(listOf("gpt-6-preview"), customs["copilot"])

        setup.open()
        setup.pick("copilot")
        val provider = (setup.step() as SetupStep.Models).provider
        assertEquals(listOf("gpt-6-preview"), provider.custom)
        assertEquals(listOf("gpt-5"), provider.models)
    }

    @Test
    fun anIdTheCoreRefusesIsNotKept() = runTest {
        configSet = { throw CoreError(5001, "Model `gpt-6` was not found in this provider's catalog.") }
        setup.open()
        setup.pick("copilot")

        setup.chooseCustom("gpt-6")

        assertEquals("Model `gpt-6` was not found in this provider's catalog.", setup.state.value?.error)
        assertTrue(setup.step() is SetupStep.Models)
        assertEquals("refused", emptyList<String>(), customs["copilot"])

        // The expensive-model answer: nothing is taken while the confirm shows, and the confirmed id is.
        configSet = { params ->
            if (params["confirm_expensive_model"]?.jsonPrimitive?.boolean == true) """{"key":"model","value":"gpt-6"}"""
            else """{"key":"model","confirm_required":true,"confirm_message":"gpt-6 costs $$$"}"""
        }
        setup.chooseCustom("gpt-6")
        assertEquals(Confirm("gpt-6", "gpt-6 costs $$$"), setup.state.value?.confirm)
        assertEquals("asked to confirm", emptyList<String>(), customs["copilot"])

        setup.choose("gpt-6", confirmed = true)
        assertNull(setup.state.value)
        assertEquals(listOf("gpt-6"), customs["copilot"])
    }

    @Test
    fun anIdWithASpaceNeverReachesTheCore() = runTest {
        setup.open()
        setup.pick("copilot")

        setup.chooseCustom("gpt 6")
        assertTrue(log.toString(), "config.set" !in log)
        assertEquals("A model id has no spaces.", setup.state.value?.error)

        setup.back(); setup.pick("copilot")
        setup.chooseCustom("   ")
        assertTrue(log.toString(), "config.set" !in log)
        assertNull(setup.state.value?.error)
    }

    @Test
    fun aCoreWarningOnATakenIdIsShownUntilDone() = runTest {
        configSet = { """{"key":"model","value":"gpt-6","warning":"Model `gpt-6` not found in listing; using it anyway"}""" }
        setup.open()
        setup.pick("copilot")

        setup.chooseCustom("gpt-6")

        assertEquals("Model `gpt-6` not found in listing; using it anyway", setup.state.value?.notice)
        assertEquals(listOf("gpt-6"), (setup.step() as SetupStep.Models).provider.custom)
        assertEquals("reopen_chat", log.last())
        assertEquals(listOf("gpt-6"), customs["copilot"])
        setup.close()
        assertNull(setup.state.value)

        // A model the catalog lists closes setup as before, whatever the core notes.
        setup.open()
        setup.pick("copilot")
        setup.choose("gpt-5")
        assertNull(setup.state.value)
    }

    @Test
    fun aListedIdIsNeverKeptAsCustomAndShowsOnce() = runTest {
        setup.open()
        setup.pick("copilot")
        setup.choose("gpt-5")
        assertEquals(emptyList<String>(), customs["copilot"])

        customs.add("copilot", "gpt-5")  // kept before the catalog listed it
        setup.open()
        setup.pick("copilot")
        assertEquals(emptyList<String>(), (setup.step() as SetupStep.Models).provider.custom)
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
    fun openingSetupStartsFromAFreshList() = runTest {
        answers["list_custom_endpoints"] = listing(dgx, keyEnvs = listOf("HERMES_CUSTOM_DGX_API_KEY"))
        answers["delete_custom_endpoint"] = """{"key_env":"HERMES_CUSTOM_DGX_API_KEY","was_default":true}"""
        setup.open()
        setup.deleteEndpoint("dgx")
        assertTrue((setup.step() as SetupStep.Providers).defaultGone)

        setup.open()  // again, without closing: nothing of the last list is kept

        assertFalse((setup.step() as SetupStep.Providers).defaultGone)
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

    @Test
    fun aScreenOpenedDuringAnotherExchangeIsNotBusy() = runTest {
        answers["probe_endpoint"] = """{"found":{"base_url":"http://spark-98d1:11434/v1","kind":"ollama","models":["m1"]},"failure":null}"""
        val answer = CompletableDeferred<Unit>()
        beforeBridge = { if (it == "probe_endpoint") answer.await() }
        setup.open(); setup.addEndpoint()
        val finding = launch { setup.findServer("spark-98d1", null) }
        runCurrent()

        setup.pick("copilot")
        assertEquals(false, setup.state.value?.busy)

        answer.complete(Unit)
        finding.join()
        assertEquals("copilot", (setup.step() as SetupStep.Models).provider.slug)
        assertEquals(false, setup.state.value?.busy)
        assertNull(setup.state.value?.error)
    }

    @Test
    fun aSignInStartedAfterTheUserLeftIsCancelledAtTheCore() = runTest {
        onCoreCall = { if (it == "nous.login.start") setup.back() }  // the user leaves while the core starts it
        setup.open()

        setup.pick("nous")

        assertEquals("nous.login.cancel", log.last())
        assertEquals("L1", core.calls.last().second.getValue("login_id").jsonPrimitive.content)
        assertTrue("nous.login.poll" !in log)
        assertTrue(setup.step().toString(), setup.step() is SetupStep.Providers)
        assertEquals(false, setup.state.value?.busy)
    }

    @Test
    fun aSignInThatExpiresAfterTheUserLeftDoesNotMoveSetup() = runTest {
        polls.clear()
        polls += """{"status":"expired","error_message":"The sign-in code expired."}"""
        onCoreCall = { if (it == "nous.login.poll") setup.addEndpoint() }  // the user leaves while the core polls
        setup.open()

        setup.pick("nous")

        assertTrue(setup.step().toString(), setup.step() is SetupStep.Endpoint)
        assertNull(setup.state.value?.error)
    }
}
