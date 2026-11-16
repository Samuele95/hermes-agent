package com.nousresearch.hermes.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.setup.Confirm
import com.nousresearch.hermes.android.setup.ModelFacts
import com.nousresearch.hermes.android.setup.ProviderFacts
import com.nousresearch.hermes.android.setup.ProviderChoice
import com.nousresearch.hermes.android.setup.Setup
import com.nousresearch.hermes.android.setup.SetupScreen
import com.nousresearch.hermes.android.setup.SetupStep
import com.nousresearch.hermes.android.setup.UsageWindow
import com.nousresearch.hermes.bridge.contract.FoundServer
import com.nousresearch.hermes.bridge.contract.ServerKind
import com.nousresearch.hermes.bridge.contract.SavedEndpoint
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The setup screen shows the core's providers and models and hands a pasted key over without keeping it. */
@RunWith(AndroidJUnit4::class)
class SetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val deepseek = ProviderChoice("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", ready = false, models = emptyList())
    private val picked = mutableListOf<String>()

    private fun show(setup: Setup) = show { setup }

    /** [current] is read while composing: a state it reads changes what the screen shows, as the app's flow does. */
    private fun show(current: () -> Setup) = compose.setContent { Screen(current()) }

    @Composable
    private fun Screen(setup: Setup) {
        SetupScreen(setup, onPick = { picked += "pick:$it" }, onKey = { picked += "key:" + String(it) },
                    onModel = { model, confirmed -> picked += "model:$model:$confirmed" }, onBack = {}, onClose = { picked += "close" },
                    onAddEndpoint = { picked += "add-endpoint" },
                    onEndpoint = { name, url, model, key -> picked += "endpoint:$name|$url|$model|${key?.let(::String)}" },
                    onOpenUrl = { picked += "open:$it" }, onForget = { picked += "forget" },
                    onFindServer = { address, key, model ->
                        picked += "find $address" + (key?.let { " key:" + String(it) } ?: "") +
                            (model.takeIf { it.isNotBlank() }?.let { " model:$it" } ?: "")
                    },
                    onSearchNetwork = { picked += "search" }, onEditEndpoint = { picked += "edit $it" },
                    onDeleteEndpoint = { picked += "delete $it" }, onCustomModel = { picked += "custom:$it" })
    }

    private val dgx = SavedEndpoint("dgx", "DGX", "http://spark-98d1:11434/v1", "gemma4:12b-it-q8_0", true)

    @Test
    fun aProviderIsPickedFromTheCoresList() {
        show(Setup(SetupStep.Providers(listOf(deepseek, ProviderChoice("copilot", "GitHub Copilot", null, true, listOf("gpt-5"))))))

        compose.onNodeWithText("DEEPSEEK_API_KEY").assertExists()
        compose.onNodeWithTag("provider-copilot").performClick()

        assertEquals(listOf("pick:copilot"), picked)
    }

    @Test
    fun theKeyIsMaskedHandedOverTrimmedAndClearedFromTheField() {
        show(Setup(SetupStep.Key(deepseek)))
        compose.onNodeWithTag("key-save").assertIsNotEnabled()

        compose.onNodeWithTag("key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("key").performTextInput("  sk-abc  ")
        compose.onNodeWithTag("key-save").performClick()

        assertEquals(listOf("key:sk-abc"), picked)
        compose.onNodeWithTag("key-save").assertIsNotEnabled()
    }

    @Test
    fun anExpensiveModelIsUsedOnlyThroughTheConfirmButton() {
        val models = deepseek.copy(ready = true, models = listOf("deepseek-flash"))
        show(Setup(SetupStep.Models(models), confirm = Confirm("deepseek-flash", "This model is expensive.")))

        compose.onNodeWithText("This model is expensive.").assertExists()
        compose.onNodeWithTag("setup-confirm").performClick()

        assertEquals(listOf("model:deepseek-flash:true"), picked)
    }

    @Test
    fun eachModelShowsItsPriceAsTheCoreWroteIt() {
        val facts = ProviderFacts(models = mapOf("a" to ModelFacts(input = "$3.00", output = "$15.00"), "b" to ModelFacts(input = "free", free = true)))
        show(Setup(SetupStep.Models(deepseek.copy(ready = true, models = listOf("a", "b", "c"), facts = facts))))

        compose.onNodeWithText("$3.00 in / $15.00 out per Mtok").assertExists()
        compose.onNodeWithText("Free").assertExists()
        compose.onNodeWithTag("model-c").assert(!hasAnyDescendant(hasText("per Mtok", substring = true)))
    }

    @Test
    fun aProModelIsLockedAndItsTapExplainsAndChoosesNothing() {
        val nous = ProviderChoice("nous", "Nous Portal", null, ready = true, models = listOf("nous/hermes-4", "nous/hermes-4-405b"),
                                  facts = ProviderFacts(models = mapOf("nous/hermes-4-405b" to ModelFacts(locked = true))))
        show(Setup(SetupStep.Models(nous)))

        compose.onNodeWithTag("pro-nous/hermes-4-405b", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("model-nous/hermes-4-405b").performClick()
        assertEquals(emptyList<String>(), picked)
        compose.onNodeWithTag("pro-explained").assert(hasText("Pro models need a paid Nous subscription."))

        compose.onNodeWithTag("model-nous/hermes-4").performClick()
        assertEquals(listOf("model:nous/hermes-4:false"), picked)
    }

    @Test
    fun theProvidersWarningLimitAndUsageShowAboveItsModels() {
        val facts = ProviderFacts(warning = "DeepSeek is slow today", limited = true,
                                  usage = listOf(UsageWindow("Session", 63, null), UsageWindow("Account 2", null, null)),
                                  models = mapOf("v4" to ModelFacts(resetsAt = "2099-01-01T10:00:00+00:00")))
        show(Setup(SetupStep.Models(deepseek.copy(ready = true, models = listOf("v4"), facts = facts))))

        compose.onNodeWithTag("provider-warning").assert(hasText("DeepSeek is slow today"))
        compose.onNodeWithTag("provider-limited").assert(hasText("DeepSeek hit its usage limit. You can still pick a model for after it resets."))
        compose.onNodeWithTag("provider-usage").assert(hasText("Session: 37% left", substring = true))
            .assert(hasText("Account 2: usage unavailable", substring = true))
        compose.onNodeWithTag("model-v4").assert(hasAnyDescendant(hasText("resets ", substring = true))).performClick()
        assertEquals(listOf("model:v4:false"), picked)
    }

    @Test
    fun addCustomModelOpensAFieldThatHandsTheTrimmedIdOver() {
        show(Setup(SetupStep.Models(deepseek.copy(ready = true, models = listOf("a")))))

        compose.onNodeWithTag("add-custom-model").performClick()
        compose.onNodeWithTag("model-use").assertIsNotEnabled()
        compose.onNodeWithTag("model-name").performTextInput("gpt 6")
        compose.onNodeWithTag("model-use").assertIsNotEnabled()
        compose.onNodeWithTag("model-name").performTextReplacement("  gpt-6  ")
        compose.onNodeWithTag("model-use").performClick()

        assertEquals(listOf("custom:gpt-6"), picked)
    }

    /** A typed Pro-locked id is explained under the field in the picker's own words while the field holds it (improve #1). */
    @Test
    fun aTypedProLockedIdIsExplainedUnderTheField() {
        val nous = ProviderChoice("nous", "Nous Portal", null, ready = true, models = listOf("nous/hermes-4", "nous/hermes-4-405b"),
                                  facts = ProviderFacts(models = mapOf("nous/hermes-4-405b" to ModelFacts(locked = true))))
        var setup by mutableStateOf(Setup(SetupStep.Models(nous)))
        show { setup }

        compose.onNodeWithTag("add-custom-model").performClick()
        compose.onNodeWithTag("model-name").performTextInput("nous/hermes-4-405b")
        compose.onNodeWithTag("model-use").performClick()
        assertEquals(listOf("custom:nous/hermes-4-405b"), picked)
        compose.onNodeWithTag("custom-pro-explained").assertDoesNotExist()

        setup = setup.copy(proLocked = "nous/hermes-4-405b")  // as ProviderSetup.choose answers a locked id
        compose.onNodeWithTag("custom-pro-explained").assertIsDisplayed()
            .assert(hasText("Pro models need a paid Nous subscription."))
        compose.onNodeWithTag("setup-error").assertDoesNotExist()

        compose.onNodeWithTag("model-name").performTextReplacement("nous/hermes-4")
        compose.onNodeWithTag("custom-pro-explained").assertDoesNotExist()
    }

    @Test
    fun theIdsTheUserAddedFollowTheCatalogAsCustomModels() {
        show(Setup(SetupStep.Models(deepseek.copy(ready = true, models = listOf("a"), custom = listOf("mine")))))

        compose.onNodeWithTag("model-mine").assert(hasAnyDescendant(hasText("Custom model"))).performClick()

        assertEquals(listOf("model:mine:false"), picked)
    }

    @Test
    fun theCoresNoteOnATakenIdShowsWithDone() {
        show(Setup(SetupStep.Models(deepseek.copy(ready = true, models = listOf("a"), custom = listOf("gpt-6"))), notice = "not in listing"))

        compose.onNodeWithTag("setup-notice").assert(hasText("not in listing"))
        compose.onNodeWithTag("setup-done").performClick()

        assertEquals(listOf("close"), picked)
    }

    @Test
    fun aCustomEndpointIsOfferedAboveTheProviders() {
        show(Setup(SetupStep.Providers(listOf(deepseek))))

        compose.onNodeWithTag("add-endpoint").performClick()

        assertEquals(listOf("add-endpoint"), picked)
    }

    @Test
    fun anEndpointNeedsAnAddressAndAModelAndItsBlankKeyIsNoKey() {
        show(Setup(SetupStep.Endpoint()))
        compose.onNodeWithTag("endpoint-key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

        compose.onNodeWithTag("endpoint-name").performTextInput(" Host Ollama ")
        compose.onNodeWithTag("endpoint-address").performTextInput("http://127.0.0.1:11434/v1")
        compose.onNodeWithTag("endpoint-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("endpoint-model").performTextInput("gemma4")
        compose.onNodeWithTag("endpoint-key").performTextInput("   ")
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        assertEquals(listOf("endpoint:Host Ollama|http://127.0.0.1:11434/v1|gemma4|null"), picked)
    }

    @Test
    fun savedEndpointsAreListedAndOpenForEditing() {
        show(Setup(SetupStep.Providers(emptyList(), listOf(dgx))))

        compose.onNodeWithText("DGX").assertExists()
        compose.onNodeWithTag("saved-dgx").performClick()

        assertEquals(listOf("edit dgx"), picked)
    }

    @Test
    fun aFoundServerOffersItsModelsInADropdown() {
        val found = FoundServer("http://spark-98d1:11434/v1", ServerKind.OLLAMA, listOf("gemma4:12b-it-q8_0", "gemma3:12b-it-q8_0"))
        show(Setup(SetupStep.Endpoint(found = found)))

        compose.onNodeWithText("Ollama · 2 models").assertExists()
        compose.onNodeWithTag("endpoint-model").performClick()
        compose.onNodeWithTag("endpoint-model-gemma3:12b-it-q8_0").performClick()
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        // No name typed: the server's host names it.
        assertEquals(listOf("endpoint:spark-98d1|http://spark-98d1:11434/v1|gemma3:12b-it-q8_0|null"), picked)
    }

    @Test
    fun anEditMadeAfterAServerWasFoundSurvivesARotation() {
        val a = FoundServer("http://spark-98d1:11434/v1", ServerKind.OLLAMA, listOf("gemma4:12b-it-q8_0", "qwen3"))
        val restore = StateRestorationTester(compose)
        restore.setContent { Screen(Setup(SetupStep.Endpoint(found = a, suggestedModel = "gemma4:12b-it-q8_0", generation = 1))) }

        compose.onNodeWithTag("endpoint-address").performTextReplacement("http://spark-98d1:8080/v1")  // another port
        compose.onNodeWithTag("endpoint-model").performTextReplacement("my-model")  // a model the server does not list
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        assertEquals(listOf("endpoint:spark-98d1|http://spark-98d1:8080/v1|my-model|null"), picked)
    }

    @Test
    fun eachServerFoundFillsTheAddressAndModelItSuggests() {
        val a = FoundServer("http://spark-98d1:11434/v1", ServerKind.OLLAMA, listOf("gemma4:12b-it-q8_0"))
        val b = FoundServer("http://192.168.1.20:1234/v1", ServerKind.LMSTUDIO, listOf("qwen3", "llama3"))
        var setup by mutableStateOf(Setup(SetupStep.Endpoint()))
        show { setup }

        compose.onNodeWithTag("endpoint-model").performTextInput("gemm4")  // a typo before Find
        setup = Setup(SetupStep.Endpoint(found = a, suggestedModel = "gemma4:12b-it-q8_0", generation = 1))
        compose.waitForIdle()
        setup = Setup(SetupStep.Endpoint(found = b, suggestedModel = "qwen3", generation = 2))  // then another server is found
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        assertEquals(listOf("endpoint:192.168.1.20|http://192.168.1.20:1234/v1|qwen3|null"), picked)
    }

    @Test
    fun aServerFoundAgainStartsTheFormAgainFromIt() {
        val a = FoundServer("http://spark-98d1:11434/v1", ServerKind.OLLAMA, listOf("gemma4:12b-it-q8_0"))
        var setup by mutableStateOf(Setup(SetupStep.Endpoint(found = a, suggestedModel = "gemma4:12b-it-q8_0", generation = 1)))
        show { setup }

        compose.onNodeWithTag("endpoint-address").performTextReplacement("http://spark-98d1:8080/v1")
        setup = Setup(SetupStep.Endpoint(found = a, suggestedModel = "gemma4:12b-it-q8_0", generation = 2))  // an equal server
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        assertEquals(listOf("endpoint:spark-98d1|http://spark-98d1:11434/v1|gemma4:12b-it-q8_0|null"), picked)
    }

    @Test
    fun aProbeUnderwayKeepsWhatWasTyped() {
        val a = FoundServer("http://spark-98d1:11434/v1", ServerKind.OLLAMA, listOf("gemma4:12b-it-q8_0"))
        var setup by mutableStateOf(Setup(SetupStep.Endpoint(found = a, suggestedModel = "gemma4:12b-it-q8_0", generation = 1)))
        show { setup }

        compose.onNodeWithTag("endpoint-address").performTextReplacement("http://spark-98d1:8080/v1")
        compose.onNodeWithTag("endpoint-model").performTextReplacement("my-model")
        setup = Setup(SetupStep.Endpoint(found = null, suggestedModel = "gemma4:12b-it-q8_0", generation = 1))  // findServer's interim write
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        assertEquals(listOf("endpoint:spark-98d1|http://spark-98d1:8080/v1|my-model|null"), picked)
    }

    @Test
    fun anAddressEditedByHandHidesTheServerFoundBefore() {
        val found = FoundServer("http://spark-98d1:11434/v1", ServerKind.OLLAMA, listOf("gemma4:12b-it-q8_0"))
        show(Setup(SetupStep.Endpoint(found = found)))
        compose.onNodeWithTag("endpoint-found").assertExists()

        compose.onNodeWithTag("endpoint-address").performTextReplacement("http://other-host:8080/v1")

        compose.onNodeWithTag("endpoint-found").assertDoesNotExist()
        compose.onNodeWithTag("endpoint-model").performClick()
        compose.onNodeWithTag("endpoint-model-gemma4:12b-it-q8_0").assertDoesNotExist()
    }

    @Test
    fun findServerSendsTheTypedAddressAndKey() {
        show(Setup(SetupStep.Endpoint()))

        compose.onNodeWithTag("endpoint-address").performTextInput(" spark-98d1 ")
        compose.onNodeWithTag("endpoint-key").performTextInput("sk-local")
        compose.onNodeWithTag("find-server").performScrollTo().performClick()

        assertEquals("find spark-98d1 key:sk-local", picked.last())
    }

    @Test
    fun findServerSendsTheTypedModel() {
        show(Setup(SetupStep.Endpoint()))

        compose.onNodeWithTag("endpoint-address").performTextInput("spark-98d1")
        compose.onNodeWithTag("endpoint-model").performTextInput("qwen3")
        compose.onNodeWithTag("find-server").performScrollTo().performClick()

        assertEquals("find spark-98d1 model:qwen3", picked.last())
    }

    @Test
    fun aServerFoundOnTheNetworkIsAskedForItsModels() {
        val found = FoundServer("http://192.168.1.20:1234/v1", ServerKind.LMSTUDIO, listOf("qwen3"))
        show(Setup(SetupStep.Endpoint(scan = listOf(found))))
        compose.onNodeWithText("LM Studio · 1 model").assertExists()

        compose.onNodeWithTag("search-network").performScrollTo().performClick()
        compose.onNodeWithTag("found-0").performScrollTo().performClick()

        assertEquals(listOf("search", "find http://192.168.1.20:1234/v1"), picked)
    }

    @Test
    fun anEditedEndpointKeepsItsValuesAndCanBeDeleted() {
        show(Setup(SetupStep.Endpoint(editing = dgx)))

        compose.onNodeWithText("API key (leave empty to keep the saved one)").assertExists()
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()
        compose.onNodeWithTag("endpoint-delete").performScrollTo().performClick()
        assertEquals("one tap does not delete", listOf("endpoint:DGX|http://spark-98d1:11434/v1|gemma4:12b-it-q8_0|null"), picked)
        compose.onNodeWithTag("endpoint-delete-confirm").performScrollTo().performClick()

        assertEquals(listOf("endpoint:DGX|http://spark-98d1:11434/v1|gemma4:12b-it-q8_0|null", "delete dgx"), picked)
    }

    @Test
    fun aDeleteThatIsNotConfirmedDeletesNothing() {
        show(Setup(SetupStep.Endpoint(editing = dgx)))

        compose.onNodeWithTag("endpoint-delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete DGX? Its key on this phone goes too.").assertExists()
        compose.onNodeWithTag("endpoint-delete-cancel").performScrollTo().performClick()

        compose.onNodeWithTag("endpoint-delete-confirm").assertDoesNotExist()
        compose.onNodeWithTag("endpoint-delete").assertExists()
        assertEquals(emptyList<String>(), picked)
    }

    @Test
    fun aNewEndpointHasNoDeleteButton() {
        show(Setup(SetupStep.Endpoint()))

        compose.onNodeWithTag("endpoint-delete").assertDoesNotExist()
    }

    @Test
    fun theListSaysWhenTheDefaultModelWasDeleted() {
        show(Setup(SetupStep.Providers(listOf(deepseek), defaultGone = true)))

        compose.onNodeWithTag("setup-no-default").assertExists()
    }

    @Test
    fun aSignInShowsItsCodeAndOpensItsPage() {
        val nous = ProviderChoice("nous", "Nous Portal", null, ready = false, models = emptyList(), signIn = true)
        show(Setup(SetupStep.SignIn(nous, "L1", "NOUS-1234", "https://portal.test/device?user_code=NOUS-1234")))

        compose.onNodeWithTag("signin-code").assert(androidx.compose.ui.test.hasText("NOUS-1234"))
        compose.onNodeWithTag("signin-open").performClick()

        assertEquals(listOf("open:https://portal.test/device?user_code=NOUS-1234"), picked)
    }

    @Test
    fun theCoresErrorIsShown() {
        show(Setup(SetupStep.Key(deepseek), error = "DeepSeek did not accept the key."))

        compose.onNodeWithTag("setup-error").assertExists()
    }

    @Test
    fun aKeyHeldOnThePhoneCanBeForgottenAndOnlyThen() {
        var step by mutableStateOf<SetupStep>(SetupStep.Models(deepseek.copy(ready = true, models = listOf("v4")), keyHeld = true))
        compose.setContent {
            SetupScreen(Setup(step = step), onPick = {}, onKey = {}, onModel = { _, _ -> }, onBack = {}, onClose = {},
                        onForget = { picked += "forget" })
        }

        compose.onNodeWithTag("forget-key").performClick()
        assertEquals(listOf("forget"), picked)

        step = SetupStep.Models(deepseek.copy(ready = true, models = listOf("v4")), keyHeld = false)
        compose.onNodeWithTag("forget-key").assertDoesNotExist()
    }
}
