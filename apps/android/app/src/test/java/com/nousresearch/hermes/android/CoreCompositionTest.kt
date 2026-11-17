package com.nousresearch.hermes.android

import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.ContextUi
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.chat.ExportFile
import com.nousresearch.hermes.android.chat.FakeCoreGateway
import com.nousresearch.hermes.android.chat.NoticeShortcut
import com.nousresearch.hermes.android.chat.Notifier
import com.nousresearch.hermes.android.chat.RuntimeSessionId
import com.nousresearch.hermes.android.chat.StoredSessionId
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.PlatformState
import com.nousresearch.hermes.android.jobs.JobId
import com.nousresearch.hermes.android.jobs.JobsWarning
import com.nousresearch.hermes.android.settings.AboutTest
import com.nousresearch.hermes.android.settings.ConnectedProvider
import com.nousresearch.hermes.android.settings.SettingsControlTest
import com.nousresearch.hermes.android.settings.SkillRow
import com.nousresearch.hermes.android.settings.ToolsetsControlTest
import com.nousresearch.hermes.android.setup.Readiness
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.CoreRuntime
import com.nousresearch.hermes.bridge.FrameSink
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.protocol.BackendContract
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownResult
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat process's composition root runs over any [CoreRuntime]: here, a core that is a function. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreCompositionTest {
    private var configured = true
    private var options = """{"providers":[]}"""
    private var recent: String? = null
    private var resumeReply: () -> String = { """{"session_id":"s-last","message_count":0,"messages":[],"session_key":"db-last",
                                   "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}""" }
    /** The core's answer to `prompt.submit`. */
    private var submitReply = """{"status":"streaming"}"""
    private val core = FakeCoreGateway { method, _ ->
        when (method) {
            "prompt.submit" -> submitReply
            "session.create" -> """{"session_id":"s1","stored_session_id":"db1","message_count":0,"messages":[],
                                   "info":{"desktop_contract":${BackendContract.BACKEND_CONTRACT}}}"""
            "setup.status" -> """{"provider_configured":$configured}"""
            "model.options" -> options
            "model.disconnect" -> """{"slug":"deepseek","name":"DeepSeek","disconnected":true}"""
            "config.get" -> """{"config":{}}"""
            "session.most_recent" -> """{"session_id":${recent?.let { "\"$it\"" } ?: "null"}}"""
            "session.resume" -> resumeReply()
            "session.context_breakdown" -> EMPTY_BREAKDOWN
            "setup.runtime_check" -> """{"ok":true,"provider":"deepseek","model":"deepseek-v4-pro"}"""
            "cron.manage" -> """{"success":true,"count":1,"jobs":[{"job_id":"j1","name":"n","state":"scheduled"}]}"""
            "toolsets.list" -> ToolsetsControlTest.LIST
            "tools.configure" -> ToolsetsControlTest.configured(listOf("web"))
            else -> error("unexpected $method")
        }
    }
    private val bridgeCalls = mutableListOf<String>()
    /** The arguments the app's Python half was called with, by call. */
    private val bridgeArgs = mutableMapOf<String, String>()
    private var coreCallsAtWarm: List<String>? = null
    private val runtime = object : CoreRuntime {
        val connected = mutableListOf<HermesPaths>()
        override suspend fun connect(paths: HermesPaths, secrets: SecretStore): CoreGateway = core.also { connected += paths }
        override fun startGateway(paths: HermesPaths, secrets: SecretStore, events: FrameSink) = error("not the chat's core")
        override val bridge = AppBridge { function, arg ->
            bridgeCalls += function
            bridgeArgs[function] = arg
            when (function) {
                "runtime_facts" -> """{"python":"3.14.7","sqlite":"3.53.4","fts5":true,"executable":"","core_release":"2026.9.24"}"""
                "cron_runs" -> """{"runs":[{"session_id":"cron_j1_20261113_093000","started_at":1794562200,"ended_at":1794562260,
                                  "messages":4,"preview":"Say hello.","active":false}]}"""
                "telegram_status" -> """{"token":true,"allowed_user":"42","sdk":true,"enabled":true,"live":{"state":"connected",
                                        "error_message":null,"writer_pid":7,"updated_at":"2026-11-13T00:00:00+00:00"}}"""
                "cron_warm_delivery_targets" -> { coreCallsAtWarm = core.calls.map { it.first }; "{}" }
                "settings_read" -> SettingsControlTest.DEFAULT_READ
                "skills_list" -> """{"skills":[{"name":"arxiv","description":"Search arXiv.","category":"research","enabled":true,"provenance":"bundled","uses":0}]}"""
                "session_export" -> """{"path":"/data/app-files/cache/exports/session-db1.json","name":"session-db1.json","bytes":42}"""
                "toolsets_keys" -> ToolsetsControlTest.KEYS
                else -> "{}"
            }
        }
    }
    private val secrets = object : SecretStore {
        override fun put(name: String, value: ByteArray) {}
        override fun get(name: String): ByteArray? = null
        override fun delete(name: String) {}
        override fun all() = emptyMap<String, ByteArray>()
    }
    private val turns = object : TurnHost {
        override fun begin(turn: String) {}
        override fun end(turn: String) {}
    }
    /** The platform's collaborators every composition starts from (after [secrets] and [turns]: properties initialise in order). */
    private val deps = CoreDeps(secrets, turns, Notifier.None)
    private val paths = File("/data/app-files").let { HermesPaths(File(it, "runtime"), it, File(it, ".hermes"), File(it, "cache")) }

    @Test
    fun theComposedCoreHasOpenedTheChatsSessionAndSaysWhatRuns() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)

        assertEquals(listOf(paths), runtime.connected)
        assertEquals("android", core.calls.first { it.first == "session.create" }.second.getValue("source").jsonPrimitive.content)
        assertEquals(RuntimeSessionId("s1"), ready.chat.sessionId)
        assertEquals("Python 3.14.7 · SQLite 3.53.4 with FTS5", ready.about)
    }

    @Test
    fun theWarmIsTheCompositionsLastAskOnceTheChatIsOpen() = runTest(UnconfinedTestDispatcher()) {
        composeCore(runtime, paths, deps, backgroundScope)

        assertEquals(listOf("runtime_facts", "cron_warm_delivery_targets"), bridgeCalls)  // once, last
        // The readiness check is the one call after the warm (D8): launched, so Ready never waits on it.
        assertEquals("no core call the composition makes comes after the warm", startUpCalls(), coreCallsAtWarm)
        assertTrue(coreCallsAtWarm.toString(),
                   coreCallsAtWarm!!.containsAll(listOf("config.get", "session.most_recent", "session.create", "setup.status")))
    }

    @Test
    fun theWarmWaitsForTheResumedConversation() = runTest(UnconfinedTestDispatcher()) {
        recent = "db-last"

        composeCore(runtime, paths, deps, backgroundScope)

        assertTrue(coreCallsAtWarm.toString(), coreCallsAtWarm.orEmpty().contains("session.resume"))
        assertEquals(startUpCalls(), coreCallsAtWarm)
    }

    /** The core calls the composition made, but for the readiness check that follows the warm (D8). */
    private fun startUpCalls() = core.calls.map { it.first }.filterNot { it == "setup.runtime_check" }

    private fun checks() = core.calls.count { it.first == "setup.runtime_check" }

    @Test
    fun readinessIsCheckedOnceTheStartUpCallsAndTheWarmAreDone() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)

        assertFalse(coreCallsAtWarm.toString(), coreCallsAtWarm!!.contains("setup.runtime_check"))
        assertEquals(1, checks())
        assertEquals("setup.runtime_check", core.calls.last().first)
        assertEquals(Readiness.Ready("deepseek", "deepseek-v4-pro"), ready.setup.readiness.value)
    }

    @Test
    fun aProviderFailureInTheComposedChatChecksAgain() = runTest(UnconfinedTestDispatcher()) {
        composeCore(runtime, paths, deps, backgroundScope)

        core.emit("message.start", "s1")
        core.emit("message.complete", "s1", """{"text":"","status":"error","error":"Invalid API key",
            "error_surface":{"layer":"auth","code":"auth","retryable":false}}""")

        assertEquals(2, checks())
    }

    @Test
    fun aDrainedItemTheComposedCoreQueuedReachesThePlatformsLog() = runTest(UnconfinedTestDispatcher()) {
        val logged = mutableListOf<String>()
        val ready = composeCore(runtime, paths, deps.copy(log = { logged += it }), backgroundScope)

        ready.chat.send("hi")
        core.emit("message.start", "s1")
        ready.chat.queue("a")
        submitReply = """{"status":"queued"}"""
        core.emit("message.complete", "s1", """{"text":"Done.","status":"complete"}""")

        assertEquals(listOf("prompt.submit queued:true in s1 answered queued: the core runs it after its own turn, no user_row_id"),
                     logged)
    }

    @Test
    fun closingTheComposedSetupChecksAgain() = runTest(UnconfinedTestDispatcher()) {
        configured = false  // setup opens first

        val ready = composeCore(runtime, paths, deps, backgroundScope)
        assertEquals(1, checks())
        ready.setup.close()

        assertEquals(2, checks())
    }

    @Test
    fun aCoreWithoutAProviderShowsSetupFirst() = runTest(UnconfinedTestDispatcher()) {
        configured = false

        val ready = composeCore(runtime, paths, deps, backgroundScope)

        assertNotNull(ready.setup.state.value)
    }

    @Test
    fun aConfiguredCoreGoesStraightToTheChat() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)

        assertNull(ready.setup.state.value)
    }

    @Test
    fun theComposedChatReopensTheCoresMostRecentConversation() = runTest(UnconfinedTestDispatcher()) {
        recent = "db-last"

        val ready = composeCore(runtime, paths, deps, backgroundScope)

        assertEquals(RuntimeSessionId("s-last"), ready.chat.sessionId)
        assertFalse(core.calls.any { it.first == "session.create" })
        assertEquals("db-last", core.calls.single { it.first == "session.resume" }.second.getValue("session_id").jsonPrimitive.content)
    }

    @Test
    fun aLastConversationTheAppCannotReadStillComposesAChat() = runTest(UnconfinedTestDispatcher()) {
        recent = "db-last"
        resumeReply = { """{"session_id":"s-last","message_count":1,"messages":[{"kind":"user"}],"info":{}}""" }  // no role: undecodable

        val ready = composeCore(runtime, paths, deps, backgroundScope)

        assertEquals(RuntimeSessionId("s1"), ready.chat.sessionId)
        assertTrue(core.calls.any { it.first == "session.create" })
    }

    @Test
    fun theContextPanelReadsTheChatsSession() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)

        ready.pages.context.open()

        assertEquals("s1", core.calls.single { it.first == "session.context_breakdown" }.second.getValue("session_id").jsonPrimitive.content)
        // A fresh session has no meter yet: the core reported no window.
        assertEquals(ContextUi(null, Json.decodeFromString(SessionContextBreakdownResult.serializer(), EMPTY_BREAKDOWN)),
                     ready.pages.context.state.value)
    }

    @Test
    fun theChatExportsItsOwnSessionThroughTheAppsSessionExport() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)

        ready.pages.export.export(null)

        assertEquals("""{"session_id":"db1"}""", bridgeArgs["session_export"])  // the chat's stored key, never its runtime id
        assertEquals(ExportFile("/data/app-files/cache/exports/session-db1.json", "session-db1.json", 42L),
                     ready.pages.export.state.value.file)
    }

    @Test
    fun theJobsPageIsClosedUntilOpenedAndSeesTheGatewayItIsGiven() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps.copy(seen = { GatewaySeen(GatewayStatus.FAILED, "x") }), backgroundScope)

        assertNull(ready.pages.jobs.state.value)
        assertFalse(core.calls.any { it.first == "cron.manage" })  // composing lists nothing

        ready.pages.jobs.open()

        // The bridge's "{}" is no ticker record: the warning is the gateway the composition was given.
        assertEquals(JobsWarning.GatewayOff(GatewayStatus.FAILED), ready.pages.jobs.state.value?.warning)
    }

    @Test
    fun aJobsRunOpensInTheComposedChat() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)

        ready.pages.jobs.open()
        ready.pages.jobs.showRuns(JobId("j1"))
        ready.pages.jobs.openRun(StoredSessionId("cron_j1_20261113_093000"))

        assertEquals("cron_j1_20261113_093000",
                     core.calls.last { it.first == "session.resume" }.second.getValue("session_id").jsonPrimitive.content)
        assertEquals(RuntimeSessionId("s-last"), ready.chat.sessionId)
        assertNull(ready.pages.jobs.state.value)
    }

    @Test
    fun aRunTheComposedChatCannotOpenKeepsTheJobsPage() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)
        resumeReply = { """{"session_id":"s-last","message_count":1,"messages":[{"kind":"user"}],"info":{}}""" }  // no role: undecodable

        ready.pages.jobs.open()
        ready.pages.jobs.showRuns(JobId("j1"))
        ready.pages.jobs.openRun(StoredSessionId("cron_j1_20261113_093000"))

        assertNotNull(ready.pages.jobs.state.value)
        assertNotNull(ready.pages.jobs.state.value!!.runs!!.error)
        assertEquals(RuntimeSessionId("s1"), ready.chat.sessionId)
    }

    @Test
    fun theSettingsPageIsClosedUntilOpenedAndReadsThroughTheComposedBridge() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)

        assertNull(ready.pages.settings.state.value)
        assertFalse("composing reads no settings", "settings_read" in bridgeCalls)

        ready.pages.settings.open()

        assertTrue(bridgeCalls.toString(), "settings_read" in bridgeCalls)
        assertEquals(300, ready.pages.settings.state.value?.config?.approvalTimeoutS)
    }

    @Test
    fun aboutComesFromTheAppsBuildTheManifestAndTheStartUpFacts() = runTest(UnconfinedTestDispatcher()) {
        var reads = 0
        val ready = composeCore(runtime, paths, deps.copy(packageInfo = AboutTest.APP, payloadJson = { reads++; AboutTest.MANIFEST }),
                                backgroundScope)
        assertEquals("composing reads no manifest", 0, reads)
        val settings = ready.pages.settings

        settings.open()

        assertEquals(AboutTest.ABOUT, settings.state.value?.about)
        assertEquals(1, reads)
        assertEquals("the start-up facts serve every open (D4)", 1, bridgeCalls.count { it == "runtime_facts" })

        settings.close()
        settings.open()

        assertEquals(2, reads)
        assertEquals(1, bridgeCalls.count { it == "runtime_facts" })
    }

    @Test
    fun theSkillsPageIsClosedUntilOpenedAndReadsThroughTheComposedBridge() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)

        assertNull(ready.pages.skills.state.value)
        assertFalse("composing reads no skills", "skills_list" in bridgeCalls)

        ready.pages.skills.open()

        assertTrue(bridgeCalls.toString(), "skills_list" in bridgeCalls)
        assertEquals(listOf(SkillRow("arxiv", "Search arXiv.", "research", true)), ready.pages.skills.state.value?.rows)
    }

    @Test
    fun theToolsetsPageSwitchesWithoutTheOpenChatsSession() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)
        assertEquals(RuntimeSessionId("s1"), ready.chat.sessionId)  // a chat is open
        val toolsets = ready.pages.toolsets

        toolsets.open()
        val sessionCalls = core.calls.count { it.first.startsWith("session.") }
        toolsets.set(listOf("browser"), false)

        val sent = core.calls.filter { it.first == "toolsets.list" || it.first == "tools.configure" }.map { it.second }
        assertEquals(listOf("toolsets.list", "tools.configure"), core.calls.map { it.first }.filter { it.startsWith("tool") })
        assertTrue(sent.toString(), sent.none { "session_id" in it })
        assertEquals("the switch touches no session", sessionCalls, core.calls.count { it.first.startsWith("session.") })
        assertEquals(false, toolsets.state.value?.rows?.single { it.name == "browser" }?.enabled)
    }

    @Test
    fun theSettingsPageListsAndRemovesProvidersThroughTheComposedSetup() = runTest(UnconfinedTestDispatcher()) {
        options = """{"providers":[{"slug":"deepseek","name":"DeepSeek","models":["m"],"authenticated":true}]}"""
        val ready = composeCore(runtime, paths, deps, backgroundScope)
        val settings = ready.pages.settings

        settings.open()
        assertEquals(listOf(ConnectedProvider("deepseek", "DeepSeek", removable = true, current = false)), settings.state.value?.connected)

        settings.askRemoveProvider("deepseek")
        settings.confirmRemoveProvider()

        val calls = core.calls.map { it.first }
        val disconnect = calls.indexOf("model.disconnect")
        assertEquals("deepseek", core.calls[disconnect].second.getValue("slug").jsonPrimitive.content)
        assertTrue(calls.toString(), "setup.runtime_check" in calls.subList(disconnect, calls.size))
    }

    @Test
    fun theBotIsJudgedAgainstTheGatewayTheCompositionIsGiven() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps.copy(seen = { GatewaySeen(GatewayStatus.RUNNING, pid = 7) }), backgroundScope)

        ready.pages.telegram.open()

        assertEquals(PlatformState.CONNECTED, ready.pages.telegram.state.value?.live?.state)

        val other = composeCore(runtime, paths, deps.copy(seen = { GatewaySeen(GatewayStatus.RUNNING, pid = 8) }), backgroundScope)

        other.pages.telegram.open()

        assertEquals("the entry is pid 7's, the gateway given is pid 8", PlatformState.PENDING_RESTART,
                     other.pages.telegram.state.value?.live?.state)
    }

    @Test
    fun aCompositionGivenNoGatewaySeesItStopped() = runTest(UnconfinedTestDispatcher()) {
        val ready = composeCore(runtime, paths, deps, backgroundScope)  // deps names no gateway

        ready.pages.jobs.open()

        // Left out, the gateway is seen stopped (today's default), never running: the page warns.
        assertEquals(JobsWarning.GatewayOff(GatewayStatus.STOPPED), ready.pages.jobs.state.value?.warning)
    }

    @Test
    fun theChatJudgesAJobTheAgentSchedulesByTheGatewayItIsGiven() = runTest(UnconfinedTestDispatcher()) {
        var now = GatewaySeen(GatewayStatus.RUNNING)
        val ready = composeCore(runtime, paths, deps.copy(seen = { now }), backgroundScope)

        scheduled("t1")

        assertTrue(ready.chat.state.value.entries.toString(), ready.chat.state.value.entries.none { it is Entry.Notice })

        now = GatewaySeen(GatewayStatus.STOPPED)
        scheduled("t2")

        // The two tools take 0 and 1, the notice the next id.
        assertEquals(listOf(Entry.Notice(ChatSession.JOBS_NEED_GATEWAY, NoticeShortcut.GATEWAY, 2)),
                     ready.chat.state.value.entries.filterIsInstance<Entry.Notice>())
    }

    /** The agent schedules a job in the composed chat's session `s1` (the job tool's create, answered `success`). */
    private suspend fun scheduled(id: String) {
        core.emit("tool.start", "s1", """{"tool_id":"$id","name":"cronjob_manage"}""")
        core.emit("tool.complete", "s1", """{"tool_id":"$id","name":"cronjob_manage","summary":"Scheduled the job",
            "args":{"action":"create","schedule":"every 2m","prompt":"Say tick."},"result":{"success":true,"job_id":"ab12"}}""")
    }

    private companion object {
        const val EMPTY_BREAKDOWN = """{"categories":[],"context_max":0,"context_percent":0,"context_used":0,"estimated_total":0,
            "context_estimated":false,"context_source":"provider_usage","model":"m"}"""
    }
}
