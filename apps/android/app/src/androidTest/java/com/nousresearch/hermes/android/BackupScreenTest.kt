package com.nousresearch.hermes.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.settings.ApprovalMode
import com.nousresearch.hermes.android.settings.BackupActions
import com.nousresearch.hermes.android.settings.BackupContents
import com.nousresearch.hermes.android.settings.BackupFile
import com.nousresearch.hermes.android.settings.BackupPart
import com.nousresearch.hermes.android.settings.BackupScreen
import com.nousresearch.hermes.android.settings.BackupUi
import com.nousresearch.hermes.android.settings.PickedBackup
import com.nousresearch.hermes.android.settings.RestoreActions
import com.nousresearch.hermes.android.settings.RestoreReport
import com.nousresearch.hermes.android.settings.SettingsActions
import com.nousresearch.hermes.android.settings.SettingsScreen
import com.nousresearch.hermes.android.settings.SettingsUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings › Backup (settings-24): what a backup holds and leaves out, Create backup, and the last backup made. */
@RunWith(AndroidJUnit4::class)
class BackupScreenTest {
    private companion object {
        val MADE = BackupFile("/cache/exports/hermes-backup-2026-11-17-101500.zip", "hermes-backup-2026-11-17-101500.zip", 1_201_832L,
                              setOf(BackupPart.SESSIONS, BackupPart.SKILLS, BackupPart.CONFIG, BackupPart.JOBS))
        val FIVE = BackupPart.entries.toSet()
        val HOLDS = BackupContents(FIVE, skipped = 3, sessions = 12)
        val PICKED = PickedBackup("/cache/imports/picked-1.zip", "phone.zip", HOLDS)
    }

    @get:Rule
    val compose = createComposeRule()

    private var creates = 0
    private var closes = 0
    private val actions = BackupActions(create = { creates++ }, close = { closes++ })
    private val restoring = mutableListOf<String>()
    private val withRestore = BackupActions(create = { creates++ }, close = { closes++ },
                                            restore = RestoreActions(pick = { restoring += "pick" }, confirm = { restoring += "confirm" },
                                                                     cancel = { restoring += "cancel" }, restart = { restoring += "restart" }))

