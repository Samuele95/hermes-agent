package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.ToolsetsKeysReply
import com.nousresearch.hermes.bridge.protocol.ToolsetsListResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The toolsets as the page shows them: only what the core switches, by Desktop's labels, narrowed and switched by the core's answer. */
class ToolsetsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val rows = json.decodeFromString(ToolsetsListResult.serializer(), ToolsetsControlTest.LIST).toolsets
    private val keys = json.decodeFromString(ToolsetsKeysReply.serializer(), ToolsetsControlTest.KEYS)

    private val browser = ToolsetRow("browser", "Browser Automation", "Navigate, click and read pages", 14, enabled = true, needsKeys = false)
    private val kanban = ToolsetRow("kanban", "Kanban", "Track work on a board", 14, enabled = false, needsKeys = false)
    private val web = ToolsetRow("web", "Web Search & Scraping", "Web search and content extraction", 2, enabled = true, needsKeys = true)

    @Test
    fun onlyTheToolsetsTheCoreSwitchesAreListedByTheirLabels() {
        val listed = ToolsetRow.listed(rows, keys)

        // hermes-telegram and project are not switchable; discord is switchable but Desktop hides it.
        assertEquals(listOf(browser, kanban, web), listed)
        assertEquals(listOf("Browser Automation", "Kanban", "Web Search & Scraping"), listed.map { it.label })
        assertEquals(listOf(14L, 14L, 2L), listed.map { it.toolCount })
        assertEquals(listOf("web"), listed.filter { it.needsKeys }.map { it.name })
        assertTrue(listed.all { it.tools == null })
        assertTrue("discord" in ToolsetRow.HIDDEN)
    }

    @Test
    fun aSearchMatchesNameLabelOrDescriptionNotToolNames() {
        val clicking = browser.copy(tools = listOf("browser_click"))

        assertTrue(web.matches(" WEB "))  // its name
        assertFalse(kanban.matches(" WEB "))
        assertTrue(clicking.matches("automation"))  // its label
        assertFalse(web.matches("automation"))
        assertTrue(web.matches("content extraction"))  // its description
        assertFalse(clicking.matches("content extraction"))
        assertTrue(listOf(clicking, kanban, web).all { it.matches("  ") })
        assertFalse(clicking.matches("browser_click"))  // a tool's name is not searched (D9)
        assertEquals(listOf(kanban), ToolsetsUi(rows = listOf(clicking, kanban, web), query = "board").shown())
    }

    @Test
    fun aSwitchShowsTheCoresEnabledSet() {
        val switched = listOf(browser, kanban, web).switched(listOf("web", "kanban"))

        assertEquals(listOf(false, true, true), switched.map { it.enabled })
        assertEquals(listOf(browser.copy(enabled = false), kanban.copy(enabled = true), web), switched)
    }
}
