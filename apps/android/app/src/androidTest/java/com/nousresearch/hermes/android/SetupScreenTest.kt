package com.nousresearch.hermes.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.setup.Confirm
import com.nousresearch.hermes.android.setup.ProviderChoice
import com.nousresearch.hermes.android.setup.Setup
import com.nousresearch.hermes.android.setup.SetupScreen
import com.nousresearch.hermes.android.setup.SetupStep
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

    private fun show(setup: Setup) = compose.setContent {
        SetupScreen(setup, onPick = { picked += "pick:$it" }, onKey = { picked += "key:" + String(it) },
                    onModel = { model, confirmed -> picked += "model:$model:$confirmed" }, onBack = {}, onClose = {})
    }

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
    fun theCoresErrorIsShown() {
        show(Setup(SetupStep.Key(deepseek), error = "DeepSeek did not accept the key."))

        compose.onNodeWithTag("setup-error").assertExists()
    }
}
