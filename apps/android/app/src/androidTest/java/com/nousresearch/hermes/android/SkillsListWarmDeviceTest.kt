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
import com.nousresearch.hermes.bridge.contract.SkillEntry
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Skills page's first `skills_list` after a fresh start answers from the core's cache, which the start-up warm
 * filled beside `hermes-python` (/architect 20261115T185930Z #3). Run it by class: it means something only as the
 * process's first `skills_list`; `am instrument` starts a fresh app process, and in the full suite an earlier class
 * may already have listed the skills. It reads only: no config is written and no skill is turned off.
 */
@RunWith(AndroidJUnit4::class)
class SkillsListWarmDeviceTest {
    private companion object {
        /** A user's first Settings → Skills comes seconds after the app opens; the warm, delivery targets then
         *  skills, takes about 2 s on later cold starts. */
        const val SETTLE_MS = 10_000L

        /** Just past the core's `_SKILLS_CACHE_TTL_SECONDS` (`tools/skills_tool.py:38`). */
        const val CACHE_MS = 31_000L
    }

    @Test
    fun theFirstSkillsListAfterAStartIsWarm() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val state = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } }
        if (state !is CoreState.Ready) error("the core did not start: $state")
        val readyMs = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

        delay(SETTLE_MS)
        var listed: List<SkillEntry> = emptyList()
        val ms = measureTimeMillis { listed = EmbeddedBridge.ask(BridgeCalls.SKILLS_LIST).skills }
        delay(CACHE_MS)
        val afterCacheMs = measureTimeMillis { EmbeddedBridge.ask(BridgeCalls.SKILLS_LIST) } // reported, never asserted

        Log.i(
            "N7Probe",
            "core ready $readyMs ms after the process started; first skills_list $ms ms; " +
                "skills_list after the core's 30 s cache $afterCacheMs ms; skills ${listed.size}; pss ${Debug.getPss()} kB",
        )
        assertTrue("only ${listed.size} skills", listed.size >= 50) // BundledSkillsDeviceTest's floor
        assertTrue("the first skills_list held hermes-python $ms ms", ms < 300)
    }
}
