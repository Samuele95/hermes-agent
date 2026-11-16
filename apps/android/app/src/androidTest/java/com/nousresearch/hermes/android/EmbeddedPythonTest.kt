package com.nousresearch.hermes.android

import android.app.ActivityManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.gateway.GatewayRecord
import com.nousresearch.hermes.android.gateway.gatewayRecordFile
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.CronRunsParams
import com.nousresearch.hermes.bridge.contract.CronSchedulerReply
import com.nousresearch.hermes.bridge.contract.CronTriggerParams
import com.nousresearch.hermes.bridge.contract.CronUpdateParams
import com.nousresearch.hermes.bridge.contract.DeliveryTarget
import com.nousresearch.hermes.bridge.contract.SettingsReadReply
import com.nousresearch.hermes.bridge.contract.SettingsWriteParams
import com.nousresearch.hermes.bridge.contract.SkillEntry
import com.nousresearch.hermes.bridge.contract.SkillsEnableParams
import com.nousresearch.hermes.bridge.contract.TelegramEnableParams
import com.nousresearch.hermes.bridge.contract.TelegramStatusReply
import com.nousresearch.hermes.bridge.protocol.ClientCapabilitiesParams
import com.nousresearch.hermes.bridge.protocol.CommandDispatchParams
import com.nousresearch.hermes.bridge.protocol.CommandsCatalogParams
import com.nousresearch.hermes.bridge.protocol.CompleteSlashParams
import com.nousresearch.hermes.bridge.protocol.CronAction
import com.nousresearch.hermes.bridge.protocol.CronManageParams
import com.nousresearch.hermes.bridge.protocol.DispatchType
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.LearningEditParams
import com.nousresearch.hermes.bridge.protocol.LearningNodeParams
import com.nousresearch.hermes.bridge.protocol.MessageReactParams
import com.nousresearch.hermes.bridge.protocol.Method
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PingParams
import com.nousresearch.hermes.bridge.protocol.PingResult
import com.nousresearch.hermes.bridge.protocol.ReactionAuthor
import com.nousresearch.hermes.bridge.protocol.SessionCloseParams
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownParams
import com.nousresearch.hermes.bridge.protocol.SessionCorrectionParams
import com.nousresearch.hermes.bridge.protocol.SessionCreateParams
import com.nousresearch.hermes.bridge.protocol.SessionDeleteParams
import com.nousresearch.hermes.bridge.protocol.SessionStatusParams
import com.nousresearch.hermes.bridge.protocol.SessionTitleParams
import com.nousresearch.hermes.bridge.protocol.SetupRuntimeCheckParams
import com.nousresearch.hermes.bridge.protocol.SkillsReloadParams
import com.nousresearch.hermes.bridge.protocol.ToolsAction
import com.nousresearch.hermes.bridge.protocol.ToolsConfigureParams
import com.nousresearch.hermes.bridge.protocol._SessionScoped
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The core the app embeds answers the app's gateway over the in-process transport. */
@RunWith(AndroidJUnit4::class)
class EmbeddedPythonTest {
    companion object {
        // One interpreter per process, owned by the application: every test talks to its core.
        private val core: CoreGateway by lazy {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HermesApplication
            val state = runBlocking { withTimeout(120_000) { app.core.first { it !is CoreState.Starting } } }
            (state as? CoreState.Ready ?: error("the core did not start: $state")).gateway
        }
    }

    @Test
    fun theCoreAnswersATypedRequest() = runBlocking {
        assertEquals(PingResult(pong = true), core.call(Methods.PING, PingParams))
    }

    @Test
    fun theAppIsAClientThatAnswersInTurnPrompts() = runBlocking {
        val asked = core.call(Methods.CLIENT_CAPABILITIES, ClientCapabilitiesParams(serverRequests = true)).serverRequests

        assertTrue("$asked", asked.containsAll(listOf("approval", "clarify", "secret")))
    }

