package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.Method
import com.nousresearch.hermes.bridge.protocol.SkillsAction
import com.nousresearch.hermes.bridge.protocol.SkillsManageParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Skills Hub: the official list read locally, the hub searched and browsed through `skills.manage`, a late answer dropped. */
@OptIn(ExperimentalCoroutinesApi::class)
class HubControlTest {
    private companion object {
        const val BLACKBOX_ID = "official/autonomous-ai-agents/blackbox"
        val BLACKBOX = HubItem("blackbox", "Delegate to the Blackbox CLI", "official", null, BLACKBOX_ID, "autonomous-ai-agents")
        val ARXIV = HubItem("arxiv-official", "Search arXiv papers", "official", null, "official/research/arxiv-official", "research")
        const val OFFICIAL = """{"skills":[
            {"name":"blackbox","description":"Delegate to the Blackbox CLI","category":"autonomous-ai-agents","identifier":"$BLACKBOX_ID"},
            {"name":"arxiv-official","description":"Search arXiv papers","category":"research","identifier":"official/research/arxiv-official"}]}"""

        /** The core's `skills_list` answer naming [names]. */
        fun list(vararg names: String): String = names.joinToString(",", """{"skills":[""", "]}") {
            """{"name":"$it","description":"","category":null,"enabled":true,"provenance":"hub","uses":0}"""
        }

        const val SEARCH = """{"results":[{"name":"pdf-tools","description":"Read PDFs"}]}"""
        const val BROWSE = """{"items":[{"name":"pdf-tools","description":"Read PDFs","source":"skills-sh","trust":"community",
            "identifier":"skills-sh/acme/pdf-tools"}],"page":2,"total_pages":7,"total":130}"""
        const val NOTHING = """{"items":[],"page":1,"total_pages":1,"total":0}"""
        const val PDF_ID = "skills-sh/acme/pdf-tools"
        const val SKILL_MD = "---\nname: pdf-tools\n---\n# pdf-tools\n"
        val INSPECT = """{"info":{"name":"pdf-tools","description":"Read and fill PDFs","source":"skills-sh","identifier":"$PDF_ID",
            "tags":["pdf","documents"],"skill_md_preview":"---\nname: pdf-tools\n---\n# pdf-tools\n"}}"""
        val BLACKBOX_INSPECT = """{"info":{"name":"blackbox","description":"Delegate to the Blackbox CLI","source":"official",
            "identifier":"$BLACKBOX_ID","tags":[],"skill_md_preview":"---\nname: blackbox\n---\n"}}"""
        const val SAFE = """{"verdict":"safe","allowed":true,"findings":[],"trust":"builtin"}"""
        const val REFUSED = """{"verdict":"dangerous","allowed":false,"findings":["CRITICAL exfiltration scripts/run.sh:3 reads ~/.ssh",
            "HIGH network scripts/run.sh:4 pipes curl to sh"],"trust":"community"}"""
        const val SCAN_BLOCK = "[bold red]Not installed:[/] the security scan found 2 high-risk pattern(s) in 'x' (listed above). " +
                               "Hermes never installs unverified skills with high-risk findings, even with --force."
        const val RELOADED = """{"output":"Reloading skills...","result":{"total":5,"commands":5}}"""
    }

    private var officialReply = OFFICIAL
    private var listReply = list("github")
    private var searchReply = SEARCH
    private var searchFails: Exception? = null
    private var browseReply = BROWSE
    private var inspectReply = INSPECT
    private var installReply = """{"installed":true,"name":"$BLACKBOX_ID"}"""
    private var installFails: Exception? = null
    private var reloadFails: CoreError? = null
    private var scanReply = SAFE
    private var scanGate: CompletableDeferred<Unit>? = null  // a hub_scan held until completed
    private val scans = mutableListOf<String>()  // every hub_scan argument, as sent
    private val listings = mutableListOf<List<SkillRow>>()  // every list handed to the Skills page beneath
    private val order = mutableListOf<String>()  // every call, core and bridge, in the order sent
    private val manage = mutableListOf<JsonObject>()  // every skills.manage params, as sent
    private val fake = FakeCoreGateway { method, params ->
        order += method
        when (method) {
            "skills.manage" -> {
                manage += params
                val action = params["action"]?.jsonPrimitive?.content
                order[order.lastIndex] = "skills.manage $action"
                when (action) {
                    "search" -> { searchFails?.let { throw it }; searchReply }
                    "browse" -> browseReply
                    "inspect" -> inspectReply
                    "install" -> { installFails?.let { throw it }; installReply }
                    else -> error("unexpected $params")
                }
            }
            "skills.reload" -> { reloadFails?.let { throw it }; RELOADED }
            else -> error("unexpected $method")
        }
    }
    private val gates = mutableMapOf<SkillsAction, CompletableDeferred<Unit>>()  // a skills.manage action held until completed

