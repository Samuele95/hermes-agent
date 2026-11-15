package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.CoreError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings page: the core's `approvals.mode` and personality over the protocol, the rest (the approval timeout, the
 * always-allowed commands, memory) over the app's `settings_read` / `settings_write`; each key by one channel only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsControlTest {
    private var mode = "smart"
    private var personality = "concise"
    private var refuse: String? = null
    private val core = FakeCoreGateway { method, params ->
        val key = params.getValue("key").jsonPrimitive.content
        when (method) {
            "config.get" -> when (key) {
                "approvals.mode" -> { refuse?.let { throw CoreError(5001, it) }; """{"value":"$mode"}""" }
                "personality" -> """{"value":"$personality"}"""
                else -> error("unexpected key $key")
            }
            "config.set" -> {
                refuse?.let { throw CoreError(if (key == "personality") 5001 else 4002, it) }
                val value = params.getValue("value").jsonPrimitive.content
                when (key) {
                    "approvals.mode" -> mode = value
                    "personality" -> personality = value
                    else -> error("unexpected key $key")
                }
                """{"key":"$key","value":"$value"}"""
            }
            else -> error("unexpected $method")
        }
    }

    private var readReply = DEFAULT_READ
    private var writeAnswer = """{"applied":true}"""
    private var writeGate: CompletableDeferred<Unit>? = null  // a write that suspends until completed
    private val bridgeCalls = mutableListOf<Pair<String, String>>()
    private var seenNow = GatewaySeen(GatewayStatus.STOPPED)
    private var providerRows = ROWS  // what provider setup lists as connected
    private val removed = mutableListOf<String>()
    private var removeGate: CompletableDeferred<Unit>? = null  // a removal that suspends until completed
    private var removeFails: CoreError? = null
    private var connectedFails: String? = null
    private var connectedReads = 0
    private var onRemove: (String) -> Unit = { s -> providerRows = providerRows.filterNot { it.slug == s } }
    private var aboutFails: Exception? = null
    private var aboutReads = 0
    private val settings = SettingsControl(core, bridge = { function, arg ->
        bridgeCalls += function to arg
        when (function) {
            "settings_read" -> readReply
            "settings_write" -> { writeGate?.await(); writeAnswer }
            else -> error("unexpected $function")
        }
    }, seen = { seenNow }, providers = ProviderAccess(connected = {
        connectedFails?.let { throw CoreError(5033, it) }
        connectedReads++
        providerRows
    }, remove = {
        removeGate?.await()
        removeFails?.let { throw it }
        removed += it
        onRemove(it)
    }), about = { aboutFails?.let { throw it }; aboutReads++; AboutTest.ABOUT })

    private fun writes() = bridgeCalls.filter { it.first == "settings_write" }.map { Json.parseToJsonElement(it.second) }
    private fun reads() = bridgeCalls.count { it.first == "settings_read" }
    private fun write(json: String) = Json.parseToJsonElement(json)

    @Test
    fun openingShowsTheCoresMode() = runTest {
        settings.open()

        assertEquals(ApprovalMode.SMART, settings.state.value?.approvals)
        assertEquals("approvals.mode", core.calls.first().second.getValue("key").jsonPrimitive.content)
    }

    @Test
    fun aChosenModeIsWrittenToTheCore() = runTest {
        settings.open()

        settings.approvals(ApprovalMode.MANUAL)

        assertEquals("manual", mode)
        assertEquals(ApprovalMode.MANUAL, settings.state.value?.approvals)
    }

    @Test
    fun aRefusedChoiceKeepsTheModeAndSaysWhy() = runTest {
        settings.open()
        refuse = "managed install — settings are read-only"

        settings.approvals(ApprovalMode.OFF)

        assertEquals(SettingsUi(ApprovalMode.SMART, "concise", CFG, connected = ROWS, about = AboutTest.ABOUT,
                                error = "managed install — settings are read-only"),
                     settings.state.value)
    }

    @Test
    fun aModeThatCannotBeReadSelectsNone() = runTest {
        refuse = "config.yaml could not be read"

        settings.open()

        assertEquals(SettingsUi(approvals = null, personality = "concise", config = CFG, connected = ROWS,
                                about = AboutTest.ABOUT, error = "config.yaml could not be read"),
                     settings.state.value)
    }

    @Test
    fun closingForgetsTheScreen() = runTest {
        settings.open()

        settings.close()

        assertNull(settings.state.value)
    }

    @Test
    fun openingShowsTheCoresModePersonalityAndSettings() = runTest {
        settings.open()

        assertEquals(SettingsUi(ApprovalMode.SMART, "concise", CFG, connected = ROWS, about = AboutTest.ABOUT), settings.state.value)
        assertEquals(listOf("settings_read" to "{}"), bridgeCalls)
        assertEquals(listOf("approvals.mode", "personality"),
                     core.calls.filter { it.first == "config.get" }.map { it.second.getValue("key").jsonPrimitive.content })
    }

    @Test
    fun settingsTheBridgeCannotReadAreNoneAndSayWhy() = runTest {
        readReply = """{"error":"config.yaml could not be read"}"""

        settings.open()

        val ui = settings.state.value!!
        assertNull(ui.config)
        assertEquals("config.yaml could not be read", ui.error)
        assertEquals(ApprovalMode.SMART, ui.approvals)
        assertEquals("concise", ui.personality)
    }

    @Test
    fun aPersonalityIsWrittenForNewSessionsOnly() = runTest {
        settings.open()
        core.calls.clear()

        settings.personality("pirate")

        val (method, params) = core.calls.first()
        assertEquals("config.set", method)
        assertEquals(write("""{"key":"personality","value":"pirate"}"""), params)
        assertFalse("session_id" in params)
        assertEquals("config.get" to "personality", core.calls.last().let { it.first to it.second.getValue("key").jsonPrimitive.content })
        assertEquals(2, core.calls.size)
        assertEquals("pirate", settings.state.value?.personality)
        assertFalse(settings.state.value!!.busy)
    }

    @Test
    fun aRefusedPersonalityKeepsTheShownOneAndShowsTheCoresWords() = runTest {
        settings.open()
        refuse = "Unknown personality: `x`."

        settings.personality("x")

        assertEquals("concise", settings.state.value?.personality)
        assertEquals("Unknown personality: `x`.", settings.state.value?.error)
        assertFalse(settings.state.value!!.busy)
    }

    @Test
    fun anEditSendsOnlyTheTouchedFieldsAndShowsTheReRead() = runTest {
        settings.open()
        readReply = DEFAULT_READ.replace("\"approvals_timeout_s\":300", "\"approvals_timeout_s\":120")
            .replace("""["git status","docker *"]""", """["git status"]""")

        settings.edit(SettingsEdit(approvalTimeoutS = 120))

        assertEquals(listOf(write("""{"approvals_timeout_s":120,"memory_enabled":null,"user_profile_enabled":null,
            "memory_char_limit":null,"user_char_limit":null,"allowlist_remove":[]}""")), writes())
        assertEquals(CFG.copy(approvalTimeoutS = 120, allowlist = listOf("git status")), settings.state.value?.config)
        assertEquals(2, reads())
        assertFalse(settings.state.value!!.busy)
    }

    @Test
    fun aRefusedWriteShowsTheCoresWordsAndKeepsWhatWasShown() = runTest {
        settings.open()
        writeAnswer = """{"error":"The approval timeout must be at least 1 second."}"""

        settings.edit(SettingsEdit(approvalTimeoutS = 0))

        val ui = settings.state.value!!
        assertEquals("The approval timeout must be at least 1 second.", ui.error)
        assertEquals(CFG, ui.config)
        assertFalse(ui.busy)
        assertEquals(1, reads())
    }

    @Test
    fun aMemorySwitchAndALimitAreWrittenAlone() = runTest {
        settings.open()

        settings.edit(SettingsEdit(memoryEnabled = false))
        settings.edit(SettingsEdit(profileChars = 500))

        assertEquals(listOf(write("""{"approvals_timeout_s":null,"memory_enabled":false,"user_profile_enabled":null,
                                     "memory_char_limit":null,"user_char_limit":null,"allowlist_remove":[]}"""),
                            write("""{"approvals_timeout_s":null,"memory_enabled":null,"user_profile_enabled":null,
                                     "memory_char_limit":null,"user_char_limit":500,"allowlist_remove":[]}""")), writes())
    }

    @Test
    fun removingAnAlwaysAllowedCommandAsksFirst() = runTest {
        settings.open()

        settings.askRemove("docker *")
        assertEquals("docker *", settings.state.value?.confirmRemove)
        assertEquals(emptyList<Any>(), writes())

        settings.askRemove("not listed")
        assertNull(settings.state.value?.confirmRemove)
        settings.askRemove("docker *")
        settings.askRemove(null)
        assertNull(settings.state.value?.confirmRemove)
        settings.confirmRemove()  // nothing asked: nothing removed
        assertEquals(emptyList<Any>(), writes())

        settings.askRemove("docker *")
        settings.confirmRemove()

        assertEquals(listOf(write("""{"approvals_timeout_s":null,"memory_enabled":null,"user_profile_enabled":null,
            "memory_char_limit":null,"user_char_limit":null,"allowlist_remove":["docker *"]}""")), writes())
        assertEquals("settings_read", bridgeCalls.last().first)  // the re-read
        assertEquals(2, reads())
        assertNull(settings.state.value?.confirmRemove)
    }

    @Test
    fun aSecondConfirmationRemovesNothingMore() = runTest {
        settings.open()
        settings.askRemove("docker *")
        writeGate = CompletableDeferred()

        val first = launch { settings.confirmRemove() }
        val second = launch { settings.confirmRemove() }
        runCurrent()
        writeGate!!.complete(Unit)
        first.join(); second.join()
        settings.confirmRemove()

        assertEquals(1, writes().size)
    }

    @Test
    fun aRemovalWhileTheGatewayRunsWaitsForItsNextStart() = runTest {
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 7)
        settings.open()
        settings.askRemove("docker *")

        settings.confirmRemove()

        assertTrue(settings.state.value!!.nextStart)
        settings.followGateway(flowOf(GatewaySeen(GatewayStatus.RUNNING, pid = 7)))
        assertTrue("the gateway that held the old list still runs", settings.state.value!!.nextStart)
        settings.followGateway(flowOf(GatewaySeen(GatewayStatus.RUNNING, pid = 7), GatewaySeen(GatewayStatus.RUNNING, pid = 8)))
        assertFalse(settings.state.value!!.nextStart)

        seenNow = GatewaySeen(GatewayStatus.STOPPED)
        settings.askRemove("git status")
        settings.confirmRemove()
        assertFalse(settings.state.value!!.nextStart)
    }

    @Test
    fun aRefusedRemovalShowsItsWordsKeepsTheListAndMarksNoNextStart() = runTest {
        seenNow = GatewaySeen(GatewayStatus.RUNNING, pid = 7)
        settings.open()
        writeAnswer = """{"error":"The always-allowed list could not be read; nothing was changed."}"""

        settings.askRemove("docker *")
        settings.confirmRemove()

        val ui = settings.state.value!!
        assertEquals("The always-allowed list could not be read; nothing was changed.", ui.error)
        assertEquals(CFG, ui.config)
        assertNull(ui.confirmRemove)
        assertFalse(ui.busy)
        assertFalse("a refused removal changed nothing the gateway holds", ui.nextStart)
        assertEquals(1, reads())  // a refusal re-reads nothing
        assertEquals(1, writes().size)
    }

    @Test
    fun whileAWriteRunsNothingElseIsSent() = runTest {
        settings.open()
        core.calls.clear()
        writeGate = CompletableDeferred()

        val running = launch { settings.edit(SettingsEdit(memoryChars = 100)) }
        runCurrent()
        assertTrue(settings.state.value!!.busy)
        settings.approvals(ApprovalMode.OFF)
        settings.personality("pirate")
        settings.edit(SettingsEdit(approvalTimeoutS = 9))
        settings.askRemove("docker *")
        settings.confirmRemove()
        writeGate!!.complete(Unit)
        running.join()

        assertEquals(emptyList<Any>(), core.calls)
        assertEquals(1, writes().size)
        assertNull(settings.state.value?.confirmRemove)
        assertFalse(settings.state.value!!.busy)
    }

    // Providers (F10): listed when the page opens; a removal asks first and goes through provider setup's one forget path.

    @Test
    fun openingListsTheConnectedProviders() = runTest {
        settings.open()

        assertEquals(ROWS, settings.state.value?.connected)
        assertEquals(emptyList<String>(), removed)
    }

    @Test
    fun providersThatCannotBeReadAreNoneAndSayWhy() = runTest {
        connectedFails = "model.options failed"

        settings.open()

        val ui = settings.state.value!!
        assertNull("cannot tell is not none (D5)", ui.connected)
        assertEquals("model.options failed", ui.error)
        assertEquals(ApprovalMode.SMART, ui.approvals)
        assertEquals("concise", ui.personality)
        assertEquals(CFG, ui.config)
    }

    @Test
    fun removingAProviderAsksFirstAndRemovesOnlyThatOne() = runTest {
        settings.open()

        settings.askRemoveProvider("nous")
        assertEquals("nous", settings.state.value?.confirmProvider)
        assertEquals(emptyList<String>(), removed)

        settings.askRemoveProvider("dgx")  // listed, but deleted in provider setup (D3)
        assertNull(settings.state.value?.confirmProvider)
        settings.askRemoveProvider("ghost")
        assertNull(settings.state.value?.confirmProvider)
        settings.askRemoveProvider("nous")
        settings.askRemoveProvider(null)
        assertNull(settings.state.value?.confirmProvider)
        settings.confirmRemoveProvider()  // nothing asked: nothing removed
        assertEquals(emptyList<String>(), removed)

        settings.askRemoveProvider("nous")
        settings.confirmRemoveProvider()

        assertEquals(listOf("nous"), removed)
        val ui = settings.state.value!!
        assertEquals("the core's re-read", listOf(COPILOT, ENDPOINT), ui.connected)
        assertEquals(2, connectedReads)
        assertNull(ui.confirmProvider)
        assertFalse(ui.busy)
        assertNull(ui.error)
        assertEquals(emptyList<Any>(), writes())
    }

    @Test
    fun oneQuestionAtATime() = runTest {
        settings.open()

        settings.askRemove("docker *")
        settings.askRemoveProvider("nous")
        assertNull(settings.state.value?.confirmRemove)
        assertEquals("nous", settings.state.value?.confirmProvider)

        settings.askRemove("docker *")
        assertNull(settings.state.value?.confirmProvider)
    }

    @Test
    fun aSecondProviderConfirmationRemovesNothingMore() = runTest {
        settings.open()
        settings.askRemoveProvider("nous")
        removeGate = CompletableDeferred()

        val first = launch { settings.confirmRemoveProvider() }
        val second = launch { settings.confirmRemoveProvider() }
        runCurrent()
        removeGate!!.complete(Unit)
        first.join(); second.join()
        settings.confirmRemoveProvider()

        assertEquals(1, removed.size)
    }

    @Test
    fun aProviderStillListedAfterItsRemovalIsManagedOutsideTheApp() = runTest {
        onRemove = {}  // the core answered 4005 and nothing came off (D6)
        settings.open()
        settings.askRemoveProvider("nous")

        settings.confirmRemoveProvider()

        val ui = settings.state.value!!
        assertEquals("Nous Portal is still connected: its credential is managed outside the app.", ui.error)
        assertEquals(ROWS, ui.connected)
        assertFalse(ui.busy)
    }

    @Test
    fun aRefusedRemovalShowsTheCoresWordsAndKeepsTheList() = runTest {
        removeFails = CoreError(5035, "DEEPSEEK_API_KEY is managed by your system: cannot remove it")
        settings.open()
        settings.askRemoveProvider("nous")

        settings.confirmRemoveProvider()

        val ui = settings.state.value!!
        assertEquals("DEEPSEEK_API_KEY is managed by your system: cannot remove it", ui.error)
        assertEquals(ROWS, ui.connected)
        assertEquals("no re-read after a refusal", 1, connectedReads)
        assertFalse(ui.busy)
    }

    @Test
    fun aboutIsReadAtEachOpenAndShown() = runTest {
        settings.open()

        assertEquals(AboutTest.ABOUT, settings.state.value?.about)
        assertEquals(1, aboutReads)

        settings.close()
        settings.open()

        assertEquals(2, aboutReads)
    }

    @Test
    fun anAboutThatCannotBeReadIsAbsentAndEverythingElseShows() = runTest {
        aboutFails = java.io.FileNotFoundException("payload.json")

        settings.open()

        val ui = settings.state.value!!
        assertNull(ui.about)
        assertEquals(CFG, ui.config)
        assertEquals(ROWS, ui.connected)
        assertEquals("Something went wrong (FileNotFoundException).", ui.error)

        refuse = "config.yaml could not be read"  // the mode fails first: its words show
        settings.open()

        assertNull(settings.state.value?.about)
        assertEquals("config.yaml could not be read", settings.state.value?.error)
    }

    @Test
    fun aWriteNeverReadsAboutAgain() = runTest {
        settings.open()

        settings.edit(SettingsEdit(approvalTimeoutS = 120))

        assertEquals(AboutTest.ABOUT, settings.state.value?.about)
        assertEquals(1, aboutReads)
    }

    companion object {
        /** `settings_read`'s answer: the core's defaults, two always-allowed commands and two personalities. */
        const val DEFAULT_READ = """{"approvals_timeout_s":300,"command_allowlist":["git status","docker *"],"memory_enabled":true,
            "user_profile_enabled":true,"memory_char_limit":2200,"user_char_limit":1375,
            "personalities":[{"name":"helpful","description":"You are a helpful, friendly AI assistant."},
                             {"name":"concise","description":"You are a concise assistant. Keep responses brief a..."}]}"""

        val NOUS = ConnectedProvider("nous", "Nous Portal", removable = true, current = false)
        val COPILOT = ConnectedProvider("copilot", "GitHub Copilot", removable = true, current = true)
        val ENDPOINT = ConnectedProvider("dgx", "DGX", removable = false, current = false)
        val ROWS = listOf(NOUS, COPILOT, ENDPOINT)

        val CFG = ConfigSettings(300, listOf("git status", "docker *"), true, true, 2200, 1375,
                                 listOf(Personality("helpful", "You are a helpful, friendly AI assistant."),
                                        Personality("concise", "You are a concise assistant. Keep responses brief a...")))
    }
}
