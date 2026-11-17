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
    }

    private var officialReply = OFFICIAL
    private var listReply = list("github")
    private var searchReply = SEARCH
    private var searchFails: Exception? = null
    private var browseReply = BROWSE
    private val order = mutableListOf<String>()  // every call, core and bridge, in the order sent
    private val manage = mutableListOf<JsonObject>()  // every skills.manage params, as sent
    private val fake = FakeCoreGateway { method, params ->
        order += method
        when (method) {
            "skills.manage" -> {
                manage += params
                when (params["action"]?.jsonPrimitive?.content) {
                    "search" -> { searchFails?.let { throw it }; searchReply }
                    "browse" -> browseReply
                    else -> error("unexpected $params")
                }
            }
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
    private val hub = HubControl(core, { function, _ ->
        order += function
        when (function) {
            "hub_official" -> officialReply
            "skills_list" -> listReply
            else -> error("unexpected $function")
        }
    }) { "s1" }

    private fun ui() = checkNotNull(hub.state.value) { "the hub is closed" }
    private fun json(text: String) = Json.parseToJsonElement(text)

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
}
