package com.nousresearch.hermes.android

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PingParams
import com.nousresearch.hermes.bridge.protocol.SkillsReloadParams
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A request sent while `skills.reload` rescans is answered at once: the core runs the rescan on its RPC pool, not
 * inline on `hermes-python`, the app's one thread into the interpreter (Skills Refresh, an accepted write, a hub
 * install). Inline, a Stop, an approval answer or a send waited about 1.2 s behind every reload. Run it by class.
 */
@RunWith(AndroidJUnit4::class)
class SkillsReloadDeviceTest {
    private companion object {
        /** The start-up warms (delivery targets, skills) are done by then, so they hold no thread. */
        const val SETTLE_MS = 10_000L

        /** A ping answered at once takes ~4 ms on the phone; behind an inline reload ~240 ms. */
        const val MAX_PING_MS = 100L
    }

    @Test
    fun aPingIsAnsweredWhileSkillsReloadRuns() = runBlocking {
        withTimeout(60_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = app.core.first { it !is CoreState.Starting }
            val gateway = (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway
            delay(SETTLE_MS)

            // call() posts its frame before it first suspends, so the reload is queued on hermes-python before the ping.
            val reload = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                gateway.call(Methods.SKILLS_RELOAD, SkillsReloadParams())
                SystemClock.elapsedRealtime()
            }
            val began = SystemClock.elapsedRealtime()
            gateway.call(Methods.PING, PingParams)
            val pingAt = SystemClock.elapsedRealtime()
            val reloadAt = reload.await()

            val pingMs = pingAt - began
            val reloadMs = reloadAt - began
            Log.i("Q2Probe", "ping $pingMs ms, skills.reload $reloadMs ms")
            // Inline, the ping is answered only after the reload (a session-less rescan takes ~250 ms on the phone, so
            // both numbers come out alike); pooled, within a few ms. Both bounds sit well apart from either case.
            assertTrue("ping waited $pingMs ms behind skills.reload on hermes-python", pingMs < MAX_PING_MS)
            assertTrue("the ping ($pingMs ms) was not answered while the reload ($reloadMs ms) ran", pingMs * 2 < reloadMs)
        }
    }
}
