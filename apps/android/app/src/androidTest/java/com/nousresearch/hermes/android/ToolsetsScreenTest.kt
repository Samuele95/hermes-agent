package com.nousresearch.hermes.android

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.settings.ApprovalMode
import com.nousresearch.hermes.android.settings.SettingsActions
import com.nousresearch.hermes.android.settings.SettingsScreen
import com.nousresearch.hermes.android.settings.SettingsUi
import com.nousresearch.hermes.android.settings.ToolsetRow
import com.nousresearch.hermes.android.settings.ToolsetActions
import com.nousresearch.hermes.android.settings.ToolsetsActions
import com.nousresearch.hermes.android.settings.ToolsetsScreen
import com.nousresearch.hermes.android.settings.ToolsetsUi
import com.nousresearch.hermes.android.settings.opened
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ToolsetsListResult
import com.nousresearch.hermes.bridge.protocol._SessionScoped
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Tools: the toolsets the phone can switch, each with its count, Needs keys and a switch handed over as a
 * name; on the device, the page reads the real core's toolsets.
 */
@RunWith(AndroidJUnit4::class)
class ToolsetsScreenTest {
    private companion object {
        val BROWSER = ToolsetRow("browser", "Browser Automation", "Navigate, click and read pages", 14, enabled = true, needsKeys = false)
        val KANBAN = ToolsetRow("kanban", "Kanban", "Track work on a board", 1, enabled = false, needsKeys = false)
        val WEB = ToolsetRow("web", "Web Search & Scraping", "Web search and content extraction", 2, enabled = true, needsKeys = true)
        val ROWS = listOf(BROWSER, KANBAN, WEB)
    }

    @get:Rule
    val compose = createComposeRule()

    private val queries = mutableListOf<String>()
    private val sets = mutableListOf<Pair<List<String>, Boolean>>()
    private var closes = 0
    private val actions = ToolsetsActions(query = { queries += it }, set = { names, on -> sets += names to on }, close = { closes++ })

    /** The page, its state held so a test can change it. */
    private fun show(initial: ToolsetsUi): (ToolsetsUi) -> Unit {
        var state by mutableStateOf(initial)
        compose.setContent { ToolsetsScreen(state, actions) }
        return { state = it }
    }