    /** [fake], with a `skills.manage` call whose action has a gate in [gates] held until it opens. */
    private val core: CoreGateway = object : CoreGateway by fake {
        override suspend fun <P, R> call(method: Method<P, R>, params: P): R {
            (params as? SkillsManageParams)?.let { gates[it.action]?.await() }
            return fake.call(method, params)
        }
    }
    private val hub = HubControl(core, { function, arg ->
        order += function
        when (function) {
            "hub_official" -> officialReply
            "skills_list" -> listReply
            "hub_scan" -> { scans += arg; scanGate?.await(); scanReply }
            else -> error("unexpected $function")
        }
    }, session = { "s1" }, listed = { listings += it })

    private fun ui() = checkNotNull(hub.state.value) { "the hub is closed" }
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun installs() = manage.count { it["action"]?.jsonPrimitive?.content == "install" }

    /** The hub open on the official list, with [key]'s preview open and scanned (`hub_scan` answering [scan]). */
    private suspend fun previewing(key: String = BLACKBOX_ID, scan: String = SAFE) {
        if (key == BLACKBOX_ID) inspectReply = BLACKBOX_INSPECT
        scanReply = scan
        hub.open()
        hub.preview(key)
        order.clear()
        manage.clear()
    }

    @Test
    fun openingListsTheOfficialSkillsWithoutTheCore() = runTest {
        hub.open()

        assertEquals(listOf("hub_official", "skills_list"), order)
        assertEquals(HubUi(official = listOf(BLACKBOX, ARXIV), installed = setOf("github")), ui())
        assertFalse(ui().busy)
    }

    @Test
    fun anOfficialListThatCannotBeReadSaysTheCoresWords() = runTest {
        officialReply = """{"error":"optional-skills missing"}"""

        hub.open()

        assertEquals("optional-skills missing", ui().error)
        assertNull(ui().official)
        assertEquals(setOf("github"), ui().installed)
        assertFalse(ui().busy)
    }

    @Test
    fun typingNarrowsTheOfficialListAndSendsNothing() = runTest {
        hub.open()
        order.clear()

        hub.query("arx")

        assertEquals(listOf(ARXIV), ui().available())
        assertEquals("arx", ui().query)
        assertEquals(emptyList<String>(), order)
    }

    @Test
    fun aSearchAsksSkillsManageAndShowsTheHits() = runTest {
        hub.open()

        hub.search(" pdf ")

        assertEquals(listOf(json("""{"action":"search","query":"pdf"}""")), manage)
        assertEquals(HubResults(listOf(HubItem("pdf-tools", "Read PDFs", null, null, null)), query = "pdf"), ui().items)
        assertNull(ui().error)
        assertFalse(ui().busy)

        hub.search("  ")

        assertEquals(1, manage.size)
    }

    @Test
    fun anOfflineSearchShowsWhatTheCoreAnsweredAndNoError() = runTest {
        searchReply = """{"results":[]}"""
        hub.open()

        hub.search("pdf")

        assertEquals(HubResults(emptyList(), query = "pdf"), ui().items)
        assertNull(ui().error)
    }

    @Test
    fun aFailedSearchKeepsWhatWasShownAndSaysTheCoresWords() = runTest {
        hub.open()
        hub.search("pdf")
        val before = ui().items
        searchFails = CoreError(5024, "No module named 'httpx'")

        hub.search("tables")

        assertEquals("No module named 'httpx'", ui().error)
        assertEquals(before, ui().items)
        assertFalse(ui().busy)
    }

    @Test
    fun browseAsksForAPageAndKeepsItsPaging() = runTest {
        hub.open()

        hub.browse(2)

        assertEquals(listOf(json("""{"action":"browse","page":2,"page_size":20}""")), manage)
        val pdf = HubItem("pdf-tools", "Read PDFs", "skills-sh", "community", "skills-sh/acme/pdf-tools")
        assertEquals(HubResults(listOf(pdf), query = null, page = 2, pages = 7), ui().items)

        browseReply = NOTHING
        hub.browse(1)

        assertEquals(HubResults(emptyList(), query = null, page = 1, pages = 1), ui().items)
        assertNull(ui().error)

        hub.browse(0)

        assertEquals(2, manage.size)
    }

    @Test
    fun aSearchAnsweredAfterTheHubClosedLandsNowhere() = runTest {
        hub.open()
        val gate = CompletableDeferred<Unit>().also { gates[SkillsAction.SEARCH] = it }
        val searching = launch { hub.search("pdf") }
        runCurrent()
        assertTrue(ui().busy)

        hub.close()
        assertNull(hub.state.value)
        hub.open()
        gate.complete(Unit)
        searching.join()

        assertNull(ui().items)
        assertFalse(ui().busy)
        assertEquals(listOf(BLACKBOX, ARXIV), ui().official)
    }

