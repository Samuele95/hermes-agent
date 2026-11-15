package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.settings.ConnectedProvider
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.Method
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setting up a provider: the core lists them, the key goes to the keystore, the chosen model becomes the default. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: a launched check runs as it is launched
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

    // Readiness (F6): whether the provider the core is set up for will answer, as setup.runtime_check says.

    private fun checks() = core.calls.count { it.first == "setup.runtime_check" }

    private val noCredentials = """{"ok":false,"provider":"deepseek","error":"No usable credentials found for deepseek."}"""

    @Test
    fun theCheckAsksTheCoreForTheLaunchProfileAndShowsCheckingUntilItAnswers() = runTest {
        setup.checkReadiness()
        assertEquals(Readiness.Ready("copilot", "gpt-5"), setup.readiness.value)
        var during: Readiness? = null
        onCoreCall = { if (it == "setup.runtime_check") during = setup.readiness.value }

        setup.checkReadiness()  // a second check: Ready gives way to Checking while the core answers

        assertEquals(Readiness.Checking, during)
        assertTrue("neither provider nor profile", core.calls.filter { it.first == "setup.runtime_check" }.all { it.second.isEmpty() })
        assertEquals(Readiness.Ready("copilot", "gpt-5"), setup.readiness.value)
        assertFalse(log.toString(), "setup.status" in log)
    }

    @Test
    fun aRefusalAsksSetupStatusWhichTellsNeedsSetupFromUnavailable() = runTest {
        runtimeCheck = { noCredentials }

        setup.checkReadiness()
        assertEquals(Readiness.NeedsSetup("No usable credentials found for deepseek."), setup.readiness.value)
        assertEquals(1, log.count { it == "setup.status" })

        configured = true
        setup.checkReadiness()
        assertEquals(Readiness.Unavailable("No usable credentials found for deepseek."), setup.readiness.value)
        assertEquals(2, log.count { it == "setup.status" })
    }

    @Test
    fun aCheckThatDidNotFinishIsUnknownNeverUnavailable() = runTest {
        configured = true  // a provider is set up throughout: a "no" would read Unavailable
        val unfinished = listOf<(JsonObject) -> String>(
            { throw CoreError(5097, "readiness check still in progress; retrying next tick") },
            { throw CoreError(5016, "boom") },
            { """{"error":"x"}""" },  // no ok: an answer the app cannot read
        )
        for ((i, answer) in unfinished.withIndex()) {
            runtimeCheck = answer
            setup.checkReadiness()
            assertEquals("answer $i", Readiness.Unknown, setup.readiness.value)
        }
        assertEquals(3, checks())
        assertFalse(log.toString(), "setup.status" in log)
    }

    @Test
    fun aSetupStatusThatFailsAfterARefusalNeedsSetup() = runTest {
        runtimeCheck = { noCredentials }
        onCoreCall = { if (it == "setup.status") throw CoreError(5016, "boom") }

        setup.checkReadiness()

        assertEquals(Readiness.NeedsSetup("No usable credentials found for deepseek."), setup.readiness.value)
    }

    @Test
    fun aSecondCheckWaitsForTheFirstAndItsAnswerIsShown() = runTest(UnconfinedTestDispatcher()) {
        val released = CompletableDeferred<Unit>()
        var answered = 0
        runtimeCheck = { if (answered++ == 0) noCredentials else """{"ok":true,"provider":"copilot","model":"gpt-5"}""" }
        // The core's first check reaches it, then its answer is held until the test releases it.
        var held = false
        val gated = object : CoreGateway by core {
            override suspend fun <P, R> call(method: Method<P, R>, params: P): R {
                val answer = core.call(method, params)
                if (method.name == "setup.runtime_check" && !held) { held = true; released.await() }
                return answer
            }
        }
        val other = ProviderSetup(gated, secrets, bridge, session = { "s1" }, reopenChat = {})

        val both = listOf(launch { other.checkReadiness() }, launch { other.checkReadiness() })
        assertEquals("the second waits for the first", 1, checks())

        released.complete(Unit)
        both.joinAll()

        assertEquals(2, checks())
        assertEquals(Readiness.Ready("copilot", "gpt-5"), other.readiness.value)
    }

    @Test
    fun closingSetupChecksAgainWhateverClosedIt() = runTest(UnconfinedTestDispatcher()) {
        backgroundScope.launch { setup.followReadiness() }
        assertEquals("setup was closed when it started", 0, checks())

        setup.open()
        assertEquals(0, checks())
        setup.pick("copilot")
        setup.back()
        assertEquals("setup is still open", 0, checks())
        setup.close()
        assertEquals("Back to the chat", 1, checks())

        setup.open()
        setup.pick("copilot")
        setup.choose("gpt-5")
        assertNull(setup.state.value)
        assertEquals("a model chosen closes setup", 2, checks())

        configSet = { """{"key":"model","value":"gpt-6","warning":"Model `gpt-6` not found in listing; using it anyway"}""" }
        setup.open()
        setup.pick("copilot")
        setup.chooseCustom("gpt-6")
        assertEquals("the notice keeps setup open", 2, checks())
        setup.close()
        assertEquals("Done", 3, checks())
    }

    // The Settings page's providers (F10): listed from model.options, removed through the one forget path.

    private val currentCopilot = """{"slug":"copilot","name":"GitHub Copilot","models":["gpt-5"],"authenticated":true,"is_current":true}"""

    private fun disconnected() = core.calls.last { it.first == "model.disconnect" }.second.getValue("slug").jsonPrimitive.content

    @Test
    fun connectedListsTheSignedInProvidersInTheCoresOrder() = runTest {
        nousRow = READY_NOUS
        copilotRow = currentCopilot

        val connected = setup.connected()

        // The unauthenticated deepseek, codex and openrouter rows are not connected.
        assertEquals(listOf(ConnectedProvider("nous", "Nous Portal", removable = true, current = false),
                            ConnectedProvider("copilot", "GitHub Copilot", removable = true, current = true)), connected)
        val (method, params) = core.calls.single()
        assertEquals("model.options", method)
        assertEquals("cache only, the configured providers", listOf(false, false),
                     listOf("include_unconfigured", "refresh").map { params[it]?.jsonPrimitive?.boolean ?: false })
    }

    @Test
    fun signingOutOfNousIsTheCoresDisconnectOnly() = runTest {
        nousRow = READY_NOUS
        held["DEEPSEEK_API_KEY"] = "sk-unrelated".toByteArray()

        setup.remove("nous")

        // Its tokens are the core's (D18): no keystore, no secrets re-read; not the provider in use, so no new chat.
        assertEquals(listOf("model.options", "model.disconnect", "setup.runtime_check"), log)
        assertEquals("nous", disconnected())
        assertArrayEquals("sk-unrelated".toByteArray(), held["DEEPSEEK_API_KEY"])
        assertNull(setup.state.value)
    }

    @Test
    fun removingAKeyProviderDeletesItsKeyBeforeTheCoreForgetsIt() = runTest {
        deepseekRow = SIGNED_IN_DEEPSEEK
        names["deepseek"] = "DEEPSEEK_API_KEY"
        held["DEEPSEEK_API_KEY"] = "sk-held".toByteArray()
        var goneFirst = false
        secretsChanged = { log += "secrets_changed"; goneFirst = "DEEPSEEK_API_KEY" !in held }

        setup.remove("deepseek")

        assertEquals(listOf("model.options", "secrets_changed", "model.disconnect", "setup.runtime_check"), log)
        assertTrue("the secrets re-read finds the key gone", goneFirst)
        assertTrue(held.isEmpty())
        assertNull(names["deepseek"])
        assertEquals("deepseek", disconnected())
    }

    @Test
    fun removingTheCurrentProviderStartsANewChatOpensSetupAndChecksAgain() = runTest {
        copilotRow = currentCopilot
        configured = true
        runtimeCheck = { """{"ok":false,"provider":"copilot","error":"No usable credentials found for copilot."}""" }

        setup.remove("copilot")

        // The chat's live session keeps what it was built with (L11); the default names a provider with no credential (D9).
        val disconnect = log.indexOf("model.disconnect")
        val reopen = log.indexOf("reopen_chat")
        val listing = log.withIndex().firstOrNull { it.index > reopen && it.value == "model.options" }?.index ?: -1
        val check = log.indexOf("setup.runtime_check")
        assertTrue(log.toString(), disconnect in 0 until reopen && reopen < listing && listing < check)
        assertTrue(setup.step() is SetupStep.Providers)
        assertEquals(Readiness.Unavailable("No usable credentials found for copilot."), setup.readiness.value)
    }

    @Test
    fun aProviderTheCoreHadNothingLeftForIsRemovedWithoutAFailure() = runTest {
        disconnect = { throw CoreError(4005, "no credentials found for nous") }
        nousRow = READY_NOUS

        setup.remove("nous")

        assertEquals("setup.runtime_check", log.last())
    }

    @Test
    fun aRefusedRemovalStopsBeforeTheChatAndTheCheck() = runTest {
        copilotRow = currentCopilot
        configured = true
        names["copilot"] = "COPILOT_API_KEY"
        held["COPILOT_API_KEY"] = "sk-held".toByteArray()
        val locked = CoreError(5035, "COPILOT_API_KEY is managed by your system: cannot remove it")
        disconnect = { throw locked }

        val refused = runCatching { setup.remove("copilot") }.exceptionOrNull()

        assertEquals(locked, refused)
        assertFalse("the key left the phone first (§8)", "COPILOT_API_KEY" in held)
        assertFalse(log.toString(), "reopen_chat" in log || "setup.runtime_check" in log)
        assertNull(setup.state.value)
    }

    @Test
    fun aSavedEndpointOrAnUnlistedSlugIsNotRemovedHere() = runTest {
        copilotRow = """{"slug":"copilot","name":"GitHub Copilot","models":["gpt-5"],"authenticated":true,"is_user_defined":true}"""
        held["DEEPSEEK_API_KEY"] = "sk-held".toByteArray()

        setup.remove("copilot")
        assertEquals(listOf("model.options"), log)

        log.clear()
        setup.remove("ghost")
        assertEquals(listOf("model.options"), log)
        assertEquals(setOf("DEEPSEEK_API_KEY"), held.keys)
    }
}
