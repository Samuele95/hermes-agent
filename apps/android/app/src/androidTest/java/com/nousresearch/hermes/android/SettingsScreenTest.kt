package com.nousresearch.hermes.android

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.setup.ApprovalMode
import com.nousresearch.hermes.android.setup.SettingsScreen
import com.nousresearch.hermes.android.setup.SettingsState
import com.nousresearch.hermes.bridge.protocol.ConfigGetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The settings screen shows the core's approvals mode and hands the user's choice over; the choice lands in the core. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val chosen = mutableListOf<ApprovalMode>()

    private fun show(state: SettingsState) = compose.setContent { SettingsScreen(state, onApprovals = { chosen += it }, onClose = {}) }

    @Test
    fun theCoresModeIsSelectedAndAChoiceIsHandedOver() {
        show(SettingsState(ApprovalMode.SMART))

        compose.onNodeWithTag("approvals-smart").assertIsSelected()
        compose.onNodeWithTag("approvals-manual").assertIsNotSelected()
        compose.onNodeWithTag("approvals-manual").performClick()

        assertEquals(listOf(ApprovalMode.MANUAL), chosen)
    }

    @Test
    fun whileAChoiceIsWrittenNoOtherCanBeMadeAndARefusalIsShown() {
        show(SettingsState(ApprovalMode.SMART, busy = true, error = "settings are read-only"))

        compose.onNodeWithTag("approvals-off").assertIsNotEnabled()
        compose.onNodeWithText("settings are read-only").assertExists()
    }

    @Test
    fun aChoiceMadeInTheAppIsTheCoresMode() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        val before = ready.gateway.call(Methods.CONFIG_GET, ConfigGetParams(key = "approvals.mode")).value
        try {
            ready.settings.open()
            ready.settings.choose(ApprovalMode.MANUAL)

            assertEquals("manual", ready.gateway.call(Methods.CONFIG_GET, ConfigGetParams(key = "approvals.mode")).value)
            val config = File(app.filesDir, ".hermes/config.yaml").readText()
            assertTrue(config, Regex("""approvals:\s*\n(\s+.*\n)*?\s+mode:\s*manual""").containsMatchIn(config))
        } finally {
            ready.settings.choose(ApprovalMode.of(before))
            ready.settings.close()
        }
    }
}
