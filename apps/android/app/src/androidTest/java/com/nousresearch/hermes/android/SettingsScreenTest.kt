package com.nousresearch.hermes.android

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.settings.ApprovalMode
import com.nousresearch.hermes.android.settings.ConfigSettings
import com.nousresearch.hermes.android.settings.ConnectedProvider
import com.nousresearch.hermes.android.settings.Personality
import com.nousresearch.hermes.android.settings.SettingsActions
import com.nousresearch.hermes.android.settings.SettingsEdit
import com.nousresearch.hermes.android.settings.SettingsScreen
import com.nousresearch.hermes.android.settings.SettingsUi
import com.nousresearch.hermes.bridge.protocol.ConfigGetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings page shows what the core last answered (Chat's personality, the approvals mode, timeout and allowlist,
 * memory) and hands the user's change over; on the device, a change made through the page lands in the core.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val chosen = mutableListOf<ApprovalMode>()
    private val personalities = mutableListOf<String>()
    private val edits = mutableListOf<SettingsEdit>()
    private val asks = mutableListOf<String?>()
    private var confirms = 0
    private var restarts = 0
    private val providerAsks = mutableListOf<String?>()
    private var providerConfirms = 0
    private var skillsOpened = 0
    private val actions = SettingsActions(approvals = { chosen += it }, personality = { personalities += it }, edit = { edits += it },
                                          askRemove = { asks += it }, confirmRemove = { confirms++ }, restartGateway = { restarts++ },
                                          askRemoveProvider = { providerAsks += it }, confirmRemoveProvider = { providerConfirms++ },
                                          openSkills = { skillsOpened++ })

    private fun show(state: SettingsUi) = compose.setContent { SettingsScreen(state, actions, onClose = {}) }

    /** The clickable row under [tag] (a `ListRow` tags its frame; its clickable row is inside). */
    private fun clickableUnder(tag: String) = compose.onNode(hasAnyAncestor(hasTestTag(tag)) and hasClickAction())

    @Test
    fun theCoresModeIsSelectedAndAChoiceIsHandedOver() {
        show(SettingsUi(ApprovalMode.SMART))

        compose.onNodeWithTag("approvals-smart").assertIsSelected()
        compose.onNodeWithTag("approvals-manual").assertIsNotSelected()
        compose.onNodeWithTag("approvals-manual").performClick()

        assertEquals(listOf(ApprovalMode.MANUAL), chosen)
    }

    @Test
    fun whileAChoiceIsWrittenNoOtherCanBeMadeAndARefusalIsShown() {
        show(SettingsUi(ApprovalMode.SMART, busy = true, error = "settings are read-only"))

        compose.onNodeWithTag("approvals-off").assertIsNotEnabled()
        compose.onNodeWithText("settings are read-only").assertExists()
    }

    @Test
    fun aModeThatCouldNotBeReadSelectsNoneButCanBeChosen() {
        show(SettingsUi(approvals = null, error = "could not read the mode"))

        listOf("manual", "smart", "off").forEach { compose.onNodeWithTag("approvals-$it").assertIsNotSelected() }
        compose.onNodeWithTag("approvals-smart").performClick()

        assertEquals(listOf(ApprovalMode.SMART), chosen)
    }

    @Test
    fun thePersonalityRowShowsTheCoresChoiceAndAPickIsHandedOver() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG))

        compose.onNode(hasAnyAncestor(hasTestTag("personality")) and hasText("Concise")).assertExists()
        compose.onNodeWithText("Default assistant style for new sessions.").assertExists()
        compose.onNodeWithTag("personality").performClick()
        compose.onNodeWithTag("personality-none").assertExists()
        compose.onNode(hasTestTag("personality-none") and hasText("None")).assertExists()  // the item merges its text
        compose.onNodeWithTag("personality-helpful").assertExists()
        compose.onNodeWithText("You are a helpful, friendly AI assistant.").assertExists()
        compose.onNodeWithTag("personality-helpful").performClick()

        assertEquals(listOf("helpful"), personalities)
        compose.onNodeWithTag("personality-none").assertDoesNotExist()  // a pick closes the list
        compose.onNodeWithTag("personality").performClick()
        compose.onNodeWithTag("personality-none").performClick()
        assertEquals(listOf("helpful", "none"), personalities)
    }

    @Test
    fun withoutTheCoresListThereIsNoPersonalityRow() {
        show(SettingsUi(ApprovalMode.SMART, "concise", config = null))

        compose.onNodeWithTag("personality").assertDoesNotExist()
    }

    @Test
    fun theTimeoutKeepsEveryDigitAndSavesTheTypedNumber() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG))

        compose.onNodeWithTag("approvals-timeout").performScrollTo().performTextReplacement("12a05")
        compose.onNode(hasTestTag("approvals-timeout") and hasText("1205")).assertExists()
        compose.onNodeWithTag("approvals-timeout-save").assertIsEnabled().performClick()
        assertEquals(listOf(SettingsEdit(approvalTimeoutS = 1205)), edits)

        compose.onNodeWithTag("approvals-timeout").performTextReplacement("300")
        compose.onNodeWithTag("approvals-timeout-save").assertIsNotEnabled()
        compose.onNodeWithTag("approvals-timeout").performTextReplacement("")
        compose.onNodeWithTag("approvals-timeout-save").assertIsNotEnabled()
        compose.onNodeWithTag("approvals-timeout").performTextReplacement("77")
        compose.onNodeWithTag("approvals-timeout").performImeAction()  // Done saves too
        assertEquals(listOf(SettingsEdit(approvalTimeoutS = 1205), SettingsEdit(approvalTimeoutS = 77)), edits)
    }

    @Test
    fun aZeroTimeoutIsHandedOverForTheCoreToJudge() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG))

        compose.onNodeWithTag("approvals-timeout").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("approvals-timeout-save").performClick()

        assertEquals(listOf(SettingsEdit(approvalTimeoutS = 0)), edits)
    }

    @Test
    fun eachAlwaysAllowedCommandHasRemoveWhichAsksFirst() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG))

        compose.onNodeWithTag("allowed-0").performScrollTo().assertTextEquals("git status")
        compose.onNodeWithTag("allowed-1").assertTextEquals("docker *")
        compose.onNodeWithTag("allowed-remove-1").performScrollTo().performClick()

        assertEquals(listOf("docker *"), asks)
        compose.onNodeWithTag("allowed-remove-dialog").assertDoesNotExist()
    }

    @Test
    fun theRemoveDialogNamesTheCommandAndConfirmsOrCancels() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG, confirmRemove = "docker *"))

        compose.onNodeWithText("Remove from the allowlist?").assertExists()
        compose.onNode(hasAnyAncestor(hasTestTag("allowed-remove-dialog")) and hasText("docker *", substring = true)).assertExists()
        compose.onNodeWithTag("allowed-remove-confirm").performClick()
        assertEquals(1, confirms)
        compose.onNodeWithTag("allowed-remove-cancel").performClick()
        assertNull(asks.last())
    }

    @Test
    fun anEmptyAllowlistSaysSo() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG.copy(allowlist = emptyList())))

        compose.onNodeWithTag("allowed-none").performScrollTo().assertExists()
        compose.onNodeWithTag("allowed-0").assertDoesNotExist()
    }

    @Test
    fun aRemovalWaitingForTheGatewayShowsTheNoteAndRestart() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG, nextStart = true))

        compose.onNodeWithTag("settings-next-start").performScrollTo().assertExists()
        compose.onNodeWithTag("settings-restart").performScrollTo().performClick()
        assertEquals(1, restarts)
    }

    @Test
    fun withNothingWaitingThereIsNoRestart() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG, nextStart = false))

        compose.onNodeWithTag("settings-next-start").assertDoesNotExist()
        compose.onNodeWithTag("settings-restart").assertDoesNotExist()
    }

    @Test
    fun theMemorySwitchesShowTheCoresStateAndHandOverOneField() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG.copy(profileEnabled = false)))

        compose.onNodeWithTag("memory-switch").performScrollTo().assertIsOn()
        compose.onNodeWithTag("profile-switch").performScrollTo().assertIsOff()
        compose.onNodeWithTag("memory-switch").performScrollTo().performClick()

        assertEquals(listOf(SettingsEdit(memoryEnabled = false)), edits)
    }

    @Test
    fun aSizeLimitIsSavedAlone() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG))

        compose.onNodeWithTag("profile-chars").performScrollTo().performTextReplacement("500")
        compose.onNodeWithTag("profile-chars-save").performScrollTo().performClick()

        assertEquals(listOf(SettingsEdit(profileChars = 500)), edits)
        compose.onNodeWithTag("settings-new-chats").performScrollTo().assertExists()
    }

    @Test
    fun aRefusalShowsTheCoresWordsAndBusyDisablesEveryControl() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG, busy = true, error = "The approval timeout must be at least 1 second."))

        compose.onNodeWithText("The approval timeout must be at least 1 second.").performScrollTo().assertExists()
        listOf("approvals-off", "memory-switch", "approvals-timeout-save", "allowed-remove-0").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        clickableUnder("personality").assertIsNotEnabled()
    }

    @Test
    fun theProvidersSectionListsEachConnectedOneWithRemove() {
        show(SettingsUi(ApprovalMode.SMART, "concise", CFG, connected = listOf(NOUS, COPILOT, ENDPOINT)))

        compose.onNodeWithTag("provider-nous").performScrollTo()
        compose.onNode(hasAnyAncestor(hasTestTag("provider-nous")) and hasText("Nous Portal")).assertExists()
        compose.onNodeWithTag("provider-current-copilot").assertExists()
        compose.onNodeWithTag("provider-current-nous").assertDoesNotExist()
        compose.onNodeWithTag("provider-remove-dgx").assertDoesNotExist()
        compose.onNodeWithTag("provider-managed-dgx").assertExists()
        compose.onNode(hasContentDescription("Remove Nous Portal")).assertExists()
        compose.onNodeWithTag("provider-remove-nous").performScrollTo().performClick()

        assertEquals(listOf<String?>("nous"), providerAsks)
        compose.onNodeWithTag("provider-remove-dialog").assertDoesNotExist()
    }

    @Test
    fun theProviderDialogNamesItAndSaysWhatRemovingTheOneInUseDoes() {
        var state by mutableStateOf(SettingsUi(ApprovalMode.SMART, "concise", CFG, connected = listOf(NOUS, COPILOT, ENDPOINT),
                                               confirmProvider = "copilot"))
        compose.setContent { SettingsScreen(state, actions, onClose = {}) }

        compose.onNodeWithText("Remove GitHub Copilot?").assertExists()
        compose.onNodeWithTag("provider-remove-current").assertExists()
        compose.onNodeWithTag("provider-remove-confirm").performClick()
        assertEquals(1, providerConfirms)
        compose.onNodeWithTag("provider-remove-cancel").performClick()
        assertNull(providerAsks.last())

        state = state.copy(confirmProvider = "nous")
        compose.onNodeWithText("Remove Nous Portal?").assertExists()
        compose.onNodeWithTag("provider-remove-current").assertDoesNotExist()
    }

    @Test
    fun anEmptyOrUnreadProviderListSaysSoAndBusyDisablesRemove() {
        var state by mutableStateOf(SettingsUi(ApprovalMode.SMART, "concise", CFG, connected = emptyList()))
        compose.setContent { SettingsScreen(state, actions, onClose = {}) }

        compose.onNodeWithTag("providers-none").performScrollTo().assertExists()

        state = state.copy(connected = null)
        compose.onNodeWithTag("providers").assertDoesNotExist()

        state = state.copy(connected = listOf(NOUS), busy = true)
        compose.onNodeWithTag("provider-remove-nous").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theSkillsRowOpensTheSkillsPage() {
        var state by mutableStateOf(SettingsUi(ApprovalMode.SMART))
        compose.setContent { SettingsScreen(state, actions, onClose = {}) }

        clickableUnder("settings-skills").performScrollTo().performClick()
        assertEquals(1, skillsOpened)

        state = state.copy(busy = true)
        clickableUnder("settings-skills").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aChoiceMadeInTheAppIsTheCoresMode() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        val before = ready.gateway.call(Methods.CONFIG_GET, ConfigGetParams(key = "approvals.mode")).value
        try {
            ready.pages.settings.open()
            ready.pages.settings.approvals(ApprovalMode.MANUAL)

            assertEquals("manual", ready.gateway.call(Methods.CONFIG_GET, ConfigGetParams(key = "approvals.mode")).value)
            val config = File(app.filesDir, ".hermes/config.yaml").readText()
            assertTrue(config, Regex("""approvals:\s*\n(\s+.*\n)*?\s+mode:\s*manual""").containsMatchIn(config))
        } finally {
            ready.pages.settings.approvals(ApprovalMode.of(before))
            ready.pages.settings.close()
        }
    }

    /**
     * The personality and the approval timeout, through the page's controller into the real core, and both put back
     * through the same calls (D10: never a removal, never a memory write on the phone).
     */
    @Test
    fun thePageReadsAndWritesThroughTheRealCore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        val settings = ready.pages.settings
        val began = SystemClock.elapsedRealtime()
        settings.open()
        Log.i("SettingsScreenTest", "open() read the mode, the personality and settings_read in ${SystemClock.elapsedRealtime() - began} ms")
        val was = checkNotNull(settings.state.value)
        val config = checkNotNull(was.config) { "settings_read failed: ${was.error}" }
        val names = config.personalities.map { it.name }
        assertTrue(names.toString(), "helpful" in names)
        assertEquals("no personality twice", names.distinct(), names)
        assertTrue(config.approvalTimeoutS >= 1)
        val personality = checkNotNull(was.personality)
        val pick = if (personality == "concise") "helpful" else "concise"
        val entries = ready.chat.state.value.entries.size
        try {
            settings.personality(pick)

            assertEquals(pick, ready.gateway.call(Methods.CONFIG_GET, ConfigGetParams(key = "personality")).value)
            assertEquals(pick, settings.state.value?.personality)
            val yaml = File(app.filesDir, ".hermes/config.yaml").readText()
            assertTrue(yaml, Regex("""display:\s*\n(\s+.*\n)*?\s+personality:\s*'?$pick'?\s*\n""").containsMatchIn(yaml))  // the core may quote it
            assertEquals("the open chat got no pivot", entries, ready.chat.state.value.entries.size)

            settings.edit(SettingsEdit(approvalTimeoutS = config.approvalTimeoutS + 1))
            assertEquals(config.approvalTimeoutS + 1, settings.state.value?.config?.approvalTimeoutS)

            settings.edit(SettingsEdit(approvalTimeoutS = 0))
            assertEquals("The approval timeout must be at least 1 second.", settings.state.value?.error)
            assertEquals(config.approvalTimeoutS + 1, settings.state.value?.config?.approvalTimeoutS)
        } finally {
            settings.personality(personality)
            settings.edit(SettingsEdit(approvalTimeoutS = config.approvalTimeoutS))
            settings.close()
        }
        settings.open()
        val again = checkNotNull(settings.state.value)
        settings.close()
        assertEquals(personality, again.personality)
        assertNotNull(again.config)
        assertEquals(config, again.config)
    }

    private companion object {
        val NOUS = ConnectedProvider("nous", "Nous Portal", removable = true, current = false)
        val COPILOT = ConnectedProvider("copilot", "GitHub Copilot", removable = true, current = true)
        val ENDPOINT = ConnectedProvider("dgx", "DGX", removable = false, current = false)

        /** The JVM tests' `settings_read` answer, as the page receives it. */
        val CFG = ConfigSettings(300, listOf("git status", "docker *"), true, true, 2200, 1375,
                                 listOf(Personality("helpful", "You are a helpful, friendly AI assistant."),
                                        Personality("concise", "You are a concise assistant. Keep responses brief a...")))
    }
}
