package com.nousresearch.hermes.android

import android.Manifest
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.turn.ForegroundTurnHost
import com.nousresearch.hermes.android.turn.TurnService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A running turn keeps the app in the foreground as a dataSync service, from the first turn to the last. */
@RunWith(AndroidJUnit4::class)
class TurnServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val host = ForegroundTurnHost(context)

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting until $what" }
            SystemClock.sleep(50)
        }
    }

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun endEveryTurn() {
        listOf("t1", "t2").forEach(host::end)
        waitUntil("the service stopped") { TurnService.running == null }
    }

    @Test
    fun aTurnRunsInADataSyncForegroundServiceWithItsNotification() {
        host.begin("t1")

        waitUntil("the service is in the foreground") { TurnService.running?.inForeground == true }
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, TurnService.running!!.foregroundServiceType)
        }
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue(notifications.any { it.id == TurnService.NOTIFICATION_ID })
    }

    @Test
    fun theServiceLastsUntilTheLastTurnEnds() {
        host.begin("t1")
        host.begin("t2")
        waitUntil("the service is in the foreground") { TurnService.running?.inForeground == true }

        host.end("t1")
        SystemClock.sleep(500)
        assertTrue("stopped while t2 still runs", TurnService.running?.inForeground == true)

        host.end("t2")
        waitUntil("the service stopped") { TurnService.running == null }
    }

    @Test
    fun aTurnThatEndsBeforeTheServiceStartsDoesNotCrashTheApp() {
        repeat(5) {
            host.begin("t1")
            host.end("t1")
        }

        waitUntil("the service stopped") { TurnService.running == null }
    }
}
