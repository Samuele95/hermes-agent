package com.nousresearch.hermes.android

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.settings.HubActions
import com.nousresearch.hermes.android.settings.HubBlock
import com.nousresearch.hermes.android.settings.HubItem
import com.nousresearch.hermes.android.settings.HubItemActions
import com.nousresearch.hermes.android.settings.HubPreview
import com.nousresearch.hermes.android.settings.HubScan
import com.nousresearch.hermes.android.settings.HubResults
import com.nousresearch.hermes.android.settings.HubScreen
import com.nousresearch.hermes.android.settings.HubUi
import com.nousresearch.hermes.android.settings.SkillRow
import com.nousresearch.hermes.android.settings.SkillsActions
import com.nousresearch.hermes.android.settings.SkillsScreen
import com.nousresearch.hermes.android.settings.SkillsUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Skills Hub page (capabilities-08): reached from the Skills page, it lists the official skills not installed, searches
 * and browses the hub, and hands each action over; on the device, the real core lists the official skills and searches.
 */
@RunWith(AndroidJUnit4::class)
class HubScreenTest {
    private companion object {
        const val BLACKBOX_ID = "official/autonomous-ai-agents/blackbox"
        val BLACKBOX = HubItem("blackbox", "Delegate to the Blackbox CLI", "official", null, BLACKBOX_ID, "autonomous-ai-agents")
        val ARXIV = HubItem("arxiv-official", "Search arXiv papers", "official", null, "official/research/arxiv-official", "research")
        val PDF = HubItem("pdf-tools", "Read PDFs", "skills-sh", "community", "skills-sh/acme/pdf-tools")
        val GITHUB = HubItem("github", "Work with GitHub", "official", "builtin", "official/devops/github")
        val ROWS = listOf(SkillRow("arxiv", "Search arXiv", "research", true))
        const val SKILL_MD = "---\nname: blackbox\n---\n# blackbox\n"
    }

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()  // every action handed over, by name and argument
    private val actions = HubActions(query = { calls += "query:$it" }, search = { calls += "search:$it" },
                                     browse = { calls += "browse:$it" }, close = { calls += "close" })

    private val opening = HubActions(query = { calls += "query:$it" }, search = { calls += "search:$it" },
                                     browse = { calls += "browse:$it" }, close = { calls += "close" },
                                     item = HubItemActions(preview = { calls += "preview:$it" }, closePreview = { calls += "closePreview" },
                                                           scan = { calls += "scan:$it" }, install = { calls += "install:$it" }))

