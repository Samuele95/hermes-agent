package com.nousresearch.hermes.android.settings

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
    }

    private var listReply = LIST
    private var listGate: CompletableDeferred<Unit>? = null  // a list that suspends until completed
    private var enableAnswer = LIST
    private var enableGate: CompletableDeferred<Unit>? = null  // a change that suspends until completed
    private val bridgeCalls = mutableListOf<Pair<String, String>>()
    private val skills = SkillsControl { function, arg ->
        bridgeCalls += function to arg
        when (function) {
            "skills_list" -> { listGate?.await(); listReply }
            "skills_enable" -> { enableGate?.await(); enableAnswer }
            else -> error("unexpected $function")
        }
    }

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
}
