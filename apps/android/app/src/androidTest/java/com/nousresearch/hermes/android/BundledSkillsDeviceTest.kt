package com.nousresearch.hermes.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SkillsManageParams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The core on the phone offers the bundled skills that run on Linux; those limited to other systems stay hidden. */
@RunWith(AndroidJUnit4::class)
class BundledSkillsDeviceTest {
    @Test
    fun linuxSkillsAreOfferedAndAppleOnlyOnesAreNot() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val state = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } }
        val core = (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway

        val offered = core.call(Methods.SKILLS_MANAGE, SkillsManageParams()).skills.orEmpty().values.flatten()

        // 54 bundled skills list linux; environment-gated ones (sdlc-review: kanban runs only) stay hidden on every host.
        assertTrue("bundled linux skills are offered: ${offered.size} $offered", offered.size >= 50 && "github" in offered)
        assertFalse("apple-only skills stay hidden: $offered", "imessage" in offered || "findmy" in offered)
    }
}
