package com.nousresearch.hermes.android

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.gateway.GatewayScreen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.GatewayUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The always-on gateway's screen shows what runs and hands the user's choices over. */
@RunWith(AndroidJUnit4::class)
class GatewayScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(state: GatewayUi, samsung: Boolean = false) = compose.setContent {
        GatewayScreen(state, samsung, onOn = { calls += "on" }, onOff = { calls += "off" },
                      onStartAtBoot = { calls += "boot $it" }, onClose = { calls += "close" })
    }

    @Test
    fun aStoppedGatewayIsTurnedOnFromItsSwitch() {
        show(GatewayUi(GatewayStatus.STOPPED, startAtBoot = false))

        compose.onNodeWithTag("gateway-switch").assertIsOff().performClick()

        assertEquals(listOf("on"), calls)
    }

    private fun showsOnAndIsTurnedOff(status: GatewayStatus) {
        show(GatewayUi(status, startAtBoot = false))

        compose.onNodeWithTag("gateway-status-${status.name.lowercase()}").assertExists()
        compose.onNodeWithTag("gateway-switch").assertIsOn().performClick()

        assertEquals(listOf("off"), calls)
    }

    @Test
    fun aGatewayComingUpShowsOnAndIsTurnedOff() = showsOnAndIsTurnedOff(GatewayStatus.STARTING)

    @Test
    fun aServingGatewayShowsOnAndIsTurnedOff() = showsOnAndIsTurnedOff(GatewayStatus.RUNNING)

    @Test
    fun startAtBootIsTheUsersChoice() {
        show(GatewayUi(GatewayStatus.STOPPED, startAtBoot = false))

        compose.onNodeWithTag("gateway-boot").assertIsOff().performClick()

        assertEquals(listOf("boot true"), calls)
    }

    @Test
    fun onSamsungTheSleepingAppsHintIsShown() {
        show(GatewayUi(GatewayStatus.STOPPED, startAtBoot = false), samsung = true)

        compose.onNodeWithTag("gateway-samsung-hint").assertExists()
        compose.onNodeWithTag("gateway-close").performClick()

        assertEquals(listOf("close"), calls)
    }

    @Test
    fun elsewhereTheHintIsNotShown() {
        show(GatewayUi(GatewayStatus.STOPPED, startAtBoot = false), samsung = false)

        compose.onNodeWithTag("gateway-samsung-hint").assertDoesNotExist()
    }
}
