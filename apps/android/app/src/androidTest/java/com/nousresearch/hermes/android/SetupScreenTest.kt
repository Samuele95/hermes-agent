package com.nousresearch.hermes.android

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.setup.Confirm
import com.nousresearch.hermes.android.setup.ProviderChoice
import com.nousresearch.hermes.android.setup.Setup
import com.nousresearch.hermes.android.setup.SetupScreen
import com.nousresearch.hermes.android.setup.SetupStep
import com.nousresearch.hermes.bridge.contract.EndpointProbe
import com.nousresearch.hermes.bridge.contract.FoundEndpoint
import com.nousresearch.hermes.bridge.contract.ProbeEndpointReply
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
    private fun show(current: () -> Setup) = compose.setContent {
        SetupScreen(current(), onPick = { picked += "pick:$it" }, onKey = { picked += "key:" + String(it) },
                    onModel = { model, confirmed -> picked += "model:$model:$confirmed" }, onBack = {}, onClose = {},
                    onAddEndpoint = { picked += "add-endpoint" },
                    onEndpoint = { name, url, model, key -> picked += "endpoint:$name|$url|$model|${key?.let(::String)}" },
                    onOpenUrl = { picked += "open:$it" }, onForget = { picked += "forget" },
                    onFindServer = { address, key -> picked += "find $address" + (key?.let { " key:" + String(it) } ?: "") },
                    onSearchNetwork = { picked += "search" }, onEditEndpoint = { picked += "edit $it" },
                    onDeleteEndpoint = { picked += "delete $it" })
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
        val found = ProbeEndpointReply(EndpointProbe.FOUND, "http://spark-98d1:11434/v1", "ollama",
                                       listOf("gemma4:12b-it-q8_0", "gemma3:12b-it-q8_0"))
        show(Setup(SetupStep.Endpoint(probe = found)))

        compose.onNodeWithText("ollama · 2 models").assertExists()
        compose.onNodeWithTag("endpoint-model").performClick()
        compose.onNodeWithTag("endpoint-model-gemma3:12b-it-q8_0").performClick()
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        // No name typed: the server's host names it.
        assertEquals(listOf("endpoint:spark-98d1|http://spark-98d1:11434/v1|gemma3:12b-it-q8_0|null"), picked)
    }

    @Test
    fun aServerFoundLaterBringsItsOwnModel() {
        val a = ProbeEndpointReply(EndpointProbe.FOUND, "http://spark-98d1:11434/v1", "ollama", listOf("gemma4:12b-it-q8_0"))
        val b = ProbeEndpointReply(EndpointProbe.FOUND, "http://192.168.1.20:1234/v1", "lmstudio", listOf("qwen3", "llama3"))
        var setup by mutableStateOf(Setup(SetupStep.Endpoint()))
        show { setup }

        compose.onNodeWithTag("endpoint-model").performTextInput("gemm4")  // a typo before Find
        setup = Setup(SetupStep.Endpoint(probe = a))
        compose.waitForIdle()
        setup = Setup(SetupStep.Endpoint(probe = b))  // then another server is found
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        assertEquals(listOf("endpoint:192.168.1.20|http://192.168.1.20:1234/v1|qwen3|null"), picked)
    }

    @Test
    fun aModelTheNewServerAlsoListsIsKept() {
        val a = ProbeEndpointReply(EndpointProbe.FOUND, "http://spark-98d1:11434/v1", "ollama", listOf("gemma4", "qwen3"))
        val b = ProbeEndpointReply(EndpointProbe.FOUND, "http://spark-98d1:11434/v1", "ollama", listOf("llama3", "qwen3"))
        var setup by mutableStateOf(Setup(SetupStep.Endpoint(probe = a)))
        show { setup }

        compose.onNodeWithTag("endpoint-model").performClick()
        compose.onNodeWithTag("endpoint-model-qwen3").performClick()
        setup = Setup(SetupStep.Endpoint(probe = b))
        compose.onNodeWithTag("endpoint-save").performScrollTo().performClick()

        assertEquals(listOf("endpoint:spark-98d1|http://spark-98d1:11434/v1|qwen3|null"), picked)
    }

    @Test
    fun anAddressEditedByHandHidesTheServerFoundBefore() {
        val found = ProbeEndpointReply(EndpointProbe.FOUND, "http://spark-98d1:11434/v1", "ollama", listOf("gemma4:12b-it-q8_0"))
        show(Setup(SetupStep.Endpoint(probe = found)))
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
    fun aServerFoundOnTheNetworkIsAskedForItsModels() {
        val found = FoundEndpoint("http://192.168.1.20:1234/v1", "lmstudio", listOf("qwen3"))
        show(Setup(SetupStep.Endpoint(scan = listOf(found))))

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
