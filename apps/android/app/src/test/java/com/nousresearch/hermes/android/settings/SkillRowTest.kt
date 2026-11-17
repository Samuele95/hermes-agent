package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.SkillEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import com.nousresearch.hermes.bridge.contract.Provenance as WireProvenance

// A skill says where it came from and how often it ran, and a search finds it by name, description or category.
class SkillRowTest {
    private val arxiv = SkillRow("arxiv", "Find papers", "research", true)
    private val codex = SkillRow("codex", "Delegate to Codex", "autonomous-ai-agents", false)
    private val plain = SkillRow("plain", "A plain skill", null, true)
    private val rows = listOf(arxiv, codex, plain)

    private fun found(query: String) = rows.filter { it.matches(query) }

    @Test
    fun ofCarriesWhereItCameFromAndHowOftenItRan() {
        val expected = mapOf(WireProvenance.HUB to Provenance.HUB, WireProvenance.BUNDLED to Provenance.BUNDLED,
                             WireProvenance.EXTERNAL to Provenance.EXTERNAL, WireProvenance.AGENT to Provenance.AGENT)

        assertEquals(expected, WireProvenance.entries.associateWith { SkillRow.of(SkillEntry("s", "", null, true, it, 0)).provenance })
        assertEquals(SkillRow("arxiv", "Find papers", "research", true, Provenance.AGENT, 12),
                     SkillRow.of(SkillEntry("arxiv", "Find papers", "research", true, WireProvenance.AGENT, 12)))
    }

    @Test
    fun searchMatchesNameDescriptionOrCategoryIgnoringCase() {
        assertEquals(listOf(arxiv), found("ARX"))
        assertEquals(listOf(arxiv), found("papers"))
        assertEquals(listOf(arxiv), found("research"))
        assertEquals(listOf(codex), found(" Codex "))
        assertEquals(emptyList<SkillRow>(), found("zzz"))
        assertEquals(emptyList<SkillRow>(), found("general"))  // no category is not the "General" heading
    }

    @Test
    fun aBlankQueryMatchesEverySkill() {
        assertEquals(rows, found(""))
        assertEquals(rows, found("   "))
    }
}
