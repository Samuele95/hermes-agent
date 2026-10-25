package com.nousresearch.hermes.android

import android.util.Log
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.setup.SshStep
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The CP6 smoke: an SSH host set up on the app's own screen (fingerprint confirmed, the app's key logs in),
 * then a turn whose terminal command runs on that host. `scripts/ssh_smoke.sh` passes the endpoint, the
 * host's port and fingerprint, and a marker only that host holds (`-e sshPort … -e fingerprint … -e marker …`).
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class SshSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)

    @Before
    fun allowNotifications() = steps.allowNotifications()

    @Test
    fun aTurnRunsItsTerminalCommandOnTheHostSetUpOnTheScreen() {
        val port = requireNotNull(steps.args.getString("sshPort")) { "no SSH host given: run scripts/ssh_smoke.sh" }
        val fingerprint = requireNotNull(steps.args.getString("fingerprint"))
        val marker = requireNotNull(steps.args.getString("marker"))
        steps.setUpEndpoint()

        steps.openFromDrawer("open-ssh")
        compose.onNodeWithTag("ssh-host").performTextInput("127.0.0.1")
        compose.onNodeWithTag("ssh-user").performTextInput("hermes")
        compose.onNodeWithTag("ssh-port").performTextReplacement(port)
        compose.onNodeWithTag("ssh-look").performClick()
        compose.waitUntil(60_000) { steps.shown("ssh-trust") }
        compose.onNodeWithText(fingerprint, substring = true).assertExists()  // the key the host printed, before trusting it
        compose.onNodeWithTag("ssh-trust").performClick()
        compose.waitUntil(60_000) { steps.shown("ssh-public-key") }
        val ready = (steps.app.core.value as CoreState.Ready).ssh.state.value?.step as SshStep.Ready
        Log.i(SmokeSteps.TAG, "public key ${ready.publicKey}")  // public: the script compares it with the host's record
        compose.onNodeWithTag("ssh-check").performClick()
        compose.waitUntil(60_000) { steps.shown("ssh-login-ok") || steps.shown("ssh-error") }
        compose.onNodeWithTag("ssh-login-ok").assertExists()
        compose.onNodeWithTag("ssh-close").performClick()
        steps.awaitChat()

        val turn = steps.say("Use the terminal tool to run exactly this command, then tell me its output: cat ~/hermes-marker.txt")

        val reply = turn.filterIsInstance<Entry.Reply>().lastOrNull()
        assertTrue("a terminal tool ran: $turn", turn.any { it is Entry.Tool && it.name == "terminal" })
        assertTrue("the reply carries the host's marker: $reply", reply?.text?.contains(marker) == true)
        compose.onNodeWithText(marker, substring = true).assertExists()  // rendered in the chat, not only in its state
        steps.screenshot("ssh-smoke")
    }
}
