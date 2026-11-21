package com.nousresearch.hermes.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PingParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * A request sent while `toolsets_keys` runs is answered at once: the contract marks the read `blocking`, so the
 * bridge runs it on `hermes-python-net`, not on `hermes-python`, the one thread every frame into the core and every
 * quick bridge call takes. On `hermes-python` a Stop, an approval answer or a send waited behind the whole read each
 * time Settings › Tools opened (P14: the page's open took most of a second). It reads only; nothing on the phone
 * changes. Run it by class.
 */
@RunWith(AndroidJUnit4::class)
class ToolsetsKeysDeviceTest {
    private companion object {
        /** The start-up warms (delivery targets, skills) are done by then, so they hold no thread. */
        const val SETTLE_MS = 10_000L

        /** Reads back to back (~30 ms each once settled), so the pings meet a read in progress for about 0.3 s. */
        const val K = 10

        /**
         * Measured on the phone: the slowest of ~25 pings on the network thread 4-6 ms; inline, the slowest of ~10
         * pings 26-31 ms, about one settled read (~30 ms). The bound is twice the one and half the other.
         */
        const val MAX_PING_MS = 13L
    }

    @Test
    fun aPingIsAnsweredWhileToolsetsKeysRuns() = runBlocking {
        withTimeout(120_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = app.core.first { it !is CoreState.Starting }
            val gateway = (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway
            delay(SETTLE_MS)

            val alone = measureTimeMillis { EmbeddedBridge.ask(BridgeCalls.TOOLSETS_KEYS) }
            val durations = mutableListOf<Long>()
            val keys = async(Dispatchers.IO) {
                repeat(K) { durations += measureTimeMillis { EmbeddedBridge.ask(BridgeCalls.TOOLSETS_KEYS) } }
            }
            val pings = mutableListOf<Long>()
            delay(10)
            while (keys.isActive) {
                pings += measureTimeMillis { gateway.call(Methods.PING, PingParams) }
                delay(10)
            }
            keys.await()

            val medianKeys = durations.sorted()[durations.size / 2]
            val maxPing = pings.maxOrNull() ?: 0L
            val medianPing = pings.sorted().getOrElse(pings.size / 2) { 0L }
            Log.i("Q5Probe", "toolsets_keys alone $alone ms; under pings $durations ms, median $medianKeys ms; " +
                "${pings.size} pings, max $maxPing ms, median $medianPing ms")
            assertTrue("only ${pings.size} pings were sent while the reads ran", pings.size >= 5)
            // Inline, every ping queues behind the read in progress, so the slowest ping is about one read; on the
            // network thread a ping waits only for a GIL switch. Both bounds sit well apart from either case.
            assertTrue("a ping waited $maxPing ms behind toolsets_keys on hermes-python", maxPing < MAX_PING_MS)
            assertTrue("the slowest ping ($maxPing ms) was not answered while a read ($medianKeys ms) ran",
                maxPing * 2 < medianKeys)
        }
    }
}
