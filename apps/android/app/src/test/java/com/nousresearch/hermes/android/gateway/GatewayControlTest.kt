package com.nousresearch.hermes.android.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The always-on gateway's controls (D23): an observed switch, an opt-in boot start, one battery request. */
class GatewayControlTest {
    private val launched = mutableListOf<String>()
    private var running = GatewayStatus.STOPPED
    private var exempt = false
    private val settings = object : GatewaySettings {
        override var startAtBoot = false
    }
    private val control = GatewayControl(settings, object : GatewayLauncher {
        override fun start() { launched += "start" }
        override fun stop() { launched += "stop" }
    }, observe = { running to null }, batteryExempt = { exempt })

    @Test
    fun theScreenShowsWhatRunsNotAStoredWish() {
        running = GatewayStatus.RUNNING  // e.g. started at boot, or before the app's process was restarted
        control.open()

        assertEquals(GatewayUi(GatewayStatus.RUNNING, startAtBoot = false), control.state.value)

        running = GatewayStatus.STOPPED  // stopped from its notification, or by its own failure
        control.refresh()

        assertEquals(GatewayStatus.STOPPED, control.state.value?.status)
    }

    @Test
    fun turningItOnStartsTheGatewayAndAsksForTheBatteryExemptionOnce() {
        control.open()

        control.turnOn()

        assertEquals(listOf("start"), launched)
        assertEquals(GatewayStatus.STARTING, control.state.value?.status)
        assertTrue(control.state.value!!.askBatteryExemption)
        control.batteryAsked()
        assertFalse(control.state.value!!.askBatteryExemption)
    }

    @Test
    fun anExemptAppIsNotAskedAgain() {
        exempt = true
        control.open()

        control.turnOn()

        assertFalse(control.state.value!!.askBatteryExemption)
    }

    @Test
    fun turningItOffStopsTheGateway() {
        running = GatewayStatus.RUNNING
        control.open()

        running = GatewayStatus.STOPPED
        control.turnOff()

        assertEquals(listOf("stop"), launched)
        assertEquals(GatewayStatus.STOPPED, control.state.value?.status)
    }

    @Test
    fun aBootStartsTheGatewayOnlyWhenTheUserAskedForIt() {
        control.onBoot()
        assertEquals(emptyList<String>(), launched)

        control.open()
        control.setStartAtBoot(true)
        control.onBoot()

        assertTrue(settings.startAtBoot)
        assertTrue(control.state.value!!.startAtBoot)
        assertEquals(listOf("start"), launched)
    }

    @Test
    fun aClosedScreenHasNoState() {
        control.open()
        control.close()
        control.refresh()

        assertNull(control.state.value)
    }

    @Test
    fun restartingARunningGatewayStartsItAgainOnceItHasStopped() {
        running = GatewayStatus.RUNNING
        control.open()

        control.restart()
        control.refresh()  // still draining: the old process has not stopped yet
        assertEquals(listOf("stop"), launched)

        running = GatewayStatus.STOPPED
        control.refresh()
        assertEquals(listOf("stop", "start"), launched)  // started only after it stopped
        assertEquals(GatewayStatus.STARTING, control.state.value?.status)

        running = GatewayStatus.STARTING  // the new process is coming up
        control.refresh()
        running = GatewayStatus.FAILED  // and fails on its own: that is shown, not restarted again
        control.refresh()
        assertEquals(listOf("stop", "start"), launched)  // started once
        assertEquals(GatewayStatus.FAILED, control.state.value?.status)
    }

    @Test
    fun restartingAGatewayThatDoesNotRunJustStartsIt() {
        for (status in listOf(GatewayStatus.STOPPED, GatewayStatus.FAILED)) {
            launched.clear()
            running = status
            control.open()

            control.restart()

            assertEquals(listOf("start"), launched)
        }
    }

    @Test
    fun aRestartWaitsUntilTheOldGatewayHasEnded() {
        running = GatewayStatus.RUNNING
        control.open()

        control.restart()
        running = GatewayStatus.STOPPING  // stop recorded, the old process still alive
        control.refresh()
        assertEquals(listOf("stop"), launched)

        running = GatewayStatus.STOPPED
        control.refresh()
        assertEquals(listOf("stop", "start"), launched)
    }

    @Test
    fun aRestartFinishesWithTheScreenClosed() {
        running = GatewayStatus.RUNNING

        control.restart()  // the screen may close right after the tap
        assertTrue(control.restarting)
        running = GatewayStatus.STOPPED
        control.refresh()

        assertEquals(listOf("stop", "start"), launched)
        assertFalse(control.restarting)
    }

    @Test
    fun turningItOffDuringARestartKeepsItOff() {
        running = GatewayStatus.RUNNING
        control.open()

        control.restart()
        control.turnOff()
        running = GatewayStatus.STOPPED
        control.refresh()

        assertEquals(listOf("stop", "stop"), launched)  // no start after the user turned it off
        assertEquals(GatewayStatus.STOPPED, control.state.value?.status)
    }

    @Test
    fun turningItOnWhileTheOldGatewayStillStopsStartsItOnceThatOneHasEnded() {
        running = GatewayStatus.STOPPING
        control.open()

        control.turnOn()
        assertEquals(emptyList<String>(), launched)  // not into the process that is about to end

        running = GatewayStatus.STOPPED
        control.refresh()
        assertEquals(listOf("start"), launched)
    }

    @Test
    fun aRestartGivenUpOnStartsNothingLater() {
        running = GatewayStatus.RUNNING
        control.open()
        control.restart()

        control.abandonPendingStart()  // the old gateway did not end in time
        running = GatewayStatus.STOPPED
        control.refresh()

        assertEquals(listOf("stop"), launched)
        assertFalse(control.restarting)
    }

    @Test
    fun aRestartInProgressKeepsSayingSo() {
        running = GatewayStatus.RUNNING
        control.open()

        control.restart()
        running = GatewayStatus.STOPPING
        control.refresh()

        assertEquals(GatewayStatus.RESTARTING, control.state.value?.status)
    }
}
