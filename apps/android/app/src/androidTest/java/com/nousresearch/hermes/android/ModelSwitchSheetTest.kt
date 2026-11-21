package com.nousresearch.hermes.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Asked
import com.nousresearch.hermes.android.chat.ModelActions
import com.nousresearch.hermes.android.chat.ModelSwitchSheet
import com.nousresearch.hermes.android.chat.PickerUi
import com.nousresearch.hermes.android.setup.ModelCatalog
import com.nousresearch.hermes.android.setup.ModelFacts
import com.nousresearch.hermes.android.setup.ModelRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The chat's model picker (models-08): one list across providers, the current model marked, a pick hands that row over. */
@RunWith(AndroidJUnit4::class)
class ModelSwitchSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val NOW = 1_794_729_600_000L  // 2026-11-15T08:00:00Z
        val GPT5 = ModelRow("copilot", "GitHub Copilot", "gpt-5", ModelFacts(input = "$1.25", output = "$10.00"), current = true)
        val GPT5_PRO = ModelRow("copilot", "GitHub Copilot", "gpt-5-pro", ModelFacts(locked = true))
        val V4_PRO = ModelRow("deepseek", "DeepSeek", "deepseek-v4-pro")
        val V4_FLASH = ModelRow("deepseek", "DeepSeek", "deepseek-v4-flash", ModelFacts(resetsAt = "2026-11-15T09:00:00Z"))
        val CATALOG = ModelCatalog(listOf(GPT5, GPT5_PRO, V4_PRO, V4_FLASH))
    }

    private val picked = mutableListOf<ModelRow>()
    private val done = mutableListOf<String>()
    private var ui by mutableStateOf(PickerUi(catalog = CATALOG))
    private val actions = ModelActions(open = { done += "open" }, close = { done += "close" }, query = { ui = ui.copy(query = it) },
                                       addCustom = { done += "add-custom" }, pick = { picked += it }, confirm = { done += "confirm" },
                                       keep = { done += "keep" }, addProvider = { done += "add-provider" })

    private fun show() = compose.setContent { ModelSwitchSheet(ui, actions, chatModel = "gpt-5", now = { NOW }) }

    @Test
    fun oneListAcrossProvidersMarksTheCurrentModelAndAPickSendsThatRow() {
        show()

        compose.onNodeWithTag("models-sheet").assertExists()
        compose.onNodeWithText("Switch model").assertExists()
        compose.onNodeWithTag("models-current").assertTextEquals("current: gpt-5 · GitHub Copilot")
        compose.onNodeWithText("GITHUB COPILOT").assertExists()  // a section caption is upper-cased
        compose.onNodeWithText("DEEPSEEK").assertExists()
        compose.onNodeWithTag("current-copilot:gpt-5", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("current-deepseek:deepseek-v4-pro", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("model-copilot:gpt-5").assert(hasAnyDescendant(hasText("$1.25 in / $10.00 out per Mtok")))
        compose.onNodeWithTag("model-deepseek:deepseek-v4-flash").assert(hasAnyDescendant(hasText("resets", substring = true)))

        compose.onNodeWithTag("model-deepseek:deepseek-v4-pro").performClick()

        assertEquals(listOf(V4_PRO), picked)
        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun aLockedModelSaysWhyAndPicksNothing() {
        show()

        compose.onNodeWithTag("pro-copilot:gpt-5-pro", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("pro-explained").assertDoesNotExist()
        compose.onNodeWithTag("model-copilot:gpt-5-pro").performClick()

        assertEquals(emptyList<ModelRow>(), picked)
        compose.onNodeWithTag("pro-explained").assert(hasText("Pro models need a paid Nous subscription."))

        ui = PickerUi(catalog = ModelCatalog(listOf(GPT5, GPT5_PRO)), locked = GPT5_PRO.key)
        compose.onNodeWithTag("pro-explained").assert(hasText("Pro models need a paid Nous subscription."))
    }

    @Test
    fun theSearchNarrowsAcrossProvidersAndATypedIdIsOfferedForEach() {
        show()

        compose.onNodeWithTag("models-search").performTextInput("copilot")
        compose.onNodeWithTag("model-copilot:gpt-5").assertExists()
        compose.onNodeWithTag("model-copilot:gpt-5-pro").assertExists()
        compose.onNodeWithTag("model-deepseek:deepseek-v4-pro").assertDoesNotExist()
        compose.onNodeWithTag("typed-copilot:copilot").assertDoesNotExist()  // a search that finds rows offers no typed id

        compose.onNodeWithTag("models-search").performTextReplacement("openai/gpt-6")
        compose.onNodeWithTag("model-copilot:gpt-5").assertDoesNotExist()
        compose.onNodeWithText("CUSTOM MODEL").assertExists()
        compose.onNodeWithTag("typed-copilot:openai/gpt-6").assertExists()
        compose.onNodeWithTag("typed-deepseek:openai/gpt-6").assertExists()
        compose.onNodeWithTag("models-none").assertDoesNotExist()
        compose.onNodeWithTag("typed-deepseek:openai/gpt-6").performClick()
        assertEquals(listOf(ModelRow("deepseek", "DeepSeek", "openai/gpt-6", custom = true)), picked)

        compose.onNodeWithTag("models-search").performTextReplacement("a b")
        compose.onNodeWithTag("models-none").assertTextEquals("No models found.")

        compose.onNodeWithTag("models-add-custom").performClick()
        assertEquals(listOf("add-custom"), done)
        ui = ui.copy(adding = true, query = "")
        compose.onNodeWithTag("models-search").assert(hasText("Type a model id, e.g. openai/gpt-5"))
        compose.onNodeWithTag("models-add-provider").performClick()
        assertEquals(listOf("add-custom", "add-provider"), done)
    }

    @Test
    fun aConfirmADeferredPickAFailureAndAnEmptyListEachSaySo() {
        ui = PickerUi(catalog = CATALOG, confirm = Asked(GPT5, "gpt-5 costs more"))
        show()

        compose.onNodeWithTag("models-confirm").assertExists()
        compose.onNodeWithText("Switch to gpt-5?").assertExists()
        compose.onNodeWithText("gpt-5 costs more").assertExists()
        compose.onNodeWithTag("models-confirm-yes").performClick()
        assertEquals(listOf("confirm"), done)
        compose.onNodeWithTag("models-keep").performClick()
        assertEquals(listOf("confirm", "keep"), done)
        ui = ui.copy(confirm = Asked(GPT5, " "))
        compose.onNodeWithText("This model switch needs confirmation.").assertExists()

        ui = PickerUi(catalog = CATALOG, next = "gpt-5")
        compose.onNodeWithTag("models-confirm").assertDoesNotExist()
        compose.onNodeWithTag("models-deferred").assertTextEquals("gpt-5 applies from your next message")
        compose.onNodeWithTag("models-done").performClick()
        assertEquals(listOf("confirm", "keep", "close"), done)

        ui = PickerUi(catalog = CATALOG, failed = "No usable credentials.")
        compose.onNodeWithTag("models-failed").assert(hasAnyDescendant(hasText("Model switch failed")))
        compose.onNodeWithTag("models-failed").assert(hasAnyDescendant(hasText("No usable credentials.")))

        ui = PickerUi(loadFailed = "model.options failed")
        compose.onNodeWithTag("models-load-failed").assert(hasAnyDescendant(hasText("Could not load models")))
        compose.onNodeWithText("model.options failed").assertExists()
        ui = PickerUi(catalog = ModelCatalog(emptyList()))
        compose.onNodeWithTag("models-no-providers").assertTextEquals("No authenticated providers.")
        ui = PickerUi()
        compose.onNodeWithTag("models-loading").assertExists()
        compose.onNodeWithTag("models-current").assertTextEquals("current: gpt-5")
    }
}
