package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How the agent's risky commands get approved: the core's `approvals.mode`, read and set over the protocol. */
class ApprovalSettingsTest {
    private var mode = "smart"
    private var refuse: String? = null
    private val core = FakeCoreGateway { method, params ->
        when (method) {
            "config.get" -> """{"value":"$mode"}"""
            "config.set" -> {
                refuse?.let { throw CoreError(4002, it) }
                mode = params.getValue("value").jsonPrimitive.content
                """{"key":"approvals.mode","value":"$mode"}"""
            }
            else -> error("unexpected $method")
        }
    }
    private val settings = ApprovalSettings(core)

    @Test
    fun openingShowsTheCoresMode() = runTest {
        settings.open()

        assertEquals(SettingsState(ApprovalMode.SMART), settings.state.value)
        assertEquals("approvals.mode", core.calls.single().second.getValue("key").jsonPrimitive.content)
    }

    @Test
    fun aChosenModeIsWrittenToTheCore() = runTest {
        settings.open()

        settings.choose(ApprovalMode.MANUAL)

        assertEquals("manual", mode)
        assertEquals(ApprovalMode.MANUAL, settings.state.value?.approvals)
    }

    @Test
    fun aRefusedChoiceKeepsTheModeAndSaysWhy() = runTest {
        settings.open()
        refuse = "managed install — settings are read-only"

        settings.choose(ApprovalMode.OFF)

        assertEquals(SettingsState(ApprovalMode.SMART, error = "managed install — settings are read-only"), settings.state.value)
    }

    @Test
    fun closingForgetsTheScreen() = runTest {
        settings.open()

        settings.close()

        assertNull(settings.state.value)
    }
}