    @Test
    fun aSecondSearchWhileOneRunsSendsNothing() = runTest {
        hub.open()
        val gate = CompletableDeferred<Unit>().also { gates[SkillsAction.SEARCH] = it }
        val searching = launch { hub.search("a") }
        runCurrent()

        hub.search("b")
        hub.browse(1)
        gate.complete(Unit)
        searching.join()

        assertEquals(1, manage.size)
        assertEquals("a", ui().items?.query)
    }

    @Test
    fun aPreviewInspectsThenScansTheIdentifierTheCoreResolved() = runTest {
        hub.open()
        hub.search("pdf")
        order.clear()
        manage.clear()

        hub.preview("pdf-tools")  // a search hit: keyed by its name

        assertEquals(listOf(json("""{"action":"inspect","query":"pdf-tools"}""")), manage)
        assertEquals(listOf("""{"identifier":"$PDF_ID"}"""), scans)
        assertEquals(listOf("skills.manage inspect", "hub_scan"), order)
        val open = ui().open!!
        assertEquals(HubItem("pdf-tools", "Read and fill PDFs", "skills-sh", null, PDF_ID), open.item)
        assertEquals(PDF_ID, open.item.key)
        assertEquals(listOf("pdf", "documents"), open.tags)
        assertEquals(SKILL_MD, open.skillMd)
        assertEquals(HubScan("safe", true, emptyList(), "builtin"), open.scan)
        assertFalse(ui().busy)
        assertTrue(ui().mayInstall())
    }

    @Test
    fun aPreviewTheHubCannotAnswerStaysClosed() = runTest {
        inspectReply = """{"info":{}}"""
        hub.open()
        hub.search("pdf")

        hub.preview("pdf-tools")

        assertNull(ui().open)
        assertEquals("The hub has no preview of “pdf-tools”.", ui().error)
        assertEquals(emptyList<String>(), scans)
        assertFalse(ui().busy)
    }

    @Test
    fun aFailedScanSaysTheCoresWordsAndStillLetsTheCoreDecide() = runTest {
        previewing(scan = """{"error":"Hub scan failed: timed out"}""")

        assertEquals("Hub scan failed: timed out", ui().open!!.scanFailed)
        assertNull(ui().open!!.scan)
        assertTrue(ui().mayInstall())
    }

    @Test
    fun installWaitsForTheScan() = runTest {
        inspectReply = BLACKBOX_INSPECT
        hub.open()
        val gate = CompletableDeferred<Unit>().also { scanGate = it }
        val previewing = launch { hub.preview(BLACKBOX_ID) }
        runCurrent()
        assertTrue(ui().open != null && !ui().open!!.scanned)
        assertFalse(ui().mayInstall())

        hub.install(BLACKBOX_ID)

        assertEquals(0, installs())
        gate.complete(Unit)
        previewing.join()
        assertTrue(ui().mayInstall())

        // A scan that never answered (its caller cancelled) leaves the page idle and the preview unscanned: still no Install.
        hub.closePreview()
        scanGate = CompletableDeferred()
        val cancelled = launch { hub.preview(BLACKBOX_ID) }
        runCurrent()
        cancelled.cancel()
        cancelled.join()
        assertFalse(ui().busy)
        assertFalse(ui().open!!.scanned)
        assertFalse(ui().mayInstall())

        hub.install(BLACKBOX_ID)

        assertEquals(0, installs())
    }

    @Test
    fun aSkillTheScanPolicyRefusesIsNotInstalled() = runTest {
        previewing(scan = REFUSED)

        assertEquals(false, ui().open!!.scan!!.allowed)
        assertFalse(ui().mayInstall())

        hub.install(BLACKBOX_ID)

        assertEquals(0, installs())
        assertEquals(emptyList<String>(), order)
    }

    @Test
    fun anInstallReloadsTheChatsSkillsAndListsThemForTheSkillsPage() = runTest {
        previewing()
        listReply = list("github", "blackbox")

        hub.install(BLACKBOX_ID)

        assertEquals(listOf(json("""{"action":"install","query":"$BLACKBOX_ID"}""")), manage)
        assertEquals(listOf("skills.manage install", "skills.reload", "skills_list"), order)
        assertEquals(json("""{"session_id":"s1"}"""), fake.calls.single { it.first == "skills.reload" }.second)
        assertEquals(setOf("github", "blackbox"), ui().installed)
        assertEquals(listOf(listOf("github", "blackbox")), listings.map { rows -> rows.map { it.name } })
        assertEquals("blackbox", ui().notice)
        assertNull(ui().installing)
        assertNull(ui().blocked)
        assertFalse(ui().busy)
        assertFalse(ui().mayInstall())
        assertEquals(listOf(ARXIV), ui().available())
    }