    @Test
    fun framesCrossTheBridgeAsUtf8BothWays() {
        val unknown = Method("no.such.😀", PingParams.serializer(), PingResult.serializer())

        val error = assertThrows(CoreError::class.java) { runBlocking { core.call(unknown, PingParams) } }

        assertEquals(-32601, error.code)
        assertTrue(error.message, error.message!!.contains("no.such.😀"))
    }

    @Test
    fun theInterpreterHasFts5AndOffersNoExecutable() {
        core
        val facts = JSONObject(EmbeddedPython.call("runtime_facts", ""))

        assertEquals("3.14.7", facts.getString("python"))
        assertTrue("sqlite ${facts.getString("sqlite")} lacks FTS5", facts.getBoolean("fts5"))
        assertEquals("", facts.getString("executable"))
        assertTrue(facts.getString("core_release"),
                   Regex("""\d{4}\.\d{1,2}\.\d{1,2}""").matches(facts.getString("core_release")))
    }

    @Test
    fun onlyTheDeclaredBridgeCallsReachThePythonHalf() {
        core
        // `stop` is a function of hermes_android, but no bridge call: calling it would close the app's core.
        val refused = assertThrows(RuntimeException::class.java) { EmbeddedPython.call("stop", "") }

        assertTrue(refused.message, refused.message!!.contains("not a bridge call"))
        assertEquals(PingResult(pong = true), runBlocking { core.call(Methods.PING, PingParams) })
    }

