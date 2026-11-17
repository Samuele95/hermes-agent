package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
    private val core = FakeCoreGateway { method, _ ->
        order += method
        when (method) {
            "skills.reload" -> { reloadFails?.let { throw it }; RELOADED }
            "learning.detail" -> detailReply
            else -> error("unexpected $method")
        }
    }
    private val skills = SkillsControl(core, { function, arg ->
        bridgeCalls += function to arg
        order += function
        when (function) {
            "skills_list" -> { listGate?.await(); listReply }
            "skills_enable" -> { enableGate?.await(); enableAnswer }
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
}