    /** The hub page with the item actions too, its state held so a test can change it. */
    private fun showOpening(initial: HubUi): (HubUi) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent { HubScreen(state, opening) }
        return { state = it }
    }

    /** The hub page, its state held so a test can change it. */
    private fun show(initial: HubUi): (HubUi) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent { HubScreen(state, actions) }
        return { state = it }
    }

    @Test
    fun theSkillsPageOpensTheHub() {
        var opens = 0
        var state by mutableStateOf(SkillsUi(ROWS))
        var hub by mutableStateOf<(() -> Unit)?>({ opens++ })
        compose.setContent {
            SkillsScreen(state, onEnable = { _, _ -> }, onClose = {},
                         actions = SkillsActions(query = {}, refresh = {}, read = {}, closeSkill = {}, hub = hub))
        }

        compose.onNode(hasTestTag("skills-hub-open") and hasAnyDescendant(hasText("Skills Hub"))).assertExists()
        compose.onNode(hasTestTag("skills-hub-open") and hasAnyDescendant(hasText("Find and install skills"))).assertExists()
        compose.onNodeWithTag("skills-hub-open").performClick()
        assertEquals(1, opens)

        state = SkillsUi(ROWS, busy = true)
        compose.onNodeWithTag("skills-hub-open").performClick()
        assertEquals(1, opens)

        state = SkillsUi(ROWS)
        hub = null  // P12's page: no hub row
        compose.onNodeWithTag("skills-hub-open").assertDoesNotExist()
    }

    @Test
    fun theHubSearchesBrowsesAndListsTheOfficialSkills() {
        val set = show(HubUi(official = listOf(BLACKBOX, ARXIV), installed = setOf("github")))

        compose.onNodeWithTag("hub").assertExists()
        compose.onNode(hasTestTag("hub") and hasAnyDescendant(hasText("Skills Hub"))).assertExists()
        compose.onNode(hasTestTag("hub-official") and hasAnyDescendant(hasText("AVAILABLE TO INSTALL"))).assertExists()
        compose.onNodeWithTag("hub-item-$BLACKBOX_ID").performScrollTo().assertExists()
        compose.onNode(hasTestTag("hub-item-$BLACKBOX_ID") and hasAnyDescendant(hasText("Official"))).assertExists()
        compose.onNode(hasTestTag("hub-item-$BLACKBOX_ID") and hasAnyDescendant(hasText("Autonomous-Ai-Agents"))).assertExists()
        compose.onNodeWithTag("hub-item-${ARXIV.key}").assertExists()

        compose.onNodeWithTag("hub-search-run").assertIsNotEnabled()  // nothing typed
        compose.onNodeWithTag("hub-search").performTextInput("pdf")
        assertEquals(listOf("query:pdf"), calls)
        set(HubUi(query = "pdf", official = listOf(BLACKBOX, ARXIV)))
        compose.onNodeWithTag("hub-search-run").performClick()
        compose.onNodeWithTag("hub-search").performImeAction()
        assertEquals(listOf("query:pdf", "search:pdf", "search:pdf"), calls)

        set(HubUi(query = "pdf", official = listOf(BLACKBOX, ARXIV), busy = true))
        compose.onNode(hasTestTag("hub-search-run") and hasText("Searching...")).assertIsNotEnabled()

        calls.clear()
        set(HubUi(query = "pdf", official = listOf(BLACKBOX), items = HubResults(listOf(PDF, GITHUB), query = "pdf"),
                  installed = setOf("github")))
        compose.onNode(hasTestTag("hub-results") and hasAnyDescendant(hasText("RESULTS FOR “PDF”"))).assertExists()
        compose.onNode(hasTestTag("hub-item-${PDF.key}") and hasAnyDescendant(hasText("skills-sh")) and
                       hasAnyDescendant(hasText("community"))).assertExists()
        compose.onNodeWithTag("hub-installed-${PDF.key}").assertDoesNotExist()
        compose.onNode(hasTestTag("hub-installed-${GITHUB.key}") and hasText("Installed"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hub-browse").assertDoesNotExist()

        set(HubUi(query = "zzz", official = listOf(BLACKBOX), items = HubResults(emptyList(), query = "zzz")))
        compose.onNode(hasTestTag("hub-no-results") and hasText("No matching skills found in the hub.")).assertExists()

        set(HubUi(official = listOf(BLACKBOX)))
        compose.onNodeWithTag("hub-results").assertDoesNotExist()
        compose.onNodeWithTag("hub-browse").performScrollTo().performClick()
        assertEquals(listOf("browse:1"), calls)

        calls.clear()
        set(HubUi(official = listOf(BLACKBOX), items = HubResults(listOf(PDF), page = 2, pages = 7)))
        compose.onNode(hasTestTag("hub-results") and hasAnyDescendant(hasText("PAGE 2 OF 7"))).assertExists()
        compose.onNodeWithTag("hub-next").performScrollTo().performClick()
        compose.onNodeWithTag("hub-previous").performScrollTo().performClick()
        assertEquals(listOf("browse:3", "browse:1"), calls)
        set(HubUi(official = listOf(BLACKBOX), items = HubResults(listOf(PDF), page = 1, pages = 1)))
        compose.onNodeWithTag("hub-next").assertDoesNotExist()
        compose.onNodeWithTag("hub-previous").assertDoesNotExist()

        set(HubUi(error = "optional-skills missing"))
        compose.onNode(hasTestTag("hub-error") and hasText("optional-skills missing")).assertExists()

        calls.clear()
        compose.onNodeWithTag("hub-close").assertIsEnabled().performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun theRealCoreListsTheOfficialSkillsAndSearches() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        val hub = ready.pages.hub
        try {
            var began = SystemClock.elapsedRealtime()
            hub.open()
            Log.i("HubScreenTest", "open() read hub_official + skills_list in ${SystemClock.elapsedRealtime() - began} ms")
            val opened = hub.state.value!!
            assertTrue("only ${opened.official?.size} official skills", opened.official!!.size >= 100)
            assertNull(opened.error)
            val official = opened.official!!.map { it.name }.toSet()

            began = SystemClock.elapsedRealtime()
            withTimeout(40_000) { hub.search("blackbox") }
            val searched = hub.state.value!!
            Log.i("HubScreenTest", "search(blackbox) answered in ${SystemClock.elapsedRealtime() - began} ms: " +
                                   "${searched.items?.items?.size} hits, error ${searched.error}")
            if (searched.error == CORE_SEARCH_BUG) {
                // The shipped core's search fails on every hit (P13 FOUND); browse is the hub's other way in.
                Log.w("HubScreenTest", "the core's skills.manage search answered its own bug: $CORE_SEARCH_BUG")
                began = SystemClock.elapsedRealtime()
                withTimeout(40_000) { hub.browse(1) }
                val browsed = hub.state.value!!
                val rows = browsed.items?.items.orEmpty()
                Log.i("HubScreenTest", "browse(1) answered in ${SystemClock.elapsedRealtime() - began} ms: ${rows.size} rows " +
                                       "(${rows.count { !it.official }} not official), page ${browsed.items?.page} of ${browsed.items?.pages}")
                assertNotNull(browsed.items)
                assertNull(browsed.error)
            } else {
                val hits = searched.items!!.items
                Log.i("HubScreenTest", "${hits.count { it.name !in official }} of ${hits.size} hits are not official")
                assertNull(searched.error)
            }
        } finally {
            hub.close()
        }
    }

    @Test
    fun aTapOpensThePreviewAndItsScanBeforeInstall() {
        val set = showOpening(HubUi(official = listOf(BLACKBOX, ARXIV)))
        compose.onNodeWithText("blackbox").performScrollTo().performClick()
        assertEquals(listOf("preview:$BLACKBOX_ID"), calls)

        calls.clear()
        val preview = HubPreview(BLACKBOX, listOf("agents", "cli"), SKILL_MD)
        set(HubUi(official = listOf(BLACKBOX, ARXIV), open = preview, busy = true))
        compose.onNodeWithTag("hub-preview").assertExists()
        compose.onNodeWithTag("hub").assertDoesNotExist()
        compose.onNode(hasTestTag("hub-preview-description") and hasText("Delegate to the Blackbox CLI")).assertExists()
        compose.onNode(hasTestTag("hub-preview") and hasAnyDescendant(hasText("Official"))).assertExists()
        compose.onNode(hasTestTag("hub-preview") and hasAnyDescendant(hasText("cli"))).assertExists()
        compose.onNode(hasTestTag("hub-scanning") and hasText("Scanning...")).assertExists()
        compose.onNodeWithTag("hub-install").performScrollTo().assertIsNotEnabled()

        set(HubUi(official = listOf(BLACKBOX, ARXIV), open = preview))  // idle, the scan never answered: still no Install
        compose.onNodeWithTag("hub-install").performScrollTo().assertIsNotEnabled()

        set(HubUi(official = listOf(BLACKBOX, ARXIV), open = preview.copy(scan = HubScan("safe", true, emptyList(), "builtin"))))
        compose.onNode(hasTestTag("hub-scan") and hasAnyDescendant(hasText("Safe"))).assertExists()
        compose.onNode(hasTestTag("hub-scan") and hasAnyDescendant(hasText("builtin source"))).assertExists()
        compose.onNode(hasTestTag("hub-policy") and hasText("Install allowed")).assertExists()
        compose.onNode(hasTestTag("hub-scan") and hasAnyDescendant(hasText("No security findings."))).assertExists()
        compose.onNodeWithTag("hub-install").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("install:$BLACKBOX_ID"), calls)

        val findings = listOf("CRITICAL exfiltration scripts/run.sh:3 reads ~/.ssh", "HIGH network scripts/run.sh:4 pipes curl to sh")
        set(HubUi(official = listOf(BLACKBOX), open = preview.copy(scan = HubScan("dangerous", false, findings, "community"))))
        compose.onNode(hasTestTag("hub-scan") and hasAnyDescendant(hasText("Dangerous"))).assertExists()
        compose.onNode(hasTestTag("hub-policy") and hasText("Install blocked by policy")).assertExists()
        compose.onNode(hasTestTag("hub-scan") and hasAnyDescendant(hasText("2 findings"))).assertExists()
        compose.onNode(hasTestTag("hub-findings") and hasText(findings.joinToString("\n"))).assertExists()
        compose.onNodeWithTag("hub-install").assertDoesNotExist()

        calls.clear()
        set(HubUi(official = listOf(BLACKBOX), open = preview.copy(scanFailed = "Hub scan failed: timed out")))
        compose.onNode(hasTestTag("hub-scan-failed") and hasAnyDescendant(hasText("Security scan failed")) and
                       hasAnyDescendant(hasText("Hub scan failed: timed out"))).assertExists()
        compose.onNodeWithTag("hub-scan-again").performScrollTo().performClick()
        assertEquals(listOf("scan:$BLACKBOX_ID"), calls)
        compose.onNodeWithTag("hub-install").performScrollTo().assertIsEnabled()

        compose.onNode(hasTestTag("hub-preview-text") and hasText(SKILL_MD)).performScrollTo().assertExists()
        compose.onNodeWithTag("hub-new-chats").performScrollTo().assertExists()
        set(HubUi(official = listOf(BLACKBOX), open = HubPreview(BLACKBOX.copy(description = ""), emptyList(), null)))
        compose.onNode(hasTestTag("hub-preview-description") and hasText("No description.")).assertExists()
        compose.onNodeWithText("This skill has no SKILL.md preview.").performScrollTo().assertExists()

        calls.clear()
        compose.onNodeWithTag("hub-preview-close").performClick()
        assertEquals(listOf("closePreview"), calls)
    }

    @Test
    fun installShowsInstallingAndARefusalSaysWhy() {
        val scanned = HubPreview(BLACKBOX, emptyList(), SKILL_MD, scan = HubScan("safe", true, emptyList(), "builtin"))
        val set = showOpening(HubUi(official = listOf(BLACKBOX), open = scanned, installing = BLACKBOX_ID, busy = true))
        compose.onNode(hasTestTag("hub-install") and hasText("Installing...")).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("hub-preview-close").assertIsNotEnabled()

        val words = "Not installed: the security scan found 2 high-risk pattern(s) in 'blackbox' (listed above)."
        set(HubUi(official = listOf(BLACKBOX), open = scanned, blocked = HubBlock("blackbox", words, true)))
        compose.onNode(hasTestTag("hub-blocked") and hasAnyDescendant(hasText("Couldn't install blackbox")) and
                       hasAnyDescendant(hasText(words))).performScrollTo().assertExists()
        compose.onNodeWithTag("hub-preview-close").assertIsEnabled()
        compose.onNodeWithTag("hub-view-scan").performScrollTo().performClick()
        assertEquals(listOf("scan:$BLACKBOX_ID"), calls)

        set(HubUi(official = listOf(BLACKBOX), open = scanned, blocked = HubBlock("blackbox", "Error: Could not download 'blackbox'.", false)))
        compose.onNode(hasTestTag("hub-blocked") and hasAnyDescendant(hasText("Error: Could not download 'blackbox'."))).assertExists()
        compose.onNodeWithTag("hub-view-scan").assertDoesNotExist()

        set(HubUi(official = listOf(BLACKBOX), open = scanned, installed = setOf("blackbox"), notice = "blackbox"))
        compose.onNode(hasTestTag("hub-preview-installed") and hasText("Installed"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("hub-install").assertDoesNotExist()
        compose.onNode(hasTestTag("hub-notice") and hasAnyDescendant(hasText("Installed “blackbox”.")) and
                       hasAnyDescendant(hasText("blackbox applies to new chats."))).assertExists()
    }

    @Test
    fun theRealCorePreviewsAndScansAnOfficialSkill() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        val hub = ready.pages.hub
        try {
            hub.open()
            val began = SystemClock.elapsedRealtime()
            withTimeout(60_000) { hub.preview(BLACKBOX_ID) }  // no install on the shared phone
            val ui = hub.state.value!!
            val open = ui.open!!
            Log.i("HubScreenTest", "preview(blackbox) inspected + scanned in ${SystemClock.elapsedRealtime() - began} ms: " +
                                   "verdict ${open.scan?.verdict}, allowed ${open.scan?.allowed}, ${open.scan?.findings?.size} findings, " +
                                   "trust ${open.scan?.trust}, failed ${open.scanFailed}")
            assertTrue("not a SKILL.md: ${open.skillMd?.take(40)}", open.skillMd!!.startsWith("---"))
            assertEquals(BLACKBOX_ID, open.item.identifier)
            assertNotNull(open.scan)
            assertNull(ui.error)
            assertFalse(ui.busy)
            hub.closePreview()
        } finally {
            hub.close()
        }
    }
}

/** The shipped core's `skills.manage search` answer to any search with a hit (`SkillMeta.describe` does not exist, P13 FOUND). */
private const val CORE_SEARCH_BUG = "'SkillMeta' object has no attribute 'describe'"
