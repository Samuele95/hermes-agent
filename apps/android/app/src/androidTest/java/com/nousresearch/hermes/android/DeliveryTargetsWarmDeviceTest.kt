package com.nousresearch.hermes.android

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PingParams
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The job form's first `cron_delivery_targets` after a fresh start answers warm (/architect 20261113T220113Z #2).
 * It means something only as the process's first call. Run it by class: `am instrument` starts a fresh app process,
 * and in the full suite an earlier class may already have warmed it.
 */
@RunWith(AndroidJUnit4::class)
class DeliveryTargetsWarmDeviceTest {
    private companion object {
        /** A user's first New job comes seconds after the app opens; the warm takes about 3 s on the phone. */
        const val SETTLE_MS = 10_000L
    }

    @Test
    fun theFirstDeliveryTargetsReadAfterAStartIsWarm() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val state = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } }
        val gateway = (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway
        val readyMs = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        val pingMs = measureTimeMillis { gateway.call(Methods.PING, PingParams) } // while the warm runs

        delay(SETTLE_MS)
        val ms = measureTimeMillis { EmbeddedBridge.ask(BridgeCalls.CRON_DELIVERY_TARGETS) }
        val pssKb = Debug.getPss()

        Log.i(
            "K1Probe",
            "core ready $readyMs ms after the process started; ping during the warm $pingMs ms; " +
                "first cron_delivery_targets $ms ms; pss $pssKb kB",
        )
        assertTrue("the first cron_delivery_targets held hermes-python $ms ms", ms < 300)
    }
}
