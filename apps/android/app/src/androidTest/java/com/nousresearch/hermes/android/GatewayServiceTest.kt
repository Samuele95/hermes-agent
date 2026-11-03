package com.nousresearch.hermes.android

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.gateway.GatewayRecord
import com.nousresearch.hermes.android.gateway.GatewayService
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.bootCount
import com.nousresearch.hermes.android.gateway.writeGatewayRecord
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The optional always-on gateway (D12, D23) runs in its own `:gateway` process: its own interpreter, the
 * app's home, a specialUse foreground service with a notification. A planned stop drains it and ends the
 * process; a killed process comes back by itself (sticky service). On Samsung, Freecess can hold the sticky
 * restart (L21): that test then skips with the reason. A gateway that died with nothing to restart it is started
 * again when the app opens (D38).
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

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .use { it.readBytes().decodeToString() }

    /** The package's last Freecess record, when it holds sticky restarts (L21); never more arguments to the dump. */
    private fun freecessHold(): FreecessRecord? =
        FreecessHistory.lastRecord(shell("dumpsys activity mars"), context.packageName, context.applicationInfo.uid)
            ?.takeIf { it.holdsStickyRestarts }

    /** Clears the log the system writes about the gateway's service, just before a planned stop. */
    private fun beforePlannedStop() {
        shell("logcat -c")
    }

    /**
     * A planned stop stays a stop: Android must neither see a crash of the sticky service (a kill before the system
     * had the service as stopped) and schedule its restart, nor bring the process back.
     */
    private fun staysStopped() {
        val until = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < until) {
            assertNull("the gateway came back after a planned stop", gatewayPid())
            SystemClock.sleep(100)
        }
        noCrashRestart()
    }

    /** Since [beforePlannedStop], the system scheduled no crash-restart of the gateway's service. */
    private fun noCrashRestart() {
        val restarts = shell("logcat -d -s ActivityManager").lines()
            .filter { "Scheduling restart of crashed service" in it && "GatewayService" in it }
        assertEquals("a planned stop read as a crash: $restarts", emptyList<String>(), restarts)
    }

    /** No `:gateway` process appears for [ms] milliseconds. */
    private fun staysAbsent(ms: Long, message: String = "a gateway started that should have stayed stopped") {
        val until = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < until) {
            assertNull(message, gatewayPid())
            SystemClock.sleep(100)
        }
    }

    /** The gateway's own record: running, and written by the `:gateway` process. */
    private fun runningIn(pid: Int?): Boolean =
        pid != null && state()?.let { it.optString("gateway_state") == "running" && it.optInt("pid") == pid } == true

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // A record left by an earlier, interrupted run would revive a gateway into these tests when the app opens.
        // Only GatewayService writes the record in the app (D38); test code may, to set up a case.
        if (gatewayPid() == null) writeGatewayRecord(context, GatewayRecord(GatewayRecord.State.STOPPED, 0))
    }

    @After
    fun stopTheGateway() {
        if (gatewayPid() == null) return  // a Stop would start the process only to end it
        beforePlannedStop()
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
        beforePlannedStop()
        control.turnOff()

        waitFor("the controls to see it stopped", 60) { control.refresh(); control.state.value?.status?.takeIf { it == GatewayStatus.STOPPED } }
        waitFor("the :gateway process to end", 30) { if (gatewayPid() == null) true else null }
        staysStopped()
        control.close()
    }

    @Test
    fun aRestartFromTheControlsBringsUpANewGatewayProcess() {
        val control = (context.applicationContext as HermesApplication).gateway
        control.open()
        control.turnOn()
        waitFor("the controls to see it serving") { control.refresh(); control.state.value?.status?.takeIf { it == GatewayStatus.RUNNING } }
        val before = waitFor("the :gateway process") { gatewayPid() }

        beforePlannedStop()
        control.restart()

        // A fresh interpreter, so the gateway reads its token and settings again.
        val after = waitFor("a new gateway serving") {
            control.refresh()
            gatewayPid()?.takeIf { it != before && control.state.value?.status == GatewayStatus.RUNNING }
        }
        assertNotEquals(before, after)
        noCrashRestart()  // the new gateway started once the old process had ended, not inside it
        beforePlannedStop()
        control.turnOff()
        waitFor("the controls to see it stopped", 60) { control.refresh(); control.state.value?.status?.takeIf { it == GatewayStatus.STOPPED } }
        waitFor("the :gateway process to end", 30) { if (gatewayPid() == null) true else null }
        staysStopped()
        control.close()
    }

    /** START_STICKY brings `:gateway` back; skipped while Samsung's Freecess holds it (D38). */
    @Test
    fun aKilledGatewayProcessComesBackByItself() {
        context.startForegroundService(GatewayService.start(context))
        val first = waitFor("the gateway to run") { gatewayPid()?.takeIf(::runningIn) }
        val hold = freecessHold()
        assumeFalse(
            "Samsung Freecess holds sticky restarts for ${context.packageName} until it is next unfrozen for another " +
                "reason (last record: ${hold?.line}); wake the phone and open the app once (L21)",
            hold != null,
        )

        Process.killProcess(first)

        val second = waitFor("the gateway to run again", 180) { gatewayPid()?.takeIf { it != first && runningIn(it) } }
        assertNotEquals(first, second)
    }

    @Test
    fun aGatewayRecordedRunningWithNoProcessIsStartedWhenTheAppOpens() {
        // Its writer gone without a recorded stop (no such pid lives), in this boot: it died unplanned.
        writeGatewayRecord(context, GatewayRecord(GatewayRecord.State.RUNNING, Int.MAX_VALUE, boot = bootCount(context)))

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("the revived gateway to run", 120) { gatewayPid()?.takeIf(::runningIn) }
        }
    }

    @Test
    fun aPlannedStopStaysStoppedWhenTheAppOpens() {
        writeGatewayRecord(context, GatewayRecord(GatewayRecord.State.STOPPED, Int.MAX_VALUE, boot = bootCount(context)))

        ActivityScenario.launch(MainActivity::class.java).use {
            staysAbsent(5_000)
        }
    }

    @Test
    fun aGatewayFromBeforeTheLastBootIsLeftToTheBootSetting() {
        val boot = bootCount(context)
        assumeTrue("Settings.Global.BOOT_COUNT cannot be read here", boot != null)
        writeGatewayRecord(context, GatewayRecord(GatewayRecord.State.RUNNING, Int.MAX_VALUE, boot = boot!! - 1))

        ActivityScenario.launch(MainActivity::class.java).use {
            staysAbsent(5_000)
        }
    }

    /** D38's case: Samsung's Freecess holds the sticky restart of a killed gateway, and the app brings it back. */
    @Test
    fun aGatewayKilledWhileSamsungHoldsItsRestartComesBackWhenTheAppOpens() {
        context.startForegroundService(GatewayService.start(context))
        val first = waitFor("the gateway to run") { gatewayPid()?.takeIf(::runningIn) }
        assumeTrue("needs Samsung's Freecess holding sticky restarts for ${context.packageName} (recipe A in briefs/G2)",
                   freecessHold() != null)

        Process.killProcess(first)
        // A kill is asynchronous: the dying process stays listed for a moment.
        waitFor("the killed process to end", 10) { if (gatewayPid() != first) true else null }
        staysAbsent(10_000, "the platform restarted it itself: the Freecess reading is wrong")

        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("the gateway to run again", 120) { gatewayPid()?.takeIf { it != first && runningIn(it) } }
        }
    }
}