    /** The clickable row under [tag] (a ListRow's click sits on its inner row). */
    private fun clickableUnder(tag: String): SemanticsNodeInteraction = compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(tag)))

    @Test
    fun settingsOpensTheTools() {
        var opened = 0
        fun settingsActions(openToolsets: (() -> Unit)?) =
            SettingsActions(approvals = {}, personality = {}, edit = {}, askRemove = {}, confirmRemove = {}, restartGateway = {},
                            askRemoveProvider = {}, confirmRemoveProvider = {}, openSkills = {}, copy = {}, reactions = {},
                            openToolsets = openToolsets)
        var state by mutableStateOf(SettingsUi(ApprovalMode.SMART))
        var with by mutableStateOf(settingsActions { opened++ })
        compose.setContent { SettingsScreen(state, with, onClose = {}) }

        compose.onNodeWithText("TOOLS").performScrollTo()
        clickableUnder("settings-toolsets").performScrollTo().assertExists()
        compose.onNodeWithText("Turn toolsets on or off").assertExists()
        clickableUnder("settings-toolsets").performClick()
        assertEquals(1, opened)

        state = state.copy(busy = true)
        clickableUnder("settings-toolsets").performScrollTo().assertIsNotEnabled()

        with = settingsActions(null)
        compose.onNodeWithTag("settings-toolsets").assertDoesNotExist()
        compose.onNodeWithText("TOOLS").assertDoesNotExist()
    }

    @Test
    fun theListShowsEachToolsetsCountKeysAndSwitch() {
        val set = show(ToolsetsUi(rows = ROWS))

        compose.onNodeWithTag("toolsets").assertExists()
        compose.onNodeWithTag("toolsets-new-chats").assertTextEquals("Changes apply to new chats; an open chat keeps what it started with.")
        compose.onNode(hasTestTag("toolset-browser") and hasAnyDescendant(hasText("Browser Automation"))).assertExists()
        compose.onNodeWithText("Web search and content extraction").assertExists()
        assertEquals(listOf("toolset-browser", "toolset-kanban", "toolset-web"), rowTags())
        compose.onNodeWithTag("toolset-count-web").assertTextEquals("2 tools")
        compose.onNodeWithTag("toolset-count-kanban").assertTextEquals("1 tool")
        compose.onNodeWithTag("toolset-needs-keys-web").assertTextEquals("Needs keys")
        compose.onNodeWithTag("toolset-needs-keys-browser").assertDoesNotExist()

        compose.onNode(hasTestTag("toolset-switch-browser") and hasContentDescription("Turn Browser Automation toolset off")).assertIsOn()
        compose.onNodeWithTag("toolset-switch-browser").performClick()
        assertEquals(listOf(listOf("browser") to false), sets)
        compose.onNode(hasTestTag("toolset-switch-kanban") and hasContentDescription("Turn Kanban toolset on")).assertIsOff()

        set(ToolsetsUi(rows = ROWS, busy = true))
        listOf("browser", "kanban", "web").forEach { compose.onNodeWithTag("toolset-switch-$it").assertIsNotEnabled() }

        set(ToolsetsUi(rows = ROWS))
        compose.onNodeWithTag("toolsets-search").performTextInput("kan")
        assertEquals(listOf("kan"), queries)
        set(ToolsetsUi(rows = ROWS, query = "zzz"))
        compose.onNode(hasTestTag("toolsets-no-match") and hasText("Nothing matches “zzz”.")).assertExists()
        compose.onNodeWithTag("toolset-browser").assertDoesNotExist()

        set(ToolsetsUi(rows = emptyList()))
        compose.onNode(hasTestTag("toolsets-none") and hasText("No toolsets found")).assertExists()

        set(ToolsetsUi(error = "toolsets.list failed: boom"))
        compose.onNode(hasTestTag("toolsets-error") and hasText("toolsets.list failed: boom")).assertExists()
        compose.onNodeWithTag("toolsets-none").assertDoesNotExist()

        compose.onNodeWithTag("toolsets-close").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun theRealCoreListsTheToolsetsThePhoneCanSwitch() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        val toolsets = ready.pages.toolsets
        try {
            for (pass in listOf("cold", "warm")) {
                val began = SystemClock.elapsedRealtime()
                toolsets.open()
                Log.i("ToolsetsScreenTest", "open() ($pass: toolsets.list + toolsets_keys) in ${SystemClock.elapsedRealtime() - began} ms")
            }
            val ui = toolsets.state.value!!
            assertNull(ui.error)
            val rows = ui.rows!!
            assertTrue("only ${rows.size} toolsets", rows.size >= 15)
            val names = rows.map { it.name }
            listOf("web", "terminal", "file", "browser").forEach { assertTrue("$it is not listed: $names", it in names) }
            assertTrue(names.toString(), names.none { it.startsWith("hermes-") || it == "coding" || it == "safe" || it in ToolsetRow.HIDDEN })
            assertTrue(rows.all { it.tools == null })
            Log.i("ToolsetsScreenTest", "${rows.size} rows; needs keys ${rows.filter { it.needsKeys }.map { it.name }}; " +
                                        "on ${rows.filter { it.enabled }.map { it.name }}; off ${rows.filterNot { it.enabled }.map { it.name }}")
        } finally {
            toolsets.close()
        }
    }

    @Test
    fun aTapShowsItsToolsAndWhereKeysAreSet() {
        val shown = mutableListOf<String>()
        var backs = 0
        var keyOpens = 0
        val withKeys = ToolsetActions(show = { shown += it }, back = { backs++ }, keys = { keyOpens++ })
        var state by mutableStateOf(ToolsetsUi(rows = ROWS))
        var with by mutableStateOf(ToolsetsActions(query = {}, set = { _, _ -> }, close = {}, toolset = withKeys))
        compose.setContent { ToolsetsScreen(state, with) }

        compose.onNodeWithTag("toolset-open-web").performClick()
        assertEquals(listOf("web"), shown)

        val opened = ROWS.map { if (it.name == "web") it.copy(tools = listOf("web_extract", "web_search")) else it }
        state = ToolsetsUi(rows = opened, open = "web")
        compose.onNodeWithTag("toolset-page").assertExists()
        compose.onNodeWithText("Web Search & Scraping").assertExists()
        compose.onNodeWithTag("toolset-description").assertTextEquals("Web search and content extraction")
        compose.onNodeWithTag("toolset-tool-web_search").performScrollTo().assertTextEquals("web_search")
        compose.onNodeWithText("2 tools").assertExists()
        compose.onNodeWithTag("toolset-needs-keys").assertTextEquals("Needs keys")
        compose.onNodeWithTag("toolset-keys-explain").assertTextEquals(
            "Hermes doesn't have a key this toolset needs. Keys are added with a provider in Setup and kept in the phone's keystore.")
        compose.onNodeWithTag("toolset-keys-open").performScrollTo().assertTextEquals("Set up a provider").performClick()
        assertEquals(1, keyOpens)
        compose.onNodeWithTag("toolset-new-chats").performScrollTo()
            .assertTextEquals("Changes apply to new chats; an open chat keeps what it started with.")

        with = ToolsetsActions(query = {}, set = { _, _ -> }, close = {}, toolset = ToolsetActions(show = {}, back = { backs++ }))
        compose.onNodeWithTag("toolset-needs-keys").assertExists()
        compose.onNodeWithTag("toolset-keys-open").assertDoesNotExist()

        state = ToolsetsUi(rows = opened, open = "browser")
        listOf("toolset-needs-keys", "toolset-keys-explain", "toolset-keys-open").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }

        state = ToolsetsUi(rows = ROWS, open = "browser", busy = true)
        compose.onNodeWithTag("toolset-tools").assertDoesNotExist()
        compose.onNodeWithTag("toolset-close").assertIsNotEnabled()

        state = ToolsetsUi(rows = listOf(BROWSER.copy(description = "", tools = emptyList())), open = "browser")
        compose.onNodeWithTag("toolset-description").assertTextEquals("No description.")
        compose.onNodeWithTag("toolset-close").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun theRealCoreShowsAToolsetsToolsAndASwitchLeavesTheOpenChatAlone() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
        val ready = withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } as CoreState.Ready
        val toolsets = ready.pages.toolsets
        val config = File(app.filesDir, ".hermes/config.yaml")
        val original = config.takeIf { it.exists() }?.readBytes()
        var began = SystemClock.elapsedRealtime()
        val enabledBefore = ready.gateway.call(Methods.TOOLSETS_LIST, _SessionScoped()).toolsets.associate { it.name to it.enabled }
        Log.i("ToolsetsScreenTest", "toolsets.list alone in ${SystemClock.elapsedRealtime() - began} ms, ${enabledBefore.size} rows")
        began = SystemClock.elapsedRealtime()
        val listed = ready.gateway.call(Methods.TOOLS_LIST, _SessionScoped())
        Log.i("ToolsetsScreenTest", "tools.list alone in ${SystemClock.elapsedRealtime() - began} ms, " +
                                    "${Json.encodeToString(ToolsetsListResult.serializer(), listed).length} chars")
        try {
            toolsets.open()
            began = SystemClock.elapsedRealtime()
            toolsets.show("web")
            Log.i("ToolsetsScreenTest", "show(web) (tools.list) in ${SystemClock.elapsedRealtime() - began} ms")
            assertNull(toolsets.state.value!!.error)
            assertTrue(toolsets.state.value!!.opened()!!.tools!!.contains("web_search"))
            toolsets.back()

            val name = toolsets.state.value!!.rows!!.first { it.enabled }.name
            val chat = ready.chat.openSessionId?.value
            Log.i("ToolsetsScreenTest", "a chat was open during the switch: ${chat != null}")
            val events = mutableListOf<CoreEvent>()
            val collecting = launch(Dispatchers.Default) { ready.gateway.events.collect { synchronized(events) { events += it } } }
            delay(200)
            began = SystemClock.elapsedRealtime()
            toolsets.set(listOf(name), false)
            Log.i("ToolsetsScreenTest", "set($name, off) (tools.configure) in ${SystemClock.elapsedRealtime() - began} ms")
            assertNull(toolsets.state.value!!.error)
            assertEquals(false, toolsets.state.value!!.rows!!.single { it.name == name }.enabled)
            assertEquals(false, ready.gateway.call(Methods.TOOLSETS_LIST, _SessionScoped()).toolsets.single { it.name == name }.enabled)
            delay(2_000)
            collecting.cancel()
            if (chat != null) {
                val infos = synchronized(events) { events.filter { it.type == "session.info" && it.sessionId == chat } }
                assertTrue("the open chat was reset: $infos", infos.isEmpty())
            }
        } finally {
            if (original != null) config.writeBytes(original) else config.delete()
            assertTrue(if (original != null) config.readBytes().contentEquals(original) else !config.exists())
            assertEquals(enabledBefore,
                         ready.gateway.call(Methods.TOOLSETS_LIST, _SessionScoped()).toolsets.associate { it.name to it.enabled })
            toolsets.close()
        }
    }

    /** The rows' tags in the order drawn, top to bottom. */
    private fun rowTags(): List<String> =
        compose.onAllNodes(SemanticsMatcher("a toolset row") {
            val tag = it.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            tag.startsWith("toolset-") && listOf("toolset-switch-", "toolset-count-", "toolset-needs-keys-", "toolset-open-").none(tag::startsWith)
        }).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }.map { it.config[SemanticsProperties.TestTag] }
}
