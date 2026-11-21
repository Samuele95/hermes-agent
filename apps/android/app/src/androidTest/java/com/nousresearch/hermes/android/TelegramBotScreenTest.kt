package com.nousresearch.hermes.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.gateway.GatewayScreen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.GatewayUi
import com.nousresearch.hermes.android.gateway.PlatformLive
import com.nousresearch.hermes.android.gateway.PlatformState
import com.nousresearch.hermes.android.gateway.TelegramActions
import com.nousresearch.hermes.android.gateway.TelegramState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Gateway page's Telegram section: the bot's live line, its switch (on only with a token, D40; off always, Dv54) and
 * Remove the bot.
 */
@RunWith(AndroidJUnit4::class)
class TelegramBotScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = TelegramActions({ calls += "enabled $it" }, { calls += "ask $it" }, { calls += "remove" })

    /** The section's state; a test changes it after [show] (read while composing, so a change shows at once). */
    private var bot by mutableStateOf(TelegramState())

    private fun show(telegram: TelegramState, onTelegram: TelegramActions? = actions) {
        bot = telegram
        compose.setContent {
            GatewayScreen(GatewayUi(GatewayStatus.RUNNING, startAtBoot = false), samsung = false, onOn = {}, onOff = {},
                          onStartAtBoot = {}, onClose = {}, telegram = bot, onRestart = { calls += "restart" },
                          onTelegram = onTelegram)
        }
    }

    @Test
    fun withoutATokenTheSwitchIsOffCannotBeTurnedOnAndSaysToAddATokenFirst() {
        show(TelegramState(tokenHeld = false, enabled = false))

        compose.onNodeWithTag("telegram-switch").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("telegram-switch").performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag("telegram-needs-token").assertExists()
        compose.onNodeWithTag("telegram-add-token").performScrollTo().performClick()
        compose.onNodeWithTag("telegram-token").assertIsFocused()
    }

    @Test
    fun withoutATokenABotLeftOnShowsOnAndCanOnlyBeSwitchedOff() {
        show(TelegramState(tokenHeld = false, enabled = true, live = PlatformLive(PlatformState.NOT_CONFIGURED)))

        compose.onNodeWithTag("telegram-live-not_configured").performScrollTo().assertExists()
        compose.onNodeWithTag("telegram-switch").performScrollTo().assertIsOn().assertIsEnabled().performClick()
        assertEquals("a bot left on without a token can be switched off (Dv54)", listOf("enabled false"), calls)
        compose.onNodeWithTag("telegram-needs-token").assertExists()
        compose.onNodeWithTag("telegram-add-token").assertExists()

        bot = TelegramState(tokenHeld = false, enabled = true, busy = true)
        compose.onNodeWithTag("telegram-switch").performScrollTo().assertIsNotEnabled()

        bot = TelegramState(tokenHeld = false, enabled = false, live = PlatformLive(PlatformState.DISABLED))
        compose.onNodeWithTag("telegram-switch").performScrollTo().assertIsOff().assertIsNotEnabled().performClick()
        assertEquals("once off, no token: not back on (D40)", listOf("enabled false"), calls)
    }

    @Test
    fun withATokenTheSwitchShowsTheSettingAndHandsTheChoiceOver() {
        show(TelegramState(tokenHeld = true, enabled = true))

        compose.onNodeWithTag("telegram-switch").performScrollTo().assertIsOn().assertIsEnabled().performClick()
        assertEquals(listOf("enabled false"), calls)
        compose.onNodeWithTag("telegram-needs-token").assertDoesNotExist()

        bot = TelegramState(tokenHeld = true, enabled = false)
        compose.onNodeWithTag("telegram-switch").performScrollTo().assertIsOff().performClick()
        assertEquals(listOf("enabled false", "enabled true"), calls)
    }

    @Test
    fun whileBusyTheSwitchAndRemoveWait() {
        show(TelegramState(tokenHeld = true, enabled = true, busy = true))

        compose.onNodeWithTag("telegram-switch").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("telegram-remove").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun removeIsOfferedOnlyForAHeldTokenAndAsksFirst() {
        show(TelegramState(tokenHeld = false))
        compose.onNodeWithTag("telegram-remove").assertDoesNotExist()

        bot = TelegramState(tokenHeld = true)
        compose.onNodeWithTag("telegram-remove").performScrollTo().performClick()

        assertEquals(listOf("ask true"), calls)
        compose.onNodeWithTag("telegram-remove-dialog").assertDoesNotExist()  // until the state says so
    }

    @Test
    fun theConfirmationRemovesOrCancels() {
        show(TelegramState(tokenHeld = true, confirmRemove = true))

        compose.onNodeWithTag("telegram-remove-dialog").assertExists()
        compose.onNodeWithText("This deletes the bot's token from this phone and switches the bot off.", substring = true).assertExists()
        compose.onNodeWithTag("telegram-remove-confirm").performClick()
        assertEquals(listOf("remove"), calls)

        // Shown afresh (a new state object, the same question).
        bot = TelegramState(tokenHeld = true, confirmRemove = true, allowedUser = "42")
        compose.onNodeWithTag("telegram-remove-cancel").performClick()
        assertEquals(listOf("remove", "ask false"), calls)
    }

    @Test
    fun aChangeForTheNextStartOffersRestart() {
        show(TelegramState(tokenHeld = true, nextStart = true))

        compose.onNodeWithTag("telegram-next-start").performScrollTo().assertExists()
        compose.onNodeWithTag("telegram-restart").performScrollTo().performClick()
        assertEquals(listOf("restart"), calls)

        bot = TelegramState(tokenHeld = true, nextStart = false)
        compose.onNodeWithTag("telegram-next-start").assertDoesNotExist()
        compose.onNodeWithTag("telegram-restart").assertDoesNotExist()
    }

    /** Restart waits with the switch, Save and Remove while a save, a switch or a removal runs; the note stays (N5's FOUND). */
    @Test
    fun whileBusyRestartWaitsAndTheNoteStays() {
        show(TelegramState(tokenHeld = true, enabled = true, nextStart = true, busy = true))  // a switch or a removal runs

        compose.onNodeWithTag("telegram-next-start").performScrollTo().assertExists()
        compose.onNodeWithTag("telegram-restart").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals("a change runs: no restart", emptyList<String>(), calls)

        bot = TelegramState(tokenHeld = true, enabled = true, nextStart = true)
        compose.onNodeWithTag("telegram-restart").performScrollTo().assertIsEnabled().performClick()
        assertEquals("idle: Restart restarts", listOf("restart"), calls)

        bot = TelegramState(tokenHeld = false, enabled = false, nextStart = true, busy = true)  // after a removal, a new token saves
        compose.onNodeWithTag("telegram-next-start").performScrollTo().assertExists()
        compose.onNodeWithTag("telegram-restart").performScrollTo().assertIsNotEnabled().performClick()
        assertEquals("a save runs: no restart", listOf("restart"), calls)
    }

    @Test
    fun theLiveLineShowsTheStateAndTheCoresWords() {
        show(TelegramState(tokenHeld = true, live = PlatformLive(PlatformState.FATAL, "Conflict: terminated by other getUpdates request")))

        compose.onNodeWithTag("telegram-live-fatal").performScrollTo().assertExists()
        compose.onNodeWithText("Error Conflict: terminated by other getUpdates request").assertExists()

        bot = TelegramState(tokenHeld = true, live = PlatformLive(PlatformState.GATEWAY_OFF))
        compose.onNodeWithTag("telegram-live-gateway_off").performScrollTo().assertExists()
        compose.onNodeWithText("The gateway is off").assertExists()
    }

    @Test
    fun withoutBotActionsTheSectionIsAsBefore() {
        show(TelegramState(tokenHeld = true), onTelegram = null)

        compose.onNodeWithTag("telegram-switch").assertDoesNotExist()
        compose.onNodeWithTag("telegram-remove").assertDoesNotExist()
        compose.onNodeWithTag("telegram-needs-token").assertDoesNotExist()
        compose.onNodeWithTag("telegram-save").performScrollTo().assertExists()
    }
}
