package com.nousresearch.hermes.android

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.setup.SshScreen
import com.nousresearch.hermes.android.setup.SshState
import com.nousresearch.hermes.android.setup.SshStep
import com.nousresearch.hermes.android.setup.SshTarget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The SSH settings screen shows each step of the setup and hands the user's choices over. */
@RunWith(AndroidJUnit4::class)
class SshScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val target = SshTarget("host.example", "delta", 2222)
    private val calls = mutableListOf<String>()

    private fun show(state: SshState) = compose.setContent {
        SshScreen(state, onLook = { calls += "look $it" }, onTrust = { calls += "trust" }, onCheck = { calls += "check" },
                  onCopy = { calls += "copy $it" }, onClose = { calls += "close" })
    }

    @Test
    fun aHostIsLookedAtOnlyOnceHostUserAndPortAreFilledIn() {
        show(SshState())
        compose.onNodeWithTag("ssh-look").assertIsNotEnabled()

        compose.onNodeWithTag("ssh-host").performTextInput("host.example")
        compose.onNodeWithTag("ssh-user").performTextInput("delta")
        compose.onNodeWithTag("ssh-port").performTextReplacement("70000")
        compose.onNodeWithTag("ssh-look").assertIsNotEnabled()
        compose.onNodeWithTag("ssh-port").performTextReplacement("2222")
        compose.onNodeWithTag("ssh-look").performClick()

        assertEquals(listOf("look $target"), calls)
    }

    @Test
    fun aNewHostShowsItsFingerprintAndIsTrustedOnlyOnConfirmation() {
        show(SshState(SshStep.Fingerprint(target, "ssh-ed25519 AAAAHOST", "SHA256:abc123")))

        compose.onNodeWithText("SHA256:abc123", substring = true).assertExists()
        compose.onNodeWithTag("ssh-trust").performClick()

        assertEquals(listOf("trust"), calls)
    }

    @Test
    fun aReadyHostShowsThePublicKeyToCopyAndOffersALoginCheck() {
        show(SshState(SshStep.Ready(target, "ssh-ed25519 AAAAPUB hermes-android")))

        compose.onNodeWithText("ssh-ed25519 AAAAPUB hermes-android").assertExists()
        compose.onNodeWithTag("ssh-copy").performClick()
        compose.onNodeWithTag("ssh-check").performClick()
        compose.onNodeWithTag("ssh-login-ok").assertDoesNotExist()

        assertEquals(listOf("copy ssh-ed25519 AAAAPUB hermes-android", "check"), calls)
    }

    @Test
    fun aCheckedLoginIsShown() {
        show(SshState(SshStep.Ready(target, "ssh-ed25519 AAAAPUB hermes-android", loginOk = true)))

        compose.onNodeWithTag("ssh-login-ok").assertExists()
    }

    @Test
    fun theCoresWordsAreShownAndNothingIsClickableWhileBusy() {
        show(SshState(SshStep.Fingerprint(target, "k", "SHA256:x"), busy = true, error = "host.example refused the app's key"))

        compose.onNodeWithTag("ssh-error").assertExists()
        compose.onNodeWithTag("ssh-trust").assertIsNotEnabled()
        compose.onNodeWithTag("ssh-close").assertIsEnabled()
    }
}