    /** The page, its state held so a test can change it. */
    private fun show(initial: BackupUi, with: BackupActions = actions): (BackupUi) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent { BackupScreen(state, with) }
        return { state = it }
    }

    /** The clickable row under [tag] (a ListRow's click sits on its inner row). */
    private fun clickableUnder(tag: String): SemanticsNodeInteraction = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(tag)))

    @Test
    fun settingsOpensTheBackup() {
        var opened = 0
        fun settingsActions(openBackup: (() -> Unit)?) =
            SettingsActions(approvals = {}, personality = {}, edit = {}, askRemove = {}, confirmRemove = {}, restartGateway = {},
                            askRemoveProvider = {}, confirmRemoveProvider = {}, openSkills = {}, copy = {}, reactions = {},
                            openBackup = openBackup)
        var state by mutableStateOf(SettingsUi(ApprovalMode.SMART))
        var with by mutableStateOf(settingsActions { opened++ })
        compose.setContent { SettingsScreen(state, with, onClose = {}) }

        compose.onNodeWithText("BACKUP").performScrollTo()
        clickableUnder("settings-backup").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Back up or restore Hermes data").assertExists()
        clickableUnder("settings-backup").performClick()
        assertEquals(1, opened)

        state = state.copy(busy = true)
        clickableUnder("settings-backup").performScrollTo().assertIsNotEnabled()

        with = settingsActions(null)
        compose.onNodeWithTag("settings-backup").assertDoesNotExist()
        compose.onNodeWithText("BACKUP").assertDoesNotExist()
    }

    @Test
    fun thePageSaysWhatABackupHoldsAndWhatItLeavesOut() {
        val set = show(BackupUi())

        compose.onNodeWithTag("backup").assertExists()
        compose.onNodeWithText("BACK UP").assertExists()
        compose.onNodeWithTag("backup-holds")
            .assertTextEquals("A backup is one zip of this phone's sessions, memories, skills, settings and scheduled jobs.")
        compose.onNodeWithTag("backup-not-included")
            .assertTextEquals("Settings are saved with every key left blank. Keys and sign-ins are not included; on a new phone you sign in again.")
        compose.onNodeWithTag("backup-as-written")
            .assertTextEquals("Sessions are saved as written, so anything pasted into a chat is in the backup too.")
        compose.onNodeWithTag("backup-file").assertDoesNotExist()
        compose.onNodeWithTag("backup-error").assertDoesNotExist()
        compose.onNodeWithTag("backup-create").assertTextEquals("Create backup").assertIsEnabled().performClick()
        assertEquals(1, creates)

        set(BackupUi(busy = true))
        compose.onNodeWithTag("backup-create").assertIsNotEnabled()
        compose.onNodeWithTag("backup-close").assertIsNotEnabled()

        set(BackupUi(file = MADE, made = MADE))
        compose.onNodeWithTag("backup-create").assertIsNotEnabled()  // its zip still waits for the share sheet
        compose.onNodeWithTag("backup-file").assertTextContains("hermes-backup-2026-11-17-101500.zip", substring = true)
            .assertTextContains("sessions, skills, settings, jobs", substring = true)

        set(BackupUi(made = MADE, error = "The backup could not be written: No space left on device"))
        compose.onNodeWithTag("backup-create").assertIsEnabled()
        compose.onNodeWithTag("backup-error").assertTextEquals("The backup could not be written: No space left on device")
        compose.onNodeWithTag("backup-close").assertIsEnabled().performClick()
        assertEquals(1, closes)

        compose.onNodeWithTag("backup-restore").assertDoesNotExist()
    }

    @Test
    fun aPickedBackupAsksBeforeItReplacesAnything() {
        val set = show(BackupUi(), withRestore)

        compose.onNodeWithText("RESTORE").performScrollTo()
        compose.onNodeWithTag("backup-restore-explain").performScrollTo().assertTextEquals(
            "Restoring replaces this phone's sessions, memories, skills, settings and jobs with the backup's. Keys and sign-ins on this phone are kept. Hermes restarts afterwards.")
        compose.onNodeWithTag("backup-restore").performScrollTo().assertTextEquals("Restore from backup…").performClick()
        assertEquals(listOf("pick"), restoring)
        compose.onNodeWithTag("backup-restore-dialog").assertDoesNotExist()

        set(BackupUi(picked = PICKED))
        compose.onNodeWithTag("backup-restore-dialog").assertExists()
        compose.onNodeWithText("Restore from backup?").assertExists()
        compose.onNodeWithText("Replace this phone's sessions, memories, skills, settings and jobs with the backup?").assertExists()
        compose.onNodeWithTag("backup-restore-holds").assertTextEquals("“phone.zip” holds: 12 sessions, memories, skills, settings, jobs.")
        compose.onNodeWithTag("backup-restore-skipped").assertTextEquals("3 other items (keys, sign-ins or other files) are skipped.")
        compose.onNodeWithText("This cannot be undone.").assertExists()
        compose.onNodeWithTag("backup-restore-confirm").assertTextEquals("Restore").performClick()
        compose.onNodeWithTag("backup-restore-cancel").assertTextEquals("Cancel").performClick()
        assertEquals(listOf("pick", "confirm", "cancel"), restoring)

        set(BackupUi(picked = PICKED.copy(holds = HOLDS.copy(skipped = 0, sessions = 1))))
        compose.onNodeWithTag("backup-restore-holds").assertTextEquals("“phone.zip” holds: 1 session, memories, skills, settings, jobs.")
        compose.onNodeWithTag("backup-restore-skipped").assertDoesNotExist()

        set(BackupUi(busy = true, picked = PICKED))
        compose.onNodeWithTag("backup-restore-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("backup-restore-cancel").assertIsNotEnabled()

        set(BackupUi(busy = true, picked = PICKED.copy(holds = null)))  // still being read
        compose.onNodeWithTag("backup-restore-dialog").assertDoesNotExist()
        compose.onNodeWithTag("backup-restore").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aRestoredPhoneOffersOnlyTheRestart() {
        show(BackupUi(made = MADE, report = RestoreReport(FIVE, skipped = 3, sessions = 12)), withRestore)

        compose.onNodeWithTag("backup-report").performScrollTo()
            .assertTextEquals("Restored sessions (12), memories, skills, settings, jobs · 3 items skipped")
        compose.onNodeWithTag("backup-restart-explain").performScrollTo()
            .assertTextEquals("Hermes restarts to load the restored data. On a new phone, add your keys and sign in again.")
        compose.onNodeWithTag("backup-restart").performScrollTo().assertTextEquals("Restart now").performClick()
        assertEquals(listOf("restart"), restoring)
        compose.onNodeWithTag("backup-close").assertIsNotEnabled()
        compose.onNodeWithTag("backup-create").assertDoesNotExist()
        compose.onNodeWithTag("backup-restore").assertDoesNotExist()
        assertEquals(0, closes)
    }
}
