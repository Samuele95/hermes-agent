package com.nousresearch.hermes.android

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.settings.SkillRow
import com.nousresearch.hermes.android.settings.SkillsScreen
import com.nousresearch.hermes.android.settings.SkillsUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The skills page shows the core's skills by category, each category's switch and each skill's own, and hands a flip
 * over as names; on the device, the page reads the real core's list.
 */
@RunWith(AndroidJUnit4::class)
class SkillsScreenTest {
    @get:Rule
    val compose = createComposeRule()
    private val enables = mutableListOf<Pair<List<String>, Boolean>>()

    private fun show(state: SkillsUi) =
        compose.setContent { SkillsScreen(state, onEnable = { names, on -> enables += names to on }, onClose = {}) }

    /** A node whose test tag starts with [prefix]. */
    private fun tagged(prefix: String) =
        SemanticsMatcher("test tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    @Test
    fun eachCategoryIsAHeadingWithItsSkills() {
        show(SkillsUi(ROWS))

        mapOf("general" to "GENERAL", "autonomous-ai-agents" to "AUTONOMOUS-AI-AGENTS", "research" to "RESEARCH").forEach { (key, label) ->
            compose.onNode(hasTestTag("skills-category-$key") and hasAnyDescendant(hasText(label))).assertExists()
        }
        compose.onNode(hasTestTag("skill-arxiv") and hasAnyDescendant(hasText("arxiv")) and hasAnyDescendant(hasText("Search arXiv")))
            .assertExists()
        compose.onNodeWithTag("skills-new-chats").assertExists()
    }

    @Test
    fun aSkillsSwitchShowsItsStateAndHandsOverTheOther() {
        show(SkillsUi(ROWS))

        compose.onNodeWithTag("skill-switch-arxiv").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("skill-switch-codex").performScrollTo().assertIsOff().performClick()

        assertEquals(listOf(listOf("arxiv") to false, listOf("codex") to true), enables)
    }

    @Test
    fun aCategorySwitchIsOnOnlyWhenAllItsSkillsAreOnAndSendsTheOnesThatDiffer() {
        show(SkillsUi(ROWS))

        compose.onNodeWithTag("skills-category-switch-autonomous-ai-agents").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("skills-category-switch-research").performScrollTo().assertIsOn().performClick()
        compose.onNode(hasContentDescription("All Research skills")).assertExists()

        assertEquals(listOf(listOf("codex") to true, listOf("arxiv") to false), enables)
    }

    @Test
    fun theCoresWordsShowAndTheRefusedSwitchStaysOn() {
        show(SkillsUi(ROWS, error = REFUSED))

        compose.onNode(hasTestTag("skills-error") and hasText(REFUSED)).assertExists()
        compose.onNodeWithTag("skill-switch-hermes-agent").performScrollTo().assertIsOn()
    }

    @Test
    fun busyDisablesEverySwitch() {
        show(SkillsUi(ROWS, busy = true))

        val switches = compose.onAllNodes(tagged("skill-switch-") or tagged("skills-category-switch-")).fetchSemanticsNodes()
        assertEquals(ROWS.size + 3, switches.size)
        (ROWS.map { "skill-switch-${it.name}" } +
         listOf("general", "autonomous-ai-agents", "research").map { "skills-category-switch-$it" }).forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
        compose.onNodeWithTag("skills-close").assertIsEnabled()
    }

    @Test
    fun anEmptyListSaysSoAndAnUnreadOneShowsNone() {
        var state by mutableStateOf(SkillsUi(rows = emptyList()))
        compose.setContent { SkillsScreen(state, onEnable = { _, _ -> }, onClose = {}) }
        compose.onNodeWithTag("skills-none").assertExists()

        state = SkillsUi(rows = null, error = "x")
        compose.onNodeWithTag("skills-none").assertDoesNotExist()
        compose.onNodeWithTag("skills-error").assertExists()

        state = SkillsUi(rows = null, busy = true)
        compose.onNodeWithTag("skills-none").assertDoesNotExist()
        compose.onNodeWithTag("skills-error").assertDoesNotExist()
    }

    @Test
    fun thePageReadsThroughTheRealCore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        try {
            val began = SystemClock.elapsedRealtime()
            ready.pages.skills.open()
            Log.i("SkillsScreenTest", "open() read skills_list in ${SystemClock.elapsedRealtime() - began} ms")
            val ui = ready.pages.skills.state.value!!
            val rows = ui.rows!!
            assertTrue("only ${rows.size} skills", rows.size >= 50)
            assertTrue("hermes-agent is not listed and on", rows.any { it.name == "hermes-agent" && it.enabled })
            assertNull(ui.error)
        } finally {
            ready.pages.skills.close()
        }
    }

    private companion object {
        const val REFUSED = "Skill 'hermes-agent' is essential to Hermes (the agent's own operating manual referenced by " +
                            "the system prompt) and cannot be disabled."

        /** SkillGroupTest's rows: one with no category, one category part off, one all on. */
        val ROWS = listOf(SkillRow("plain", "A plain skill", null, true),
                          SkillRow("codex", "Delegate to Codex", "autonomous-ai-agents", false),
                          SkillRow("hermes-agent", "Hermes's own manual", "autonomous-ai-agents", true),
                          SkillRow("arxiv", "Search arXiv", "research", true))
    }
}