    @Test
    fun aSessionIsTitledClosedAndDeletedThroughTheGeneratedCalls() = runBlocking {
        // session.create pre-warms the session's agent; its outcome (session.info, or error without a provider)
        // goes to python.stdout once the session is closed, so the session is closed only after it settled.
        val settled = Channel<String>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { if (it.type == "session.info" || it.type == "error") it.sessionId?.let(settled::trySend) }
        }
        // A second session beside the chat's own; "android" = ChatSession.SOURCE (Dv16).
        val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = "android"))
        val stored = created.storedSessionId
        try {
            withTimeout(120_000) { while (settled.receive() != created.sessionId) Unit }
            val title = "b1 probe ${System.nanoTime()}"

            val titled = core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = created.sessionId, title = title))

            assertEquals(title, titled.title)
            assertEquals(false, titled.pending)
            // A set answers title + pending; the read also names the session's key: the stored id session.delete takes.
            val read = core.call(Methods.SESSION_TITLE, SessionTitleParams(sessionId = created.sessionId))
            assertEquals(title, read.title)
            assertEquals(stored, read.sessionKey)

            val breakdown = core.call(Methods.SESSION_CONTEXT_BREAKDOWN, SessionContextBreakdownParams(sessionId = created.sessionId))
            assertTrue("${breakdown.contextUsed}", breakdown.contextUsed >= 0)

            val live = assertThrows(CoreError::class.java) {
                runBlocking { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
            }
            assertEquals(live.message, 4023, live.code)

            assertTrue(core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)).closed)
            assertEquals(stored, core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)).deleted)
        } finally {
            watcher.cancel()
            // A failed run leaves no session or row behind on the shared phone.
            runCatching { core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)) }
            runCatching { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
        }
    }

    @Test
    fun theCorrectionCheckAndStatusCallsAnswerOnThePhone() = runBlocking {
        // Blank words are refused before any session lookup: no session and no model are involved.
        val blank = assertThrows(CoreError::class.java) {
            runBlocking { core.call(Methods.SESSION_STEER, SessionCorrectionParams(sessionId = "", text = "   ")) }
        }
        assertEquals(blank.message, 4002, blank.code)

        // An unknown profile answers before any probe, so no resolver runs and nothing leaves the phone (D3).
        val profile = "m1-no-such-profile-${System.nanoTime()}"
        val check = withTimeout(10_000) { core.call(Methods.SETUP_RUNTIME_CHECK, SetupRuntimeCheckParams(profile = profile)) }
        assertEquals(check.error, false, check.ok)
        assertEquals(profile, check.profile)
        assertTrue(check.error, check.error!!.contains("does not exist"))

        // The session is closed only after its pre-warm settled (aSessionIsTitledClosedAndDeletedThroughTheGeneratedCalls).
        val settled = Channel<String>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { if (it.type == "session.info" || it.type == "error") it.sessionId?.let(settled::trySend) }
        }
        val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = "android"))
        val stored = created.storedSessionId
        try {
            withTimeout(120_000) { while (settled.receive() != created.sessionId) Unit }

            val lines = core.call(Methods.SESSION_STATUS, SessionStatusParams(sessionId = created.sessionId)).output.lines()

            assertEquals("$lines", "Hermes TUI Status", lines.first())
            assertTrue("$lines", "Session ID: $stored" in lines)
            assertTrue("$lines", "Agent Running: No" in lines)

            assertTrue(core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)).closed)
            // Without a turn or a title the core may not have written the row yet: then the delete answers 4007.
            val delete = runCatching { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
            when (val refused = delete.exceptionOrNull()) {
                null -> assertEquals(stored, delete.getOrThrow().deleted)
                is CoreError -> assertEquals(refused.message, 4007, refused.code)
                else -> throw refused
            }
        } finally {
            watcher.cancel()
            // A failed run leaves no session or row behind on the shared phone.
            runCatching { core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)) }
            runCatching { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
        }
    }

    @Test
    fun aJobIsAddedPausedResumedAndRemovedByItsId() = runBlocking {
        // Frames nobody collects are not replayed: the watcher runs before the first write.
        val signals = Channel<CoreEvent>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { if (it.decode(Events.CRON_CHANGED) != null) signals.trySend(it) }
        }
        // A weekly interval: its first run is a week away, so the debug app's :gateway ticker never fires it here.
        val added = core.call(
            Methods.CRON_MANAGE,
            CronManageParams(
                action = CronAction.ADD, name = "j1 probe ${System.nanoTime()}", schedule = "every 10080m",
                prompt = "Say hello.", deliver = "local",
            ),
        )
        val id = checkNotNull(added.jobId) { "add answered ${added.error}" }
        try {
            assertEquals(added.error, true, added.success)
            assertEquals("scheduled", added.job!!.state)
            // The signal reaches the chat process's gateway; a tick of the debug :gateway would produce the same fact.
            assertEquals("", withTimeout(15_000) { signals.receive() }.sessionId)

            // The reference the app sends is the core's job_id, in `name`.
            val paused = core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.PAUSE, name = id)).job!!
            assertEquals("paused", paused.state)
            assertEquals(false, paused.enabled)

            val listed = core.call(Methods.CRON_MANAGE, CronManageParams()).jobs.orEmpty()
            assertTrue("a paused job is listed without include_disabled", listed.none { it.jobId == id })
            val all = core.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true))).jobs.orEmpty()
            assertEquals("paused", all.single { it.jobId == id }.state)

            val resumed = core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.RESUME, name = id))
            assertEquals("scheduled", resumed.job!!.state)

            val removed = core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = id))
            assertEquals(id, removed.removedJob!!.id)
            val after = core.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true))).jobs.orEmpty()
            assertTrue("$id is still listed after its removal", after.none { it.jobId == id })
        } finally {
            watcher.cancel()
            // A failed run leaves no job behind on the shared phone.
            runCatching { core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = id)) }
        }
    }

    @Test
    fun theCronBridgeCallsAnswerOnThePhone() = runBlocking {
        core // the interpreter starts with the core
        // A weekly interval, as J1's probe: the debug app's :gateway ticker never finds it due here.
        val added = core.call(
            Methods.CRON_MANAGE,
            CronManageParams(
                action = CronAction.ADD, name = "j2 probe ${System.nanoTime()}", schedule = "every 10080m",
                prompt = "Say hello.", deliver = "local",
            ),
        )
        val id = checkNotNull(added.jobId) { "add answered ${added.error}" }
        val took = linkedMapOf<String, Long>() // each call holds hermes-python (none is blocking)
        try {
            var edited: String? = null
            took["cron_update"] = measureTimeMillis {
                edited = EmbeddedBridge.ask(
                    BridgeCalls.CRON_UPDATE, CronUpdateParams(id, null, "every 20160m", "Say hello again.", null, null),
                ).jobId
            }
            assertEquals(id, edited)
            val row = core.call(Methods.CRON_MANAGE, CronManageParams(includeDisabled = JsonPrimitive(true)))
                .jobs.orEmpty().single { it.jobId == id }
            assertEquals("every 20160m", row.schedule)
            assertEquals("Say hello again.", row.promptPreview)
            assertEquals("scheduled", row.state)
            assertEquals(added.job!!.name, row.name)

            // The job never ran; the call goes through the chat process's shared state.db handle.
            var runs: Int? = null
            took["cron_runs"] = measureTimeMillis { runs = EmbeddedBridge.ask(BridgeCalls.CRON_RUNS, CronRunsParams(id, 20)).runs.size }
            assertEquals(0, runs)

            var ticker: CronSchedulerReply? = null
            took["cron_scheduler"] = measureTimeMillis { ticker = EmbeddedBridge.ask(BridgeCalls.CRON_SCHEDULER) }
            val facts = ticker!!.let { "heartbeat ${it.heartbeatAgeS} s, success ${it.successAgeS} s, last error ${it.lastError != null}" }
            Log.i("J2Probe", "cron_scheduler: $facts")
            assertEquals(facts, 200L, ticker!!.staleAfterS)
            assertTrue(facts, listOf(ticker!!.heartbeatAgeS, ticker!!.successAgeS).all { it == null || it >= 0 })

            var targets: List<DeliveryTarget> = emptyList()
            took["cron_delivery_targets"] = measureTimeMillis { targets = EmbeddedBridge.ask(BridgeCalls.CRON_DELIVERY_TARGETS).targets }
            val listed = targets.joinToString { "${it.id} home=${it.homeSet}" }
            Log.i("J2Probe", "cron_delivery_targets: [$listed]")
            assertTrue(listed, targets.none { it.id.startsWith("bot-chat") })
            assertTrue(listed, targets.all { it.id.isNotBlank() && it.name.isNotBlank() })

            val removed = core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = id))
            assertEquals(id, removed.removedJob!!.id)
            // Only after the removal, so nothing becomes due on the shared phone (a fire would run an agent turn).
            var gone: CoreError? = null
            took["cron_trigger"] = measureTimeMillis {
                gone = assertThrows(CoreError::class.java) {
                    runBlocking { EmbeddedBridge.ask(BridgeCalls.CRON_TRIGGER, CronTriggerParams(id)) }
                }
            }
            assertEquals("That job no longer exists.", gone!!.message)
        } finally {
            Log.i("J2Probe", "took ms: $took")
            // A failed run leaves no job behind on the shared phone.
            runCatching { core.call(Methods.CRON_MANAGE, CronManageParams(action = CronAction.REMOVE, name = id)) }
        }
    }

    @Test
    fun theBotSwitchAndItsLiveEntryAnswerOnThePhone() = runBlocking {
        core // the interpreter starts with the core
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val took = linkedMapOf<String, Long>() // each call holds hermes-python (neither is blocking)
        val config = File(app.filesDir, ".hermes/config.yaml") // the path TelegramSetupDeviceTest reads
        val original = config.takeIf { it.exists() }?.readText()
        var before: TelegramStatusReply? = null
        took["telegram_status"] = measureTimeMillis { before = EmbeddedBridge.ask(BridgeCalls.TELEGRAM_STATUS) }
        val was = before!!
        // §8 risk (b), observed only: the entry's writer against the :gateway process and the app's own record.
        val gatewayPid = app.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.processName == "${app.packageName}:gateway" }?.pid
        val record = runCatching { GatewayRecord.parse(gatewayRecordFile(app).readText()) }.getOrNull()
        Log.i("J3Probe", "telegram_status: enabled=${was.enabled} token=${was.token} " +
            "live=${was.live?.let { "state=${it.state} writerPid=${it.writerPid} updatedAt=${it.updatedAt}" }}")
        Log.i("J3Probe", ":gateway pid=$gatewayPid record=${record?.let { "${it.state} pid=${it.pid}" }}")
        assertTrue("writer_pid ${was.live?.writerPid}", was.live?.writerPid.let { it == null || it > 0 })
        try {
            var switched: Boolean? = null
            took["telegram_enable"] = measureTimeMillis {
                switched = EmbeddedBridge.ask(BridgeCalls.TELEGRAM_ENABLE, TelegramEnableParams(!was.enabled)).enabled
            }
            assertEquals(!was.enabled, switched)
            var after: TelegramStatusReply? = null
            took["telegram_status again"] = measureTimeMillis { after = EmbeddedBridge.ask(BridgeCalls.TELEGRAM_STATUS) }
            assertEquals(!was.enabled, after!!.enabled)
            assertEquals(was.token, after!!.token)
            assertEquals(was.allowedUser, after!!.allowedUser)
            if (was.enabled) {
                assertTrue(config.readText().contains("enabled: false"))
            }
        } finally {
            Log.i("J3Probe", "took ms: $took")
            Log.i("J3Probe", "config.yaml had the key before: ${original?.contains(Regex("(?m)^platforms:\\n  telegram:\\n    enabled:"))}")
            runCatching { EmbeddedBridge.ask(BridgeCalls.TELEGRAM_ENABLE, TelegramEnableParams(was.enabled)) }
            // The shared phone gets config.yaml back as it was: with no token, an explicit `enabled: true` is not
            // the same as no key, since the gateway then starts a Telegram adapter that fails and stops it all.
            if (original != null) config.writeText(original) else config.delete()
            assertEquals(was.enabled, EmbeddedBridge.ask(BridgeCalls.TELEGRAM_STATUS).enabled)
        }
    }

    @Test
    fun theSettingsCallsAnswerOnThePhone() = runBlocking {
        core // the interpreter starts with the core
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val took = linkedMapOf<String, Long>() // each call holds hermes-python (neither is blocking)
        val config = File(app.filesDir, ".hermes/config.yaml")
        val original = config.takeIf { it.exists() }?.readBytes()
        var before: SettingsReadReply? = null
        took["settings_read"] = measureTimeMillis { before = EmbeddedBridge.ask(BridgeCalls.SETTINGS_READ) }
        val was = before!!
        // Sizes only: the allowlist is the phone owner's.
        Log.i("M8Probe", "allowlist size=${was.commandAllowlist.size} personalities=${was.personalities.size}")
        assertTrue("timeout ${was.approvalsTimeoutS}", was.approvalsTimeoutS >= 1)
        assertTrue(was.memoryCharLimit >= 0 && was.userCharLimit >= 0)
        val names = was.personalities.map { it.name }
        assertTrue(names.toString(), "helpful" in names)
        assertEquals(names.size, names.toSet().size)
        assertTrue(was.personalities.all { it.description.isNotBlank() })
        try {
            // Never a removal or a memory switch here: the allowlist is the owner's, and a turn started meanwhile
            // would build with a changed memory setting. The reconcile is proven on the host.
            var longer: Boolean? = null
            took["settings_write timeout"] = measureTimeMillis {
                longer = EmbeddedBridge.ask(BridgeCalls.SETTINGS_WRITE,
                    SettingsWriteParams(was.approvalsTimeoutS + 1, null, null, null, null, emptyList())).applied
            }
            assertEquals(true, longer)
            var reread: SettingsReadReply? = null
            took["settings_read again"] = measureTimeMillis { reread = EmbeddedBridge.ask(BridgeCalls.SETTINGS_READ) }
            assertEquals(was.copy(approvalsTimeoutS = was.approvalsTimeoutS + 1), reread)

            val bytes = config.readBytes()
            var nothing: Boolean? = null
            took["settings_write empty"] = measureTimeMillis {
                nothing = EmbeddedBridge.ask(BridgeCalls.SETTINGS_WRITE,
                    SettingsWriteParams(null, null, null, null, null, emptyList())).applied
            }
            assertEquals(false, nothing)
            assertTrue("an empty edit changed config.yaml", bytes.contentEquals(config.readBytes()))

            var refused: CoreError? = null
            took["settings_write refused"] = measureTimeMillis {
                refused = assertThrows(CoreError::class.java) {
                    runBlocking {
                        EmbeddedBridge.ask(BridgeCalls.SETTINGS_WRITE,
                            SettingsWriteParams(0, null, null, null, null, emptyList()))
                    }
                }
            }
            assertEquals("The approval timeout must be at least 1 second.", refused!!.message)
        } finally {
            Log.i("M8Probe", "took ms: $took")
            // The shared phone gets config.yaml back as it was.
            if (original != null) config.writeBytes(original) else config.delete()
            assertEquals(was, EmbeddedBridge.ask(BridgeCalls.SETTINGS_READ))
        }
    }

    @Test
    fun theSkillsCallsAnswerOnThePhone() = runBlocking {
        core // the interpreter starts with the core
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val took = linkedMapOf<String, Long>() // each call holds hermes-python (neither is blocking)
        val config = File(app.filesDir, ".hermes/config.yaml")
        val original = config.takeIf { it.exists() }?.readBytes()
        var listed: List<SkillEntry>? = null
        took["skills_list cold"] = measureTimeMillis { listed = EmbeddedBridge.ask(BridgeCalls.SKILLS_LIST).skills }
        val was = listed!!
        Log.i("M11Probe", "skills=${was.size} categories=${was.map { it.category }.toSet().size}")
        assertTrue("only ${was.size} skills", was.size >= 50) // BundledSkillsDeviceTest's floor
        assertEquals(was.size, was.map { it.name }.toSet().size)
        assertTrue("hermes-agent is not listed and on", was.any { it.name == "hermes-agent" && it.enabled })
        try {
            // One skill only, never a category: the phone is shared, and finally gives config.yaml back.
            val victim = was.first { it.enabled && it.name != "hermes-agent" }
            var off: List<SkillEntry>? = null
            took["skills_enable off"] = measureTimeMillis {
                off = EmbeddedBridge.ask(BridgeCalls.SKILLS_ENABLE, SkillsEnableParams(listOf(victim.name), false)).skills
            }
            assertEquals(was.map { if (it.name == victim.name) it.copy(enabled = false) else it }, off)
            var on: List<SkillEntry>? = null
            took["skills_enable on"] = measureTimeMillis {
                on = EmbeddedBridge.ask(BridgeCalls.SKILLS_ENABLE, SkillsEnableParams(listOf(victim.name), true)).skills
            }
            assertEquals(was, on)

            val bytes = config.readBytes()
            var refused: CoreError? = null
            took["skills_enable refused"] = measureTimeMillis {
                refused = assertThrows(CoreError::class.java) {
                    runBlocking { EmbeddedBridge.ask(BridgeCalls.SKILLS_ENABLE, SkillsEnableParams(listOf("hermes-agent"), false)) }
                }
            }
            assertEquals("Skill 'hermes-agent' is essential to Hermes (the agent's own operating manual referenced by " +
                         "the system prompt) and cannot be disabled.", refused!!.message)
            assertTrue("a refused switch changed config.yaml", bytes.contentEquals(config.readBytes()))
        } finally {
            // The shared phone gets config.yaml back as it was.
            if (original != null) config.writeBytes(original) else config.delete()
            took["skills_list warm"] = measureTimeMillis { assertEquals(was, EmbeddedBridge.ask(BridgeCalls.SKILLS_LIST).skills) }
            Log.i("M11Probe", "took ms: $took")
        }
    }

    @Test
    fun theSkillToolsetReactionAndSlashCallsAnswerOnThePhone() = runBlocking {
        core // the interpreter starts with the core, outside the calls' timeouts
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val tag = System.nanoTime()
        val nope = "p1-no-such-skill-$tag"

        // Skills: no session, nothing written. A refused write is an answer, never an error.
        val github = withTimeout(60_000) { core.call(Methods.LEARNING_DETAIL, LearningNodeParams(id = "github")) }
        assertTrue(github.message, github.ok)
        assertEquals("skill", github.kind)
        assertTrue(github.content, github.content!!.startsWith("---"))
        val missing = withTimeout(60_000) { core.call(Methods.LEARNING_DETAIL, LearningNodeParams(id = nope)) }
        assertEquals(missing.message, false, missing.ok)
        assertEquals("skill '$nope' not found", missing.message)
        // The frontmatter is checked before any lookup or write.
        val malformed = withTimeout(60_000) { core.call(Methods.LEARNING_EDIT, LearningEditParams(id = nope, content = "not a skill")) }
        assertEquals(malformed.message, false, malformed.ok)
        assertEquals("SKILL.md must start with YAML frontmatter (---). See existing skills for format.", malformed.message)
        val unarchived = withTimeout(60_000) { core.call(Methods.LEARNING_DELETE, LearningNodeParams(id = nope)) }
        assertEquals(unarchived.message, false, unarchived.ok)
        assertEquals("skill '$nope' not found", unarchived.message)
        val reload = withTimeout(60_000) { core.call(Methods.SKILLS_RELOAD, SkillsReloadParams()) }
        val printed = reload.output.lines()
        Log.i("P1Probe", "reload first=${printed.first()} last=${printed.last()} total=${reload.result.total}")
        assertTrue("only ${reload.result.total} skills", reload.result.total >= 50)
        assertEquals(reload.output, "Reloading skills...", printed.first())
        assertEquals(reload.output, "${reload.result.total} skill(s) available", printed.last())

        // Toolsets: the list names no tools (D3), and an empty switch is refused before config.yaml is read.
        val config = File(app.filesDir, ".hermes/config.yaml")
        val original = config.takeIf { it.exists() }?.readBytes()
        try {
            val rows = withTimeout(60_000) { core.call(Methods.TOOLSETS_LIST, _SessionScoped()) }.toolsets
            Log.i("P1Probe", "toolsets=${rows.size} first=${rows.firstOrNull()}")
            assertTrue("no toolsets", rows.isNotEmpty())
            assertTrue("$rows", rows.all { it.tools == null })
            val empty = assertThrows(CoreError::class.java) {
                runBlocking {
                    withTimeout(60_000) {
                        core.call(Methods.TOOLS_CONFIGURE, ToolsConfigureParams(action = ToolsAction.ENABLE, names = emptyList()))
                    }
                }
            }
            assertEquals(4018 to "names required", empty.code to empty.message)
            assertTrue("a refused switch changed config.yaml", original.contentEquals(config.takeIf { it.exists() }?.readBytes()))
        } finally {
            // The shared phone gets config.yaml back as it was.
            val now = config.takeIf { it.exists() }?.readBytes()
            if (!original.contentEquals(now)) {
                if (original != null) config.writeBytes(original) else config.delete()
            }
        }

        // Slash: no session. A skill is never dispatched: that bumps its use count.
        val catalog = withTimeout(60_000) { core.call(Methods.COMMANDS_CATALOG, CommandsCatalogParams()) }
        val skills = catalog.skills!!
        Log.i("P1Probe", "catalog skills=${skills.size} skill_count=${catalog.skillCount} github=${skills["/github"]}")
        assertTrue("${skills.keys}", "/github" in skills)
        assertEquals(skills.size.toLong(), catalog.skillCount)
        assertTrue("only ${catalog.skillCount} skills", catalog.skillCount >= 50)
        assertTrue("no pairs", catalog.pairs!!.isNotEmpty())
        val completed = withTimeout(60_000) { core.call(Methods.COMPLETE_SLASH, CompleteSlashParams(text = "/git")) }.items!!
        Log.i("P1Probe", "complete /git: ${completed.map { it.text to it.kind }}")
        assertTrue("$completed", completed.any { it.kind == "skill" && it.text.trim().trimStart('/').startsWith("github") })
        val queued = withTimeout(60_000) {
            core.call(Methods.COMMAND_DISPATCH, CommandDispatchParams(name = "queue", arg = "p1 probe $tag"))
        }
        assertEquals(DispatchType.SEND, queued.type)
        assertEquals("p1 probe $tag", queued.message)
        val unknown = assertThrows(CoreError::class.java) {
            runBlocking {
                withTimeout(60_000) { core.call(Methods.COMMAND_DISPATCH, CommandDispatchParams(name = "p1-no-such-command-$tag")) }
            }
        }
        assertEquals(unknown.message, 4018, unknown.code)

        // Reaction: one live session, closed only after its pre-warm settled
        // (aSessionIsTitledClosedAndDeletedThroughTheGeneratedCalls).
        val settled = Channel<String>(Channel.UNLIMITED)
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            core.events.collect { if (it.type == "session.info" || it.type == "error") it.sessionId?.let(settled::trySend) }
        }
        val created = core.call(Methods.SESSION_CREATE, SessionCreateParams(source = "android"))
        val stored = created.storedSessionId
        try {
            withTimeout(120_000) { while (settled.receive() != created.sessionId) Unit }

            val unnamed = assertThrows(CoreError::class.java) {
                runBlocking {
                    withTimeout(60_000) { core.call(Methods.MESSAGE_REACT, MessageReactParams(sessionId = created.sessionId)) }
                }
            }
            assertEquals(unnamed.message, 4023, unnamed.code)
            // The row lookup fails before anything is written.
            val stranger = assertThrows(CoreError::class.java) {
                runBlocking {
                    withTimeout(60_000) {
                        core.call(
                            Methods.MESSAGE_REACT,
                            MessageReactParams(
                                sessionId = created.sessionId, rowId = 999_999_999, emoji = "👍", author = ReactionAuthor.USER,
                            ),
                        )
                    }
                }
            }
            assertEquals(4040 to "message not found in this session", stranger.code to stranger.message)

            assertTrue(core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)).closed)
            // Without a turn or a title the core may not have written the row yet: then the delete answers 4007.
            val delete = runCatching { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
            when (val refused = delete.exceptionOrNull()) {
                null -> assertEquals(stored, delete.getOrThrow().deleted)
                is CoreError -> assertEquals(refused.message, 4007, refused.code)
                else -> throw refused
            }
        } finally {
            watcher.cancel()
            // A failed run leaves no session, row or config change behind on the shared phone.
            runCatching { core.call(Methods.SESSION_CLOSE, SessionCloseParams(sessionId = created.sessionId)) }
            runCatching { core.call(Methods.SESSION_DELETE, SessionDeleteParams(sessionId = stored)) }
            val now = config.takeIf { it.exists() }?.readBytes()
            if (!original.contentEquals(now)) {
                if (original != null) config.writeBytes(original) else config.delete()
            }
        }
    }

    @Test
    fun aNetworkProbeDoesNotHoldTheCoresFrames() =
        assertAHeldCallLetsFramesThrough("probe_endpoint") { """{"address":"http://127.0.0.1:$it/v1","api_key":null}""" }

    @Test
    fun anSshHostLookDoesNotHoldTheCoresFrames() =
        // asyncssh connects and waits for a banner that never comes (up to 15 s); the closed listener ends it with {error}.
        assertAHeldCallLetsFramesThrough("ssh_host_key") { """{"host":"127.0.0.1","port":$it}""" }

    /** Holds the bridge call [function] on a silent listener ([request] gets its port) and checks a ping still answers. */
    private fun assertAHeldCallLetsFramesThrough(function: String, request: (port: Int) -> String) {
        core
        // Never accepted: the kernel completes the connect and nothing answers (tests/android/fake_endpoint.py's
        // silent_listener), so the call waits on the bridge's network thread. IPv4 by name: Android's
        // getLoopbackAddress() is ::1, where the call's 127.0.0.1 would be refused at once.
        val silent = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        var reply: Result<String>? = null
        val held = thread { reply = runCatching { EmbeddedPython.call(function, request(silent.localPort)) } }
        try {
            Thread.sleep(1_500)

            assertEquals(PingResult(pong = true), runBlocking { withTimeout(3_000) { core.call(Methods.PING, PingParams) } })
            assertTrue("$function had already ended ($reply), so the ping proves nothing about a held call", held.isAlive)
        } finally {
            silent.close()
        }
        held.join(30_000)
        assertFalse("$function did not end after its listener closed", held.isAlive)
    }
}
