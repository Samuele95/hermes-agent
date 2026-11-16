package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.bridge.contract.Provenance
import com.nousresearch.hermes.bridge.contract.SkillEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A category's switch is on when every skill in it is on, and a flip sends the skills not already so (Desktop's "All").
class SkillGroupTest {
    private val plain = SkillRow("plain", "A plain skill", null, true)
    private val codex = SkillRow("codex", "Delegate to Codex", "autonomous-ai-agents", false)
    private val manual = SkillRow("hermes-agent", "Hermes's own manual", "autonomous-ai-agents", true)
    private val arxiv = SkillRow("arxiv", "Search arXiv", "research", true)
    private val groups = SkillGroup.of(listOf(plain, codex, manual, arxiv))

    @Test
    fun skillsAreGroupedByCategoryInTheCoresOrder() {
        assertEquals(listOf(null, "autonomous-ai-agents", "research"), groups.map { it.category })
        assertEquals(listOf(listOf(plain), listOf(codex, manual), listOf(arxiv)), groups.map { it.rows })
    }

    @Test
    fun aCategoryIsOnOnlyWhenEverySkillInItIsOn() {
        assertTrue(groups[0].on)
        assertFalse(groups[1].on)
        assertTrue(groups[2].on)
    }

    @Test
    fun aCategorySwitchSendsTheSkillsNotAlreadySo() {
        assertEquals(listOf("codex"), groups[1].switching(true))
        assertEquals(listOf("hermes-agent"), groups[1].switching(false))
        assertEquals(emptyList<String>(), groups[2].switching(true))
    }

    @Test
    fun aRowIsTheBridgesEntryFieldForField() {
        assertEquals(SkillRow("arxiv", "Search arXiv.", null, false), SkillRow.of(SkillEntry("arxiv", "Search arXiv.", null, false, Provenance.BUNDLED, 0)))
    }
}
