package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.HubOfficial
import com.nousresearch.hermes.bridge.protocol.SkillBrowseItem
import com.nousresearch.hermes.bridge.protocol.SkillHubHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// A hub skill is keyed by the identifier the core gave it, else by its name, and the official list hides what is installed.
class HubTest {
    private val blackbox = HubItem.of(HubOfficial("blackbox", "Delegate to the Blackbox CLI", "autonomous-ai-agents",
                                                  "official/autonomous-ai-agents/blackbox"))
    private val arxiv = HubItem.of(HubOfficial("arxiv-official", "Search arXiv papers", "research", "official/research/arxiv-official"))

    @Test
    fun anOfficialRowIsAnItemKeyedByItsIdentifier() {
        assertEquals(HubItem("blackbox", "Delegate to the Blackbox CLI", "official", null, "official/autonomous-ai-agents/blackbox",
                             "autonomous-ai-agents"), blackbox)
        assertTrue(blackbox.official)
        assertEquals("official/autonomous-ai-agents/blackbox", blackbox.key)
        assertNull(HubItem.of(HubOfficial("top", "", null, "official/top")).category)
    }

    @Test
    fun aSearchHitIsKeyedByItsNameAndABrowseRowByItsIdentifier() {
        val hit = HubItem.of(SkillHubHit("pdf-tools", "Read PDFs"))
        assertEquals(HubItem("pdf-tools", "Read PDFs", null, null, null), hit)
        assertEquals("pdf-tools", hit.key)
        assertFalse(hit.official)

        val row = HubItem.of(SkillBrowseItem("pdf-tools", "Read PDFs", "skills-sh", "community", "skills-sh/acme/pdf-tools"))
        assertEquals(HubItem("pdf-tools", "Read PDFs", "skills-sh", "community", "skills-sh/acme/pdf-tools"), row)
        assertEquals("skills-sh/acme/pdf-tools", row.key)

        val bare = HubItem.of(SkillBrowseItem("bare", "", "", null, ""))
        assertNull(bare.source)
        assertNull(bare.identifier)
        assertEquals("bare", bare.key)
    }

    @Test
    fun availableLeavesOutInstalledSkillsAndFollowsTheQuery() {
        val ui = HubUi(official = listOf(blackbox, arxiv), installed = setOf("blackbox", "github"))

        assertEquals(listOf(arxiv), ui.available())
        assertEquals(listOf(blackbox, arxiv), ui.copy(installed = emptySet()).available())
        assertEquals(listOf(arxiv), ui.copy(installed = emptySet(), query = " RESEARCH ").available())  // by category
        assertEquals(listOf(blackbox), ui.copy(installed = emptySet(), query = "BLACK").available())  // by name
        assertEquals(listOf(arxiv), ui.copy(installed = emptySet(), query = "papers").available())  // by description
        assertEquals(emptyList<HubItem>(), ui.copy(query = "zzz").available())
        assertNull(HubUi().available())
    }

    @Test
    fun theCoresConsoleMarkupIsNotShown() {
        assertEquals("Not installed: the security scan found 2 high-risk pattern(s) in 'x' (listed above).",
                     plain("[bold red]Not installed:[/] the security scan found 2 high-risk pattern(s) in 'x' (listed above)."))
        assertEquals("Resolving 'x'...", plain("[dim]Resolving 'x'...[/]"))
        assertEquals("hermes skills search x", plain("[bold]hermes skills search x[/]"))
        assertEquals("Warning: 'x' is already installed", plain(" [yellow]Warning:[/yellow] 'x' is already installed "))
        assertEquals("step [1/3] of [skill]", plain("step [1/3] of [skill]"))
    }

    @Test
    fun onlyAScanBlockedRefusalOffersTheScan() {
        val scanned = HubBlock.of("x", "[bold red]Not installed:[/] the security scan\nfound 2 high-risk pattern(s) in 'x' (listed above). " +
                                       "Hermes never installs unverified skills with high-risk findings, even with --force.")
        assertEquals(HubBlock("x", "Not installed: the security scan\nfound 2 high-risk pattern(s) in 'x' (listed above). " +
                                   "Hermes never installs unverified skills with high-risk findings, even with --force.", true), scanned)
        listOf("[bold red]Error:[/] Could not download 'x'.", "Use the full identifier to install a specific one.",
               "Use --force to reinstall.").forEach {
            assertFalse(it, HubBlock.of("x", it).byScan)
        }
        assertEquals("Error: Could not download 'x'.", HubBlock.of("x", "[bold red]Error:[/] Could not download 'x'.").words)
    }
}
