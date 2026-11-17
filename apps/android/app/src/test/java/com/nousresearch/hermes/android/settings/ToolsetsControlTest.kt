package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.Method
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Toolsets page: the config's toolsets listed, a switch sent without a session and shown as the core answered. */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsetsControlTest {
    companion object {
        /** `toolsets.list {}`: two switchable on, one off, a platform composite, a hidden one and a non-switchable one. */
        const val LIST = """{"toolsets":[
            {"name":"browser","description":"Navigate, click and read pages","tool_count":14,"enabled":true},
            {"name":"web","description":"Web search and content extraction","tool_count":2,"enabled":true},
            {"name":"kanban","description":"Track work on a board","tool_count":14,"enabled":false},
            {"name":"hermes-telegram","description":"Telegram bot toolset","tool_count":51,"enabled":false},
            {"name":"discord","description":"Read and post in Discord","tool_count":1,"enabled":false},
            {"name":"project","description":"Project tools","tool_count":1,"enabled":true}]}"""

        /** `toolsets_keys`: what the core switches for the CLI, by its GUI labels, and which need keys. */
        const val KEYS = """{"needs_keys":["web"],"switchable":[{"name":"browser","label":"Browser Automation"},
            {"name":"web","label":"Web Search & Scraping"},{"name":"kanban","label":"Kanban"},
            {"name":"discord","label":"Discord (read/participate)"}]}"""

        /** `tools.configure`'s answer: the core's re-read [enabled] set, and the names it did not know. */
        fun configured(enabled: List<String>, unknown: List<String> = emptyList()): String =
            """{"changed":[],"enabled_toolsets":${enabled.json()},"info":null,"missing_servers":[],"reset":false,"unknown":${unknown.json()}}"""

        private fun List<String>.json() = joinToString(",", "[", "]") { "\"$it\"" }
    }

    private var listFails: Exception? = null
    private var keysReply = KEYS
    private var configureReply = configured(listOf("web"))
    private var configureFails: Exception? = null
    private val order = mutableListOf<String>()  // every call, core and bridge, in the order sent
    private val fake = FakeCoreGateway { method, _ ->
        when (method) {
            "toolsets.list" -> { listFails?.let { throw it }; LIST }
            "tools.configure" -> { configureFails?.let { throw it }; configureReply }
            else -> error("unexpected $method")
        }
    }
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()  // a core method held until completed

    /** [fake], with a call whose method has a gate in [gates] held until it opens; [order] records it as sent. */
    private val core: CoreGateway = object : CoreGateway by fake {
        override suspend fun <P, R> call(method: Method<P, R>, params: P): R {
            order += method.name
            gates[method.name]?.await()
            return fake.call(method, params)
        }
    }
    private val toolsets = ToolsetsControl(core, AppBridge { function, _ ->
        order += function
        when (function) {
            "toolsets_keys" -> keysReply
            else -> error("unexpected $function")
        }
    })

    private fun ui() = checkNotNull(toolsets.state.value) { "the page is closed" }
    private fun sent(method: String): List<JsonObject> = fake.calls.filter { it.first == method }.map { it.second }
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun enabled() = ui().rows.orEmpty().associate { it.name to it.enabled }

    /** The page open on the fixture's list, with nothing recorded yet. */
    private suspend fun opened() {
        toolsets.open()
        order.clear()
        fake.calls.clear()
    }

    @Test
    fun openingShowsThePageAtOnceThenTheConfigsToolsets() = runTest {
        gates["toolsets.list"] = CompletableDeferred()

        val opening = launch { toolsets.open() }
        runCurrent()

        assertEquals(ToolsetsUi(busy = true), ui())
        gates.getValue("toolsets.list").complete(Unit)
        opening.join()

        assertEquals(listOf("browser", "kanban", "web"), ui().rows?.map { it.name })
        assertFalse(ui().busy)
        assertEquals(listOf("toolsets.list", "toolsets_keys"), order)
        assertEquals(json("{}"), sent("toolsets.list").single())  // no session_id: the config's set, which new chats take (D5)
    }

    @Test
    fun aListTheCoreCannotReadSaysItsWords() = runTest {
        listFails = CoreError(5032, "toolsets.list failed: boom")

        toolsets.open()

        assertEquals(ToolsetsUi(error = "toolsets.list failed: boom"), ui())

        listFails = null
        keysReply = """{"error":"keys failed"}"""

        toolsets.open()

        assertEquals(ToolsetsUi(error = "keys failed"), ui())
    }

    @Test
    fun aSwitchNamesNoSessionAndShowsTheCoresAnswer() = runTest {
        opened()

        toolsets.set(listOf("browser"), false)

        assertEquals(json("""{"action":"disable","names":["browser"]}"""), sent("tools.configure").single())
        assertEquals(mapOf("browser" to false, "kanban" to false, "web" to true), enabled())
        assertFalse(ui().busy)
        assertNull(ui().error)
        assertEquals(listOf("tools.configure"), order)
    }

    @Test
    fun aSwitchTheCoreDidNotApplyShowsItsStateNotTheAsk() = runTest {
        opened()
        configureReply = configured(listOf("browser", "web"), unknown = listOf("kanban"))

        toolsets.set(listOf("kanban"), true)

        assertEquals(json("""{"action":"enable","names":["kanban"]}"""), sent("tools.configure").single())
        assertEquals(mapOf("browser" to true, "kanban" to false, "web" to true), enabled())
        assertEquals("“Kanban” can't be switched here.", ui().error)
    }

    @Test
    fun aRefusedSwitchKeepsTheRowsAndSaysTheCoresWords() = runTest {
        opened()
        val before = ui().rows
        configureFails = CoreError(5035, "tools.configure failed: disk full")

        toolsets.set(listOf("web"), false)

        assertEquals(before, ui().rows)
        assertEquals("tools.configure failed: disk full", ui().error)
        assertFalse(ui().busy)
    }

    @Test
    fun aSecondSwitchWhileOneRunsSendsNothing() = runTest {
        opened()
        gates["tools.configure"] = CompletableDeferred()

        val first = launch { toolsets.set(listOf("browser"), false) }
        runCurrent()
        assertTrue("every switch is off while one is written", ui().busy)

        val second = launch { toolsets.set(listOf("web"), false) }
        runCurrent()
        gates.getValue("tools.configure").complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("tools.configure"), order)
        assertEquals(json("""{"action":"disable","names":["browser"]}"""), sent("tools.configure").single())
        assertFalse(ui().busy)
    }

    @Test
    fun aNameNotListedOrNoNameSendsNothing() = runTest {
        opened()

        toolsets.set(listOf("hermes-telegram"), false)
        toolsets.set(emptyList(), true)
        toolsets.close()
        toolsets.set(listOf("browser"), false)

        assertEquals(emptyList<String>(), order)
        assertNull(toolsets.state.value)
    }

    @Test
    fun typingNarrowsTheRowsAndSendsNothing() = runTest {
        opened()

        toolsets.query("kan")

        assertEquals(listOf("kanban"), ui().shown()?.map { it.name })
        assertEquals(3, ui().rows?.size)
        assertEquals(emptyList<String>(), order)
    }

    @Test
    fun aClosedPageStaysClosedWhenItsListArrives() = runTest {
        gates["toolsets.list"] = CompletableDeferred()

        val opening = launch { toolsets.open() }
        runCurrent()
        toolsets.close()
        gates.getValue("toolsets.list").complete(Unit)
        opening.join()

        assertNull(toolsets.state.value)
    }
}
