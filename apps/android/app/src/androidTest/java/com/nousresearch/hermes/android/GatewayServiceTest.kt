package com.nousresearch.hermes.android

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.gateway.GatewayService
import com.nousresearch.hermes.android.gateway.GatewayStatus
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The optional always-on gateway (D12, D23) runs in its own `:gateway` process: its own interpreter, the
 * app's home, a specialUse foreground service with a notification. A planned stop drains it and ends the
 * process; a killed process comes back by itself (sticky service).
 */
@RunWith(AndroidJUnit4::class)
class GatewayServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val status = File(context.filesDir, ".hermes/gateway_state.json")
    private val gatewayProcess = "${context.packageName}:gateway"

    private fun <T> waitFor(what: String, seconds: Int = 120, value: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        while (true) {
            value()?.let { return it }
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting for $what (status: ${state()})" }
            SystemClock.sleep(200)
        }
    }

    private fun state(): JSONObject? = runCatching { JSONObject(status.readText()) }.getOrNull()

    private fun gatewayPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == gatewayProcess }?.pid

    /** The gateway's own record: running, and written by the `:gateway` process. */
    private fun runningIn(pid: Int?): Boolean =
        pid != null && state()?.let { it.optString("gateway_state") == "running" && it.optInt("pid") == pid } == true

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun stopTheGateway() {
        if (gatewayPid() == null) return  // a Stop would start the process only to end it
        context.startService(GatewayService.stop(context))
        waitFor("the gateway process to end", 60) { if (gatewayPid() == null) true else null }
    }

    @Test
    fun theGatewayRunsInItsOwnProcessUntilAPlannedStop() {
        context.startForegroundService(GatewayService.start(context))

        val pid = waitFor("the :gateway process") { gatewayPid() }
        waitFor("the gateway to run in it") { if (runningIn(gatewayPid())) true else null }
        assertNotEquals(Process.myPid(), pid)
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(notifications.any { it.id == GatewayService.NOTIFICATION_ID })

        context.startService(GatewayService.stop(context))

        waitFor("a drained stop") { if (state()?.optString("gateway_state") == "stopped") true else null }
        waitFor("the gateway process to end", 60) { if (gatewayPid() == null) true else null }
    }

    @Test
    fun theAppsControlsDriveAndSeeTheGateway() {
        val control = (context.applicationContext as HermesApplication).gateway
        control.open()
        assertEquals(GatewayStatus.STOPPED, control.state.value?.status)

        control.turnOn()
        waitFor("the controls to see it serving") { control.refresh(); control.state.value?.status?.takeIf { it == GatewayStatus.RUNNING } }
        control.turnOff()

        waitFor("the controls to see it stopped", 60) { control.refresh(); control.state.value?.status?.takeIf { it == GatewayStatus.STOPPED } }
        control.close()
    }

    @Test
    fun aRestartFromTheControlsBringsUpANewGatewayProcess() {
        val control = (context.applicationContext as HermesApplication).gateway
        control.open()
        control.turnOn()
        waitFor("the controls to see it serving") { control.refresh(); control.state.value?.status?.takeIf { it == GatewayStatus.RUNNING } }
        val before = waitFor("the :gateway process") { gatewayPid() }

        control.restart()

        // A fresh interpreter, so the gateway reads its token and settings again.
        val after = waitFor("a new gateway serving") {
            control.refresh()
            gatewayPid()?.takeIf { it != before && control.state.value?.status == GatewayStatus.RUNNING }
        }
        assertNotEquals(before, after)
        control.turnOff()
        waitFor("the controls to see it stopped", 60) { control.refresh(); control.state.value?.status?.takeIf { it == GatewayStatus.STOPPED } }
        control.close()
    }

    @Test
    fun aKilledGatewayProcessComesBackByItself() {
        context.startForegroundService(GatewayService.start(context))
        val first = waitFor("the gateway to run") { gatewayPid()?.takeIf(::runningIn) }

        Process.killProcess(first)

        val second = waitFor("the gateway to run again", 180) { gatewayPid()?.takeIf { it != first && runningIn(it) } }
        assertNotEquals(first, second)
    }
}
