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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.settings.Provenance
import com.nousresearch.hermes.android.settings.SkillRow
import com.nousresearch.hermes.android.settings.SkillText
import com.nousresearch.hermes.android.settings.SkillWriteActions
import com.nousresearch.hermes.android.settings.SkillsActions
import com.nousresearch.hermes.android.settings.SkillsNotice
import com.nousresearch.hermes.android.settings.SkillsScreen
import com.nousresearch.hermes.android.settings.SkillsUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val queries = mutableListOf<String>()
    private val reads = mutableListOf<String>()
    private var refreshes = 0
    private var closes = 0
    private val actions = SkillsActions(query = { queries += it }, refresh = { refreshes++ }, read = { reads += it },
                                        closeSkill = { closes++ })

    private val writes = mutableListOf<String>()  // each write action handed over, by name and argument
    private val write = SkillWriteActions(edit = { writes += "edit" }, draft = { writes += "draft:$it" }, cancelEdit = { writes += "cancelEdit" },
                                          save = { writes += "save" }, askArchive = { writes += "askArchive:$it" },
                                          confirmArchive = { writes += "confirmArchive" }, showArchived = { writes += "showArchived" },
                                          hideArchived = { writes += "hideArchived" }, restore = { writes += "restore:$it" })
    private val writing = SkillsActions(query = { queries += it }, refresh = { refreshes++ }, read = { reads += it },
                                        closeSkill = { closes++ }, write = write)

    /** The page with the write actions too, its state held so a test can change it. */
    private fun showWriting(initial: SkillsUi): (SkillsUi) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent { SkillsScreen(state, onEnable = { _, _ -> }, onClose = {}, actions = writing) }
        return { state = it }
    }

    /** The page with the actions, its state held so a test can change it. */
    private fun showWithActions(initial: SkillsUi): (SkillsUi) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent { SkillsScreen(state, onEnable = { names, on -> enables += names to on }, onClose = {}, actions = actions) }
        return { state = it }
    }

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

    @Test
    fun theSearchFieldNarrowsTheListAndSaysWhenNothingMatches() {
        var state by mutableStateOf(SkillsUi(ROWS))
        var with by mutableStateOf<SkillsActions?>(actions)
        compose.setContent { SkillsScreen(state, onEnable = { _, _ -> }, onClose = {}, actions = with) }

        compose.onNodeWithTag("skills-search").performTextInput("ar")
        assertEquals(listOf("ar"), queries)

        state = SkillsUi(ROWS, query = "arx")
        compose.onNodeWithTag("skill-arxiv").assertExists()
        listOf("plain", "codex", "hermes-agent").forEach { compose.onNodeWithTag("skill-$it").assertDoesNotExist() }
        assertTrue(compose.onAllNodes(tagged("skills-category-switch-")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("skill-switch-arxiv").assertExists()

        state = SkillsUi(ROWS, query = "zzz")
        compose.onNode(hasTestTag("skills-no-match") and hasText("Nothing matches “zzz”.")).assertExists()

        state = SkillsUi(ROWS)
        with = null  // M11's page: no search, no menu, no tap to open
        compose.onNodeWithTag("skills-search").assertDoesNotExist()
        compose.onNodeWithTag("skills-menu").assertDoesNotExist()
        assertTrue(compose.onAllNodes(tagged("skill-open-")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun eachRowSaysWhereItCameFromAndHowOftenItRan() {
        showWithActions(SkillsUi(KINDS))

        // The badge and the count sit inside the row's clickable text, which merges them: find them unmerged.
        compose.onNode(hasTestTag("skill-badge-mine") and hasText("Learned"), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("skill-badge-hubbed") and hasText("Hub"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("skill-badge-arxiv", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("skill-badge-ext", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasTestTag("skill-uses-mine") and hasText("×12"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("skill-uses-arxiv", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("skill-uses-hubbed", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theMenuRefreshesAndATapOpensTheSkill() {
        val set = showWithActions(SkillsUi(ROWS))

        compose.onNodeWithTag("skills-menu").performClick()
        compose.onNodeWithTag("skills-refresh").performClick()
        assertEquals(1, refreshes)

        compose.onNodeWithTag("skill-open-arxiv").performScrollTo().performClick()
        assertEquals(listOf("arxiv"), reads)
        compose.onNodeWithTag("skill-switch-arxiv").performScrollTo().performClick()
        assertEquals(listOf("arxiv"), reads)
        assertEquals(listOf(listOf("arxiv") to false), enables)

        set(SkillsUi(ROWS, busy = true))
        ROWS.forEach { compose.onNodeWithTag("skill-open-${it.name}").assertIsNotEnabled() }
    }

    @Test
    fun anOpenSkillShowsItsHeaderAndSkillMd() {
        val arxiv = ROWS.last().copy(uses = 3)
        val set = showWithActions(SkillsUi(ROWS.dropLast(1) + arxiv, open = SkillText("arxiv", SKILL_MD)))

        compose.onNodeWithTag("skill").assertExists()
        compose.onNodeWithTag("skills").assertDoesNotExist()
        compose.onNode(hasTestTag("skill-text") and hasText(SKILL_MD)).assertExists()
        compose.onNode(hasTestTag("skill-description") and hasText("Search arXiv")).assertExists()
        compose.onNode(hasTestTag("skill") and hasAnyDescendant(hasText("Research"))).assertExists()
        compose.onNode(hasTestTag("skill") and hasAnyDescendant(hasText("used 3×"))).assertExists()
        compose.onNodeWithTag("skill-close").performClick()
        assertEquals(1, closes)

        set(SkillsUi(ROWS.dropLast(1) + arxiv.copy(description = "", uses = 0), open = SkillText("arxiv", SKILL_MD)))
        compose.onNode(hasTestTag("skill-description") and hasText("No description.")).assertExists()
        compose.onNode(hasTestTag("skill") and hasAnyDescendant(hasText("used 0×"))).assertDoesNotExist()
    }

    @Test
    fun theRealCoreRefreshesAndReadsABundledSkill() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        val skills = ready.pages.skills
        try {
            skills.open()
            val began = SystemClock.elapsedRealtime()
            skills.refresh()
            Log.i("SkillsScreenTest", "refresh() ran skills.reload + skills_list in ${SystemClock.elapsedRealtime() - began} ms")
            val rows = skills.state.value!!.rows!!
            Log.i("SkillsScreenTest", "provenance: " + rows.groupingBy { it.provenance }.eachCount())
            assertEquals(Provenance.BUNDLED, rows.single { it.name == "hermes-agent" }.provenance)
            assertNull(skills.state.value!!.error)

            skills.read("hermes-agent")

            val ui = skills.state.value!!
            val text = ui.open!!.content
            assertTrue("not a SKILL.md: ${text.take(40)}", text.startsWith("---") && text.contains("name:"))
            assertNull(ui.error)
            assertFalse(ui.busy)
        } finally {
            skills.close()
        }
    }

    @Test
    fun editAndArchiveShowOnlyForAgentAndExternalSkills() {
        var state by mutableStateOf(SkillsUi(KINDS, open = SkillText("mine", "x")))
        var with by mutableStateOf(writing)
        compose.setContent { SkillsScreen(state, onEnable = { _, _ -> }, onClose = {}, actions = with) }

        for (name in listOf("mine", "ext")) {
            state = SkillsUi(KINDS, open = SkillText(name, "x"))
            compose.onNodeWithTag("skill-edit").assertExists()
            compose.onNodeWithTag("skill-archive").assertExists()
        }
        for (name in listOf("arxiv", "hubbed")) {
            state = SkillsUi(KINDS, open = SkillText(name, "x"))
            compose.onNodeWithTag("skill").assertExists()
            compose.onNodeWithTag("skill-edit").assertDoesNotExist()
            compose.onNodeWithTag("skill-archive").assertDoesNotExist()
        }
        state = SkillsUi(KINDS, open = SkillText("mine", "x"))
        with = actions  // no write actions: read only
        compose.onNodeWithTag("skill").assertExists()
        compose.onNodeWithTag("skill-edit").assertDoesNotExist()
        compose.onNodeWithTag("skill-archive").assertDoesNotExist()
    }

    @Test
    fun theEditorHandsOverTheDraftAndKeepsItOnARefusal() {
        val set = showWriting(SkillsUi(KINDS, open = SkillText("mine", "orig", editing = "x")))

        compose.onNode(hasTestTag("skill-editor") and hasText("x")).assertExists()
        compose.onNode(hasTestTag("skill") and hasAnyDescendant(hasText("mine/SKILL.md"))).assertExists()
        compose.onNodeWithTag("skill-edit").assertDoesNotExist()
        compose.onNodeWithTag("skill-editor").performTextReplacement("y")
        assertEquals(listOf("draft:y"), writes)
        compose.onNodeWithTag("skill-save").performClick()
        assertEquals("save", writes.last())

        set(SkillsUi(KINDS, busy = true, open = SkillText("mine", "orig", editing = "x")))
        compose.onNode(hasTestTag("skill-save") and hasText("Saving…")).assertIsNotEnabled()

        set(SkillsUi(KINDS, error = REFUSED_EDIT, open = SkillText("mine", "orig", editing = "x")))
        compose.onNode(hasTestTag("skills-error") and hasText(REFUSED_EDIT)).assertExists()
        compose.onNode(hasTestTag("skill-editor") and hasText("x")).assertExists()
        compose.onNodeWithTag("skill-edit-cancel").performClick()
        assertEquals("cancelEdit", writes.last())
    }

    @Test
    fun archiveAsksInADialogFirst() {
        val set = showWriting(SkillsUi(KINDS, open = SkillText("mine", "x")))

        compose.onNodeWithTag("skill-archive").performClick()
        assertEquals(listOf("askArchive:mine"), writes)
        compose.onNodeWithTag("archive-dialog").assertDoesNotExist()

        set(SkillsUi(KINDS, open = SkillText("mine", "x"), confirmArchive = "mine"))
        compose.onNode(hasTestTag("archive-dialog") and hasAnyDescendant(hasText("Archive mine?")) and
                       hasAnyDescendant(hasText("The skill is archived and can be restored from Archived skills."))).assertExists()
        compose.onNodeWithTag("archive-confirm").performClick()
        assertEquals("confirmArchive", writes.last())
        compose.onNodeWithTag("archive-cancel").performClick()
        assertEquals("askArchive:null", writes.last())
    }

    @Test
    fun theArchivedViewRestoresAndSaysWhenEmpty() {
        val set = showWriting(SkillsUi(KINDS))

        compose.onNodeWithTag("skills-menu").performClick()
        compose.onNodeWithTag("skills-archived-open").performClick()
        assertEquals(listOf("showArchived"), writes)

        set(SkillsUi(KINDS, archived = listOf("old")))
        compose.onNode(hasTestTag("skills-archived") and hasAnyDescendant(hasText("Archived skills"))).assertExists()
        compose.onNodeWithTag("skills-archived-none").assertDoesNotExist()
        compose.onNodeWithTag("skill-restore-old").performClick()
        assertEquals("restore:old", writes.last())

        set(SkillsUi(KINDS, archived = listOf("old"), busy = true))
        compose.onNodeWithTag("skill-restore-old").assertIsNotEnabled()

        set(SkillsUi(KINDS, archived = emptyList()))
        compose.onNodeWithTag("skills-archived-none").assertExists()
        compose.onNodeWithTag("skills-archived-close").performClick()
        assertEquals("hideArchived", writes.last())
    }

    @Test
    fun aNoticeSaysWhatChanged() {
        val set = showWriting(SkillsUi(KINDS, open = SkillText("mine", "x"), notice = SkillsNotice(SkillsNotice.Kind.UPDATED, "mine")))

        compose.onNode(hasTestTag("skills-notice") and hasAnyDescendant(hasText("Skill updated")) and
                       hasAnyDescendant(hasText("mine applies to new chats."))).assertExists()

        set(SkillsUi(KINDS, notice = SkillsNotice(SkillsNotice.Kind.ARCHIVED, "mine")))
        compose.onNode(hasTestTag("skills-notice") and hasAnyDescendant(hasText("Skill archived")) and
                       hasAnyDescendant(hasText("Restorable from Archived skills."))).assertExists()

        set(SkillsUi(KINDS, archived = listOf("older"), notice = SkillsNotice(SkillsNotice.Kind.RESTORED, "old")))
        compose.onNode(hasTestTag("skills-notice") and hasAnyDescendant(hasText("Skill restored")) and
                       hasAnyDescendant(hasText("old applies to new chats."))).assertExists()
    }

    private companion object {
        const val REFUSED_EDIT = "SKILL.md must start with YAML frontmatter (---). See existing skills for format."
        const val SKILL_MD = "---\nname: arxiv\ndescription: Search arXiv\n---\n\n# arxiv\n"

        /** One skill of each provenance. */
        val KINDS = listOf(SkillRow("mine", "Made by the agent", null, true, Provenance.AGENT, 12),
                           SkillRow("hubbed", "From the hub", null, true, Provenance.HUB),
                           SkillRow("arxiv", "Search arXiv", "research", true, Provenance.BUNDLED),
                           SkillRow("ext", "Mounted", "research", true, Provenance.EXTERNAL))

        const val REFUSED = "Skill 'hermes-agent' is essential to Hermes (the agent's own operating manual referenced by " +
                            "the system prompt) and cannot be disabled."

        /** SkillGroupTest's rows: one with no category, one category part off, one all on. */
        val ROWS = listOf(SkillRow("plain", "A plain skill", null, true),
                          SkillRow("codex", "Delegate to Codex", "autonomous-ai-agents", false),
                          SkillRow("hermes-agent", "Hermes's own manual", "autonomous-ai-agents", true),
                          SkillRow("arxiv", "Search arXiv", "research", true))
    }
}
