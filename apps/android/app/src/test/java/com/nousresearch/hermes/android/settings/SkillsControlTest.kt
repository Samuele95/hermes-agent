package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The skills page shows the core's skills and its answer to each change, never a prediction. */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillsControlTest {
    private companion object {
        val ROWS = listOf(SkillRow("plain", "A plain skill", null, true),
                          SkillRow("codex", "Delegate to Codex", "autonomous-ai-agents", false),
                          SkillRow("hermes-agent", "Hermes's own manual", "autonomous-ai-agents", true),
                          SkillRow("arxiv", "Search arXiv", "research", true))

        /** The core's `skills_list` answer for [rows]. */
        fun list(rows: List<SkillRow>): String = rows.joinToString(",", """{"skills":[""", "]}") { r ->
            val category = r.category?.let { "\"$it\"" } ?: "null"
            """{"name":"${r.name}","description":"${r.description}","category":$category,"enabled":${r.enabled},""" +
                """"provenance":"bundled","uses":0}"""
        }

        val LIST = list(ROWS)
        const val REFUSED = "Skill 'hermes-agent' is essential to Hermes (the agent's own operating manual referenced by " +
                            "the system prompt) and cannot be disabled."

        const val RELOADED = """{"output":"No new skills detected. 4 skill(s) available.","result":{"total":4,"commands":4}}"""
        const val SKILL_MD = "---\nname: arxiv\n---\n# arxiv\n"
        /** One skill of each provenance (the writes' fixture). */
        val KINDS = listOf(SkillRow("mine", "Made by the agent", null, true, Provenance.AGENT),
                           SkillRow("ext", "Mounted", "research", true, Provenance.EXTERNAL),
                           SkillRow("arxiv", "Search arXiv", "research", true, Provenance.BUNDLED),
                           SkillRow("hubbed", "From the hub", null, true, Provenance.HUB))

        /** The core's `skills_list` answer for [rows], each with its own provenance and uses. */
        fun kinds(rows: List<SkillRow>): String = rows.joinToString(",", """{"skills":[""", "]}") { r ->
            val category = r.category?.let { "\"$it\"" } ?: "null"
            """{"name":"${r.name}","description":"${r.description}","category":$category,"enabled":${r.enabled},""" +
                """"provenance":"${r.provenance.name.lowercase()}","uses":${r.uses}}"""
        }

        /** [name]'s SKILL.md, and the core's `learning.detail` answer holding it. */
        fun md(name: String) = "---\nname: $name\n---\n# $name\n"
        fun detail(name: String) = """{"ok":true,"kind":"skill","id":"$name","label":"$name","content":${JsonPrimitive(md(name))}}"""

        const val OK = """{"ok":true,"message":"saved"}"""
        const val NEW = "---\nname: mine\ndescription: Better\n---\n# mine, rewritten\n"
        const val FRONTMATTER = "SKILL.md must start with YAML frontmatter (---). See existing skills for format."
        const val PINNED = "'mine' is pinned — unpin it first (hermes curator unpin mine)"
        const val EXTERNAL = "skill 'ext' lives in skills.external_dirs; external skills are read-only to the curator"
        const val SHADOW = "skill 'old' is now bundled; restore would shadow the upstream version"
        const val DETAIL = """{"ok":true,"kind":"skill","id":"arxiv","label":"arxiv","content":"---\nname: arxiv\n---\n# arxiv\n"}"""
    }

    private var listReply = LIST
    private var listGate: CompletableDeferred<Unit>? = null  // a list that suspends until completed
    private var enableAnswer = LIST
    private var enableGate: CompletableDeferred<Unit>? = null  // a change that suspends until completed
    private val bridgeCalls = mutableListOf<Pair<String, String>>()
    private var sessionId: String? = "s1"
    private val order = mutableListOf<String>()  // every call, core and bridge, in the order sent
    private var reloadFails: CoreError? = null
    private var detailReply = DETAIL
    private var editReply = OK
    private var editFails: CoreError? = null
    private var deleteReply = OK
    private var archivedReply = """{"names":["old","older"]}"""
    private var restoreReply = kinds(KINDS + SkillRow("old", "Back again", null, true, Provenance.AGENT))
    private val core = FakeCoreGateway { method, _ ->
        order += method
        when (method) {
            "skills.reload" -> { reloadFails?.let { throw it }; RELOADED }
            "learning.detail" -> detailReply
            "learning.edit" -> { editFails?.let { throw it }; editReply }
            "learning.delete" -> deleteReply
            else -> error("unexpected $method")
        }
    }
    private val skills = SkillsControl(core, { function, arg ->
        bridgeCalls += function to arg
        order += function
        when (function) {
            "skills_list" -> { listGate?.await(); listReply }
            "skills_enable" -> { enableGate?.await(); enableAnswer }
            "skills_archived" -> archivedReply
            "skills_restore" -> restoreReply
            else -> error("unexpected $function")
        }
    }) { sessionId }

    private fun enables() = bridgeCalls.filter { it.first == "skills_enable" }.map { Json.parseToJsonElement(it.second) }
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun with(name: String, on: Boolean) = ROWS.map { if (it.name == name) it.copy(enabled = on) else it }

    @Test
    fun openingShowsTheCoresSkills() = runTest {
        skills.open()

        assertEquals(SkillsUi(rows = ROWS), skills.state.value)
        assertEquals(listOf("skills_list" to "{}"), bridgeCalls)
    }

    @Test
    fun aListThatCannotBeReadIsNoneAndSaysWhy() = runTest {
        listReply = """{"error":"config.yaml could not be read"}"""

        skills.open()

        assertEquals(SkillsUi(rows = null, error = "config.yaml could not be read"), skills.state.value)
    }

    @Test
    fun aPageClosedWhileItReadsStaysClosed() = runTest {
        val gate = CompletableDeferred<Unit>().also { listGate = it }
        val opening = launch { skills.open() }
        runCurrent()
        assertEquals(SkillsUi(busy = true), skills.state.value)

        skills.close()
        gate.complete(Unit)
        opening.join()

        assertNull(skills.state.value)
    }

    @Test
    fun aSwitchSendsItsNameAndShowsTheCoresAnswer() = runTest {
        skills.open()
        enableAnswer = list(with("arxiv", false))

        skills.enable(listOf("arxiv"), false)

        assertEquals(listOf(json("""{"names":["arxiv"],"enabled":false}""")), enables())
        assertEquals(with("arxiv", false), skills.state.value?.rows)
        assertEquals(false, skills.state.value?.busy)
    }

    @Test
    fun theCoresAnswerNotTheRequestIsShown() = runTest {
        skills.open()
        enableAnswer = list(with("codex", false))  // the core kept hermes-agent on

        skills.enable(listOf("codex", "hermes-agent"), false)

        assertEquals(true, skills.state.value?.rows?.single { it.name == "hermes-agent" }?.enabled)
        assertNull(skills.state.value?.error)
    }

    @Test
    fun aRefusedSwitchStaysOnWithTheCoresWords() = runTest {
        skills.open()
        enableAnswer = """{"error":"$REFUSED"}"""

        skills.enable(listOf("hermes-agent"), false)

        val ui = skills.state.value!!
        assertEquals(ROWS, ui.rows)
        assertEquals(true, ui.rows?.single { it.name == "hermes-agent" }?.enabled)
        assertEquals(REFUSED, ui.error)
        assertFalse(ui.busy)
    }

    @Test
    fun aCategoryIsOneCallWithEveryName() = runTest {
        skills.open()

        skills.enable(listOf("codex", "plain"), true)

        assertEquals(listOf(json("""{"names":["codex","plain"],"enabled":true}""")), enables())
    }

    @Test
    fun oneChangeAtATime() = runTest {
        skills.open()
        val gate = CompletableDeferred<Unit>().also { enableGate = it }
        val first = launch { skills.enable(listOf("arxiv"), false) }
        val second = launch { skills.enable(listOf("arxiv"), false) }
        runCurrent()
        assertEquals(true, skills.state.value?.busy)
        assertEquals(1, enables().size)

        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, enables().size)
        assertEquals(false, skills.state.value?.busy)
    }

    @Test
    fun nothingToSendOrAClosedPageSendsNothing() = runTest {
        skills.open()

        skills.enable(emptyList(), true)
        assertTrue(enables().isEmpty())
        assertEquals(SkillsUi(rows = ROWS), skills.state.value)

        skills.close()
        skills.enable(listOf("arxiv"), false)

        assertTrue(enables().isEmpty())
        assertNull(skills.state.value)
    }
    @Test
    fun aQueryNarrowsWhatIsShownAndSendsNothing() = runTest {
        skills.open()
        order.clear()

        skills.query("arx")

        assertEquals("arx", skills.state.value?.query)
        assertEquals(listOf(ROWS.last()), skills.state.value?.shown())
        assertEquals(emptyList<String>(), order)

        skills.open()

        assertEquals("", skills.state.value?.query)
        assertEquals(ROWS, skills.state.value?.shown())
    }

    @Test
    fun refreshReloadsTheChatsSkillsThenShowsTheList() = runTest {
        skills.open()
        order.clear()
        val fresh = ROWS.take(2)
        listReply = list(fresh)

        skills.refresh()

        assertEquals(listOf("skills.reload", "skills_list"), order)
        assertEquals(listOf("skills.reload" to json("""{"session_id":"s1"}""")), core.calls)
        assertEquals(fresh, skills.state.value?.rows)
        assertEquals(false, skills.state.value?.busy)
        assertNull(skills.state.value?.error)
    }

    @Test
    fun refreshWithNoOpenChatReloadsWithoutASession() = runTest {
        sessionId = null
        skills.open()

        skills.refresh()

        assertEquals(listOf("skills.reload" to json("{}")), core.calls)
    }

    @Test
    fun aFailedReloadKeepsTheRowsAndSaysTheCoresWords() = runTest {
        skills.open()
        order.clear()
        listReply = list(ROWS.take(1))
        reloadFails = CoreError(5025, "skills.reload failed: disk gone")

        skills.refresh()

        assertEquals(listOf("skills.reload"), order)  // no list read after a failed reload
        assertEquals(ROWS, skills.state.value?.rows)
        assertEquals("skills.reload failed: disk gone", skills.state.value?.error)
        assertEquals(false, skills.state.value?.busy)
    }

    @Test
    fun readingASkillShowsItsSkillMdAsTheCoreHoldsIt() = runTest {
        skills.open()

        skills.read("arxiv")

        assertEquals(listOf("learning.detail" to json("""{"id":"arxiv"}""")), core.calls)
        assertEquals(SkillText("arxiv", SKILL_MD), skills.state.value?.open)
        assertEquals(false, skills.state.value?.busy)
        assertNull(skills.state.value?.error)

        skills.closeSkill()

        assertNull(skills.state.value?.open)
    }

    @Test
    fun aSkillTheCoreCannotFindStaysClosedAndSaysSo() = runTest {
        skills.open()
        detailReply = """{"ok":false,"message":"skill 'arxiv' not found"}"""

        skills.read("arxiv")

        assertNull(skills.state.value?.open)
        assertEquals("skill 'arxiv' not found", skills.state.value?.error)
        assertEquals(false, skills.state.value?.busy)
    }

    @Test
    fun aReadWhileAChangeRunsSendsNothing() = runTest {
        skills.open()
        val gate = CompletableDeferred<Unit>().also { enableGate = it }
        val change = launch { skills.enable(listOf("arxiv"), false) }
        runCurrent()

        skills.read("arxiv")
        gate.complete(Unit)
        change.join()
        skills.read("nope")  // not listed

        assertTrue(core.calls.isEmpty())
        assertNull(skills.state.value?.open)

        skills.read("arxiv")  // idle and listed: read
        skills.read("codex")  // a skill already open: nothing

        assertEquals(listOf("learning.detail" to json("""{"id":"arxiv"}""")), core.calls)
        assertEquals("arxiv", skills.state.value?.open?.name)
    }
    /** The page open on [KINDS] with [name]'s SKILL.md open (none: the list), every call so far forgotten. */
    private suspend fun openOn(name: String?) {
        listReply = kinds(KINDS)
        skills.open()
        name?.let { readOnly(it) }
        order.clear()
        core.calls.clear()
        bridgeCalls.clear()
    }

    /** Opens [name] from the list (closing any open skill first). */
    private suspend fun readOnly(name: String) {
        skills.closeSkill()
        detailReply = detail(name)
        skills.read(name)
        assertEquals(name, skills.state.value?.open?.name)
    }

    private fun params(vararg pairs: Pair<String, String>) = JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    @Test
    fun editStartsFromTheCoresTextOnlyForAnEditableSkill() = runTest {
        openOn("mine")

        skills.edit()

        assertEquals(md("mine"), skills.state.value?.open?.editing)
        assertEquals(emptyList<String>(), order)

        for (name in listOf("arxiv", "hubbed")) {
            readOnly(name)
            order.clear()

            skills.edit()

            assertNull(skills.state.value?.open?.editing)
            assertEquals(emptyList<String>(), order)
        }
    }

    @Test
    fun aSavedEditShowsTheSavedTextAndReloads() = runTest {
        openOn("mine")
        skills.edit()
        skills.draft(NEW)
        val reread = KINDS.map { if (it.name == "mine") it.copy(description = "Better") else it }
        listReply = kinds(reread)

        skills.save()

        assertEquals(listOf("learning.edit", "skills.reload", "skills_list"), order)  // no second learning.detail (D6)
        assertEquals("learning.edit" to params("id" to "mine", "content" to NEW), core.calls.first())
        val ui = skills.state.value!!
        assertEquals(SkillText("mine", NEW, null), ui.open)
        assertEquals(SkillsNotice(SkillsNotice.Kind.UPDATED, "mine"), ui.notice)
        assertEquals(reread, ui.rows)
        assertNull(ui.error)
        assertFalse(ui.busy)
    }

    @Test
    fun aRefusedEditKeepsTheDraftAndShowsTheCoresWords() = runTest {
        openOn("mine")
        skills.edit()
        skills.draft("no frontmatter")
        editReply = """{"ok":false,"message":"$FRONTMATTER"}"""

        skills.save()

        val ui = skills.state.value!!
        assertEquals(SkillText("mine", md("mine"), "no frontmatter"), ui.open)
        assertEquals(FRONTMATTER, ui.error)
        assertEquals(listOf("learning.edit"), order)
        assertNull(ui.notice)
        assertFalse(ui.busy)
    }

    @Test
    fun aFailedEditCallKeepsTheDraft() = runTest {
        openOn("mine")
        skills.edit()
        skills.draft(NEW)
        editFails = CoreError(5000, "learning.edit failed: boom")

        skills.save()

        assertEquals(NEW, skills.state.value?.open?.editing)
        assertEquals("learning.edit failed: boom", skills.state.value?.error)
        assertEquals(listOf("learning.edit"), order)
    }

    @Test
    fun archiveAsksFirstAndSendsNothingUntilConfirmed() = runTest {
        openOn("mine")

        skills.askArchive("mine")
        assertEquals("mine", skills.state.value?.confirmArchive)
        skills.askArchive(null)
        assertNull(skills.state.value?.confirmArchive)
        skills.askArchive("ext")  // listed and editable, but not the open skill
        assertNull(skills.state.value?.confirmArchive)
        skills.askArchive("gone")
        assertNull(skills.state.value?.confirmArchive)
        readOnly("arxiv")
        order.clear()
        skills.askArchive("arxiv")  // bundled

        assertNull(skills.state.value?.confirmArchive)
        assertEquals(emptyList<String>(), order)
    }

    @Test
    fun aConfirmedArchiveClosesTheSkillAndTheListLosesIt() = runTest {
        openOn("mine")
        skills.askArchive("mine")
        deleteReply = """{"ok":true,"message":"archived 'mine' — restore with: hermes curator restore mine"}"""
        listReply = kinds(KINDS.drop(1))

        skills.confirmArchive()

        assertEquals(listOf("learning.delete", "skills.reload", "skills_list"), order)
        assertEquals("learning.delete" to params("id" to "mine"), core.calls.first())
        val ui = skills.state.value!!
        assertNull(ui.open)
        assertNull(ui.confirmArchive)
        assertEquals(KINDS.drop(1), ui.rows)
        assertEquals(SkillsNotice(SkillsNotice.Kind.ARCHIVED, "mine"), ui.notice)
        assertFalse(ui.busy)
    }

    @Test
    fun aPinnedOrExternalArchiveIsRefusedInTheCoresWords() = runTest {
        for ((name, words) in listOf("mine" to PINNED, "ext" to EXTERNAL)) {
            openOn(name)
            skills.askArchive(name)
            deleteReply = """{"ok":false,"message":"$words"}"""

            skills.confirmArchive()

            val ui = skills.state.value!!
            assertEquals(words, ui.error)
            assertEquals(name, ui.open?.name)
            assertEquals(KINDS, ui.rows)
            assertEquals(listOf("learning.delete"), order)  // no reload
            assertNull(ui.notice)
            assertFalse(ui.busy)
        }
    }

    @Test
    fun aDoubleConfirmArchivesOnce() = runTest {
        openOn("mine")
        skills.askArchive("mine")
        val gate = CompletableDeferred<Unit>().also { listGate = it }  // the first archive waits in its re-read
        val first = launch { skills.confirmArchive() }
        val second = launch { skills.confirmArchive() }
        runCurrent()

        gate.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, order.count { it == "learning.delete" })
        assertEquals(1, core.calls.count { it.first == "learning.delete" })
    }

    @Test
    fun theArchivedViewListsTheCoresArchive() = runTest {
        openOn(null)

        skills.showArchived()

        assertEquals(listOf("skills_archived" to "{}"), bridgeCalls)
        assertEquals(listOf("old", "older"), skills.state.value?.archived)
        assertFalse(skills.state.value!!.busy)

        skills.hideArchived()

        assertNull(skills.state.value?.archived)
    }

    @Test
    fun aRestoreBringsTheSkillBackReloadsAndReReadsTheArchive() = runTest {
        openOn(null)
        skills.showArchived()
        order.clear()
        bridgeCalls.clear()
        archivedReply = """{"names":["older"]}"""

        skills.restore("old")

        assertEquals(listOf("skills_restore", "skills.reload", "skills_archived"), order)
        assertEquals("skills_restore" to json("""{"name":"old"}"""), bridgeCalls.first().let { it.first to json(it.second) })
        assertEquals(listOf("skills.reload" to params("session_id" to "s1")), core.calls)
        val ui = skills.state.value!!
        assertTrue(ui.rows!!.any { it.name == "old" })
        assertEquals(listOf("older"), ui.archived)
        assertEquals(SkillsNotice(SkillsNotice.Kind.RESTORED, "old"), ui.notice)
        assertFalse(ui.busy)
    }

    @Test
    fun aRefusedRestoreSaysTheCoresWordsAndMovesNothing() = runTest {
        openOn(null)
        skills.showArchived()
        order.clear()
        restoreReply = """{"error":"$SHADOW"}"""

        skills.restore("old")

        val ui = skills.state.value!!
        assertEquals(SHADOW, ui.error)
        assertEquals(listOf("old", "older"), ui.archived)
        assertEquals(KINDS, ui.rows)
        assertEquals(listOf("skills_restore"), order)
        assertNull(ui.notice)
    }

    @Test
    fun aNameTheArchiveDoesNotListIsNotRestored() = runTest {
        openOn(null)
        skills.restore("old")  // the archive is not open
        skills.showArchived()
        order.clear()

        skills.restore("nope")

        assertEquals(emptyList<String>(), order)
        assertTrue(bridgeCalls.none { it.first == "skills_restore" })

        skills.restore("old")  // listed: restored

        assertEquals(1, bridgeCalls.count { it.first == "skills_restore" })
    }

    @Test
    fun rowsTheHubListedReplaceTheShownRowsAndSendNothing() = runTest {
        skills.listed(KINDS)  // a closed page stays closed
        assertNull(skills.state.value)
        skills.open()
        order.clear()

        skills.listed(KINDS)

        assertEquals(KINDS, skills.state.value!!.rows)
        assertEquals(emptyList<String>(), order)
    }
}

