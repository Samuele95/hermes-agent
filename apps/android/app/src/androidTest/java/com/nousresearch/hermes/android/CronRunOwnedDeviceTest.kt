package com.nousresearch.hermes.android

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.CronRunOwnedParams
import com.nousresearch.hermes.bridge.contract.CronRunOwnedReply
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's `cron_run_owned` over the phone's real core (/architect 20261113T220113Z #4): the core's router (and the
 * `fastapi` it pulls in) imports on the phone, and its answer passes `call()`'s declaration check against the phone's
 * own `state.db`. It adds no job, starts no `:gateway` and sends no prompt. The call's time is logged, never judged
 * (V9): it means the cold import only as the process's first `cron_run_owned`, so run it by class.
 */
@RunWith(AndroidJUnit4::class)
class CronRunOwnedDeviceTest {
    private companion object {
        const val TAG = "CronRunOwnedDeviceTest"

        /** A run's name no job of the phone's core has: the core cannot say whether it is owned. */
        const val UNKNOWN_RUN = "cron_000000000000_20261113_100000"
    }

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication

    private fun <T> waitFor(what: String, seconds: Int = 120, value: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        while (true) {
            value()?.let { return it }
            check(SystemClock.elapsedRealtime() < deadline) { "timed out waiting for $what" }
            SystemClock.sleep(200)
        }
    }

    @Test
    fun aRunThePhonesCoreDoesNotKnowCannotBeTold() {
        waitFor("the core") { app.core.value as? CoreState.Ready }
        lateinit var reply: CronRunOwnedReply
        val ms = measureTimeMillis {
            reply = runBlocking { EmbeddedBridge.ask(BridgeCalls.CRON_RUN_OWNED, CronRunOwnedParams(UNKNOWN_RUN)) }
        }

        Log.i(TAG, "first cron_run_owned $ms ms (the router and fastapi import cold)")
        assertNull(reply.owned)
    }
}