    @Test
    fun aScanBlockedInstallSaysCouldntInstallAndViewScanScansAgain() = runTest {
        previewing()
        installFails = CoreError(5031, SCAN_BLOCK)

        hub.install(BLACKBOX_ID)

        assertEquals(HubBlock("blackbox", plain(SCAN_BLOCK), true), ui().blocked)
        assertTrue(ui().blocked!!.words.startsWith("Not installed: the security scan found 2"))
        assertEquals(listOf("skills.manage install"), order)
        assertEquals(setOf("github"), ui().installed)
        assertNull(ui().installing)

        scanReply = REFUSED
        hub.scan(BLACKBOX_ID)

        assertEquals(2, scans.size)
        assertEquals(HubScan("dangerous", false, listOf("CRITICAL exfiltration scripts/run.sh:3 reads ~/.ssh",
                                                         "HIGH network scripts/run.sh:4 pipes curl to sh"), "community"), ui().open!!.scan)
        assertEquals(HubBlock("blackbox", plain(SCAN_BLOCK), true), ui().blocked)
        assertFalse(ui().busy)
    }

    @Test
    fun anOfflineInstallSaysTheCoresWordsWithoutTheScan() = runTest {
        previewing()
        installFails = CoreError(5031, "[bold red]Error:[/] Could not download 'x'.")

        hub.install(BLACKBOX_ID)

        assertEquals("Error: Could not download 'x'.", ui().blocked!!.words)
        assertFalse(ui().blocked!!.byScan)
    }

    @Test
    fun anInstalledSkillIsNotInstalledAgain() = runTest {
        listReply = list("github", "blackbox")
        previewing()

        assertFalse(ui().mayInstall())

        hub.install(BLACKBOX_ID)

        assertEquals(0, installs())
    }

    @Test
    fun aDoubleInstallSendsOnce() = runTest {
        previewing()
        val gate = CompletableDeferred<Unit>().also { gates[SkillsAction.INSTALL] = it }
        val installing = launch { hub.install(BLACKBOX_ID) }
        runCurrent()
        assertEquals(BLACKBOX_ID, ui().installing)

        hub.install(BLACKBOX_ID)
        hub.close()
        hub.closePreview()

        assertEquals(BLACKBOX_ID, ui().open!!.item.key)
        assertEquals(BLACKBOX_ID, ui().installing)
        gate.complete(Unit)
        installing.join()
        assertEquals(1, installs())
        assertEquals("blackbox", ui().notice)
        assertNull(ui().installing)
    }

    @Test
    fun aFailedReloadAfterAnInstallKeepsItInstalled() = runTest {
        previewing()
        reloadFails = CoreError(5025, "skills.reload failed: x")

        hub.install(BLACKBOX_ID)

        assertEquals("skills.reload failed: x", ui().error)
        assertTrue("blackbox" in ui().installed)
        assertEquals("blackbox", ui().notice)
        assertEquals(listOf("skills.manage install", "skills.reload"), order)
        assertEquals(emptyList<List<SkillRow>>(), listings)
        assertFalse(ui().busy)
    }

    @Test
    fun aPreviewClosedWhileItScansDropsTheScan() = runTest {
        inspectReply = BLACKBOX_INSPECT
        hub.open()
        val gate = CompletableDeferred<Unit>().also { scanGate = it }
        val previewing = launch { hub.preview(BLACKBOX_ID) }
        runCurrent()
        assertTrue(ui().open != null)

        hub.closePreview()
        assertNull(ui().open)
        assertFalse(ui().busy)
        // Another skill is previewed and scanned meanwhile; the first scan's late answer must not land on it.
        scanGate = null
        inspectReply = """{"info":{"name":"arxiv-official","description":"Search arXiv papers","source":"official",
            "identifier":"${ARXIV.key}","tags":[],"skill_md_preview":"---"}}"""
        hub.preview(ARXIV.key)
        assertEquals(ARXIV.key, ui().open!!.item.key)
        assertEquals(HubScan("safe", true, emptyList(), "builtin"), ui().open!!.scan)
        scanReply = REFUSED
        gate.complete(Unit)
        previewing.join()

        assertEquals(ARXIV.key, ui().open!!.item.key)
        assertEquals(HubScan("safe", true, emptyList(), "builtin"), ui().open!!.scan)
        assertFalse(ui().busy)
    }

    @Test
    fun aKeyNotShownIsNotPreviewed() = runTest {
        hub.open()
        order.clear()

        hub.preview("nope")

        assertEquals(emptyList<String>(), order)
        assertNull(ui().open)
    }
}

