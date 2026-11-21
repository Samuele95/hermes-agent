package com.nousresearch.hermes.bridge.protocol

import com.nousresearch.hermes.bridge.contract.ToolsetLabel
import com.nousresearch.hermes.bridge.contract.ToolsetsKeysReply
import com.nousresearch.hermes.bridge.wire
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated types speak the core's wire format: snake_case names, defaults left to the core. */
class ProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun aRequestCarriesOnlyTheFieldsTheAppSets() {
        val wire = json.encodeToString(Methods.PROMPT_SUBMIT.params, PromptSubmitParams(sessionId = "s1", text = JsonPrimitive("héllo 😀")))

        assertEquals("""{"session_id":"s1","text":"héllo 😀"}""", wire)
    }

    @Test
    fun aResultDecodesFromTheWireIgnoringFieldsTheAppDoesNotModel() {
        assertEquals(PingResult(pong = true), json.decodeFromString(Methods.PING.result, """{"pong":true,"latency_ms":3}"""))
    }

    @Test
    fun paramsWithoutFieldsAreAnEmptyObject() {
        assertEquals("{}", json.encodeToString(Methods.PING.params, PingParams))
    }

    // The tests below use the app's production wire config: the core reads a present `title`, even null, as a set.

    @Test
    fun aTitleIsReadWithoutATitleAndSetWithOne() {
        assertEquals("""{"session_id":"r1"}""", wire.encodeToString(Methods.SESSION_TITLE.params, SessionTitleParams(sessionId = "r1")))
        assertEquals(
            """{"session_id":"r1","title":"Trip to Rome"}""",
            wire.encodeToString(Methods.SESSION_TITLE.params, SessionTitleParams(sessionId = "r1", title = "Trip to Rome")),
        )

        val titled = wire.decodeFromString(
            Methods.SESSION_TITLE.result,
            """{"title":"Trip to Rome","session_key":"20261103_101500_ab12cd","pending":false}""",
        )

        assertEquals("20261103_101500_ab12cd", titled.sessionKey)
        assertEquals(false, titled.pending)
    }

    @Test
    fun aDeleteNamesTheStoredSessionAndAnswersIt() {
        assertEquals(
            """{"session_id":"20261103_101500_ab12cd"}""",
            wire.encodeToString(Methods.SESSION_DELETE.params, SessionDeleteParams(sessionId = "20261103_101500_ab12cd")),
        )
        assertEquals(
            "20261103_101500_ab12cd",
            wire.decodeFromString(Methods.SESSION_DELETE.result, """{"deleted":"20261103_101500_ab12cd"}""").deleted,
        )
    }

    @Test
    fun aCloseAnswersWhetherItClosed() {
        val closed = wire.decodeFromString(Methods.SESSION_CLOSE.result, """{"closed":true,"messages":["Session ended."]}""")

        assertTrue(closed.closed)
        assertEquals(listOf("Session ended."), closed.messages)
    }

    @Test
    fun aCompressionDecodesItsSummaryUsageAndNewTranscript() {
        val compressed = wire.decodeFromString(
            Methods.SESSION_COMPRESS.result,
            """{"status":"compressed","removed":12,"before_messages":20,"after_messages":8,"before_tokens":9000,""" +
                """"after_tokens":2100,"summary":{"headline":"Compressed 12 messages","token_line":"9,000 → 2,100 tokens"},""" +
                """"usage":{"context_used":2100,"context_max":8192,"context_percent":26},""" +
                """"messages":[{"role":"user","text":"hi","row_id":4}]}""",
        )

        assertEquals("Compressed 12 messages", compressed.summary!!.headline)
        assertEquals(false, compressed.summary.noop)
        assertEquals(8192L, compressed.usage!!.contextMax)
        assertEquals(4L, compressed.messages!!.single().rowId)
    }

    @Test
    fun aCompressionRefusedByAHeldLockSaysSo() {
        val refused = wire.decodeFromString(
            Methods.SESSION_COMPRESS.result,
            """{"compressed":false,"lock_held":true,"message":"another compression is running"}""",
        )

        assertEquals(false, refused.compressed)
        assertEquals(true, refused.lockHeld)
        assertNull(refused.summary)
    }

    @Test
    fun aContextBreakdownDecodesItsCategories() {
        val breakdown = wire.decodeFromString(
            Methods.SESSION_CONTEXT_BREAKDOWN.result,
            """{"categories":[{"color":"#7aa2f7","id":"system","label":"System prompt","tokens":1200}],"context_max":8192,""" +
                """"context_percent":15,"context_used":1200,"estimated_total":1200,"context_estimated":true,""" +
                """"context_source":"estimate","model":"gemma4"}""",
        )

        assertEquals(1200L, breakdown.categories.single().tokens)
        assertTrue(breakdown.contextFiles.isEmpty())
        assertTrue(breakdown.contextEstimated)
    }

    @Test
    fun aTodoSnapshotKeepsItsItemsAsTheCoreSentThem() {
        val snapshot = wire.decodeFromString(
            Events.TODO_UPDATED.payload,
            """{"todos":[{"id":"1","content":"Read the file","status":"in_progress","extra":1}],"revision":3}""",
        )

        assertEquals(3L, snapshot.revision)
        assertEquals("in_progress", snapshot.todos.single().jsonObject["status"]!!.jsonPrimitive.content)
    }

    // The core reads a job's reference from `name` and adds fields to a row over time.

    @Test
    fun aJobCommandNamesItsJobByIdAndAListAsksForPausedJobs() {
        assertEquals(
            """{"include_disabled":true}""",
            wire.encodeToString(Methods.CRON_MANAGE.params, CronManageParams(includeDisabled = JsonPrimitive(true))),
        )
        assertEquals(
            """{"action":"pause","name":"a1b2c3d4e5f6"}""",
            wire.encodeToString(Methods.CRON_MANAGE.params, CronManageParams(action = CronAction.PAUSE, name = "a1b2c3d4e5f6")),
        )
        assertEquals(
            """{"action":"add","name":"Morning brief","schedule":"every 30m","prompt":"Summarise the news","repeat":3,"deliver":"local"}""",
            wire.encodeToString(
                Methods.CRON_MANAGE.params,
                CronManageParams(
                    action = CronAction.ADD, name = "Morning brief", schedule = "every 30m", prompt = "Summarise the news",
                    repeat = JsonPrimitive(3), deliver = "local",
                ),
            ),
        )
    }

    @Test
    fun aJobRowDecodesTheCoresOwnShape() {
        // `pinned` is the core's key the contract does not list (cronjob_job_args.py:492).
        val listed = wire.decodeFromString(
            Methods.CRON_MANAGE.result,
            """{"success":true,"count":1,"jobs":[{"job_id":"a1b2c3d4e5f6","name":"Morning brief","skill":null,"skills":[],""" +
                """"prompt_preview":"Summarise the news","model":null,"provider":null,"pinned":false,"base_url":null,""" +
                """"schedule":"every 30m","repeat":"forever","deliver":"local","next_run_at":"2026-11-13T09:30:00+01:00",""" +
                """"last_run_at":"2026-11-13T09:00:00+01:00","last_status":"delivery_failed",""" +
                """"last_delivery_error":"telegram: chat not found","last_delivery_unverified":null,"last_fire_error":null,""" +
                """"last_error":null,"enabled":false,"state":"paused","paused_at":"2026-11-13T09:05:00+01:00",""" +
                """"paused_reason":null}],"gateway_running":false,"warning":"The Hermes gateway is not running"}""",
        )

        assertEquals(1L, listed.count)
        val row = listed.jobs!!.single()
        assertEquals("a1b2c3d4e5f6", row.jobId)
        assertEquals("paused", row.state)
        assertEquals(false, row.enabled)
        assertEquals(JsonPrimitive("forever"), row.repeat)
        assertEquals("delivery_failed", row.lastStatus)
        assertEquals("telegram: chat not found", row.lastDeliveryError)
    }

    @Test
    fun aRefusedJobCommandIsAnAnswerNotAnError() {
        val refused = wire.decodeFromString(Methods.CRON_MANAGE.result, """{"success":false,"error":"schedule is required for create"}""")

        assertEquals(false, refused.success)
        assertEquals("schedule is required for create", refused.error)
        assertNull(refused.jobs)
        assertNull(refused.job)
    }

    @Test
    fun aPauseAndARemovalAnswerTheJob() {
        val paused = wire.decodeFromString(
            Methods.CRON_MANAGE.result,
            """{"success":true,"job":{"job_id":"a1b2c3d4e5f6","state":"paused","enabled":false}}""",
        )
        val removed = wire.decodeFromString(
            Methods.CRON_MANAGE.result,
            """{"success":true,"message":"Cron job 'Morning brief' removed.",""" +
                """"removed_job":{"id":"a1b2c3d4e5f6","name":"Morning brief","schedule":"every 30m"}}""",
        )

        assertEquals("paused", paused.job!!.state)
        assertEquals("a1b2c3d4e5f6", removed.removedJob!!.id)
    }

    @Test
    fun aChangeSignalHasNoFieldsAndToleratesNewOnes() {
        assertEquals(ChangeSignalPayload, wire.decodeFromString(Events.CRON_CHANGED.payload, "{}"))
        assertEquals(ChangeSignalPayload, wire.decodeFromString(Events.PLATFORMS_CHANGED.payload, """{"since":1}"""))
    }

    // A correction's status words are the core's; a check that cannot serve answers in words.

    @Test
    fun aCorrectionCarriesTheSessionAndTheWordsOnly() {
        val correction = SessionCorrectionParams(sessionId = "r1", text = "use metric units")

        assertEquals("""{"session_id":"r1","text":"use metric units"}""", wire.encodeToString(Methods.SESSION_STEER.params, correction))
        assertEquals("""{"session_id":"r1","text":"use metric units"}""", wire.encodeToString(Methods.SESSION_REDIRECT.params, correction))
    }

    @Test
    fun aCorrectionAnswersOneOfTheCoresThreeWords() {
        val queued = wire.decodeFromString(Methods.SESSION_STEER.result, """{"status":"queued","text":"use metric units"}""")
        val redirected = wire.decodeFromString(Methods.SESSION_REDIRECT.result, """{"status":"redirected","text":"use metric units"}""")
        val rejected = wire.decodeFromString(Methods.SESSION_STEER.result, """{"status":"rejected","text":"use metric units"}""")

        assertEquals(CorrectionStatus.QUEUED to "use metric units", queued.status to queued.text)
        assertEquals(CorrectionStatus.REDIRECTED, redirected.status)
        assertEquals(CorrectionStatus.REJECTED, rejected.status)
        // A word the contract does not name is a decode failure, never a default status.
        assertThrows(SerializationException::class.java) {
            wire.decodeFromString(Methods.SESSION_REDIRECT.result, """{"status":"applied","text":"x"}""")
        }
    }

    @Test
    fun aRuntimeCheckWithoutAProviderAsksForTheSessionRoute() {
        assertEquals("{}", wire.encodeToString(Methods.SETUP_RUNTIME_CHECK.params, SetupRuntimeCheckParams()))
        assertEquals(
            """{"provider":"openrouter"}""",
            wire.encodeToString(Methods.SETUP_RUNTIME_CHECK.params, SetupRuntimeCheckParams(provider = "openrouter")),
        )
    }

    @Test
    fun aFailedCheckKeepsTheCoresWordsAndAPassedOneItsRoute() {
        val failed = wire.decodeFromString(
            Methods.SETUP_RUNTIME_CHECK.result,
            """{"ok":false,"provider":"openrouter","model":"anthropic/claude-sonnet-4","source":"config",""" +
                """"error":"No usable credentials found for openrouter."}""",
        )
        // `error_code` is the newer core's field: ignored, not modelled.
        val passed = wire.decodeFromString(
            Methods.SETUP_RUNTIME_CHECK.result,
            """{"ok":true,"provider":"nous","model":"Hermes-4-405B","source":"pool","free_tier_route":true,"error_code":null}""",
        )
        val raised = wire.decodeFromString(Methods.SETUP_RUNTIME_CHECK.result, """{"ok":false,"error":"boom"}""")

        assertEquals(false, failed.ok)
        assertEquals("No usable credentials found for openrouter.", failed.error)
        assertEquals("openrouter", failed.provider)
        assertEquals(true, passed.ok)
        assertEquals(true, passed.freeTierRoute)
        assertNull(passed.error)
        assertEquals(false, raised.ok)
        assertNull(raised.provider)
    }

    @Test
    fun aStatusIsOneRenderedText() {
        assertEquals("""{"session_id":"r1"}""", wire.encodeToString(Methods.SESSION_STATUS.params, SessionStatusParams(sessionId = "r1")))

        val output = wire.decodeFromString(
            Methods.SESSION_STATUS.result,
            """{"output":"Hermes TUI Status\n\nSession ID: 20261115_101500_ab12cd\nAgent Running: No"}""",
        ).output

        assertEquals("Hermes TUI Status", output.lines().first())
        assertTrue(output, "Session ID: 20261115_101500_ab12cd" in output.lines())
    }

    // Skills, toolsets, reactions and slash commands speak the core's words; a refused write is an answer.

    @Test
    fun aSkillIsNamedByItsIdAndARefusedWriteIsAnAnswer() {
        assertEquals("""{"id":"github"}""", wire.encodeToString(Methods.LEARNING_DETAIL.params, LearningNodeParams(id = "github")))
        assertEquals("""{"id":"github"}""", wire.encodeToString(Methods.LEARNING_DELETE.params, LearningNodeParams(id = "github")))
        assertEquals(
            """{"id":"github","content":"---\nname: github\n---\nbody"}""",
            wire.encodeToString(Methods.LEARNING_EDIT.params, LearningEditParams(id = "github", content = "---\nname: github\n---\nbody")),
        )

        val read = wire.decodeFromString(
            Methods.LEARNING_DETAIL.result,
            """{"ok":true,"kind":"skill","id":"github","label":"github","content":"---\nname: github\n---\nbody"}""",
        )
        assertTrue(read.ok)
        assertEquals("skill", read.kind)
        assertTrue(read.content, read.content!!.startsWith("---"))

        val missing = wire.decodeFromString(Methods.LEARNING_DETAIL.result, """{"ok":false,"message":"skill 'nope' not found"}""")
        assertEquals(false, missing.ok)
        assertEquals("skill 'nope' not found", missing.message)
        assertNull(missing.content)

        val pinned = wire.decodeFromString(
            Methods.LEARNING_DELETE.result,
            """{"ok":false,"message":"'github' is pinned — unpin it first (hermes curator unpin github)"}""",
        )
        assertEquals(false, pinned.ok)
        assertEquals("'github' is pinned — unpin it first (hermes curator unpin github)", pinned.message)

        val edited = wire.decodeFromString(Methods.LEARNING_EDIT.result, """{"ok":true,"message":"updated 'github'"}""")
        assertTrue(edited.ok)
        assertEquals("updated 'github'", edited.message)
    }

    @Test
    fun aReloadAnswersItsPrintedLinesAndItsDiff() {
        assertEquals("{}", wire.encodeToString(Methods.SKILLS_RELOAD.params, SkillsReloadParams()))
        assertEquals("""{"session_id":"r1"}""", wire.encodeToString(Methods.SKILLS_RELOAD.params, SkillsReloadParams(sessionId = "r1")))

        val reload = wire.decodeFromString(
            Methods.SKILLS_RELOAD.result,
            """{"output":"Reloading skills...\nAdded skills:\n  - weather\n55 skill(s) available",""" +
                """"result":{"added":[{"name":"weather","description":"Forecasts"}],"removed":[],"unchanged":["github"],""" +
                """"total":55,"commands":55}}""",
        )

        assertEquals("weather", reload.result.added!!.single().name)
        assertEquals(55L, reload.result.total)
        assertEquals("55 skill(s) available", reload.output.lines().last())
    }

    @Test
    fun aSessionlessToolsetSwitchNamesNoSessionAndTheListNamesNoTools() {
        assertEquals("{}", wire.encodeToString(Methods.TOOLSETS_LIST.params, _SessionScoped()))

        val row = wire.decodeFromString(
            Methods.TOOLSETS_LIST.result,
            """{"toolsets":[{"name":"browser","description":"Browser automation","tool_count":11,"enabled":false}]}""",
        ).toolsets.single()
        assertEquals(11L, row.toolCount)
        assertEquals(false, row.enabled)
        assertNull(row.tools) // toolsets.list builds its rows without tool names (D3)

        assertEquals(
            """{"action":"disable","names":["browser"]}""",
            wire.encodeToString(Methods.TOOLS_CONFIGURE.params, ToolsConfigureParams(action = ToolsAction.DISABLE, names = listOf("browser"))),
        )

        val switched = wire.decodeFromString(
            Methods.TOOLS_CONFIGURE.result,
            """{"changed":["browser"],"enabled_toolsets":["file","terminal","web"],"info":null,"missing_servers":[],""" +
                """"reset":false,"unknown":[]}""",
        )
        assertEquals(false, switched.reset)
        assertNull(switched.info)
        assertEquals(listOf("browser"), switched.changed)
    }

    @Test
    fun theToolsListNamesEachToolsetsToolsAndNoSession() {
        assertEquals("tools.list", Methods.TOOLS_LIST.name)
        assertEquals("{}", wire.encodeToString(Methods.TOOLS_LIST.params, _SessionScoped()))

        val row = wire.decodeFromString(
            Methods.TOOLS_LIST.result,
            """{"toolsets":[{"name":"web","description":"Web search","tool_count":2,"enabled":true,"tools":["web_extract","web_search"]}]}""",
        ).toolsets.single()
        assertEquals(listOf("web_extract", "web_search"), row.tools)

        val keys = wire.decodeFromString(
            ToolsetsKeysReply.serializer(),
            """{"needs_keys":["vision"],"switchable":[{"name":"web","label":"Web Search & Scraping"}]}""",
        )
        assertEquals(listOf("vision"), keys.needsKeys)
        assertEquals(listOf(ToolsetLabel(name = "web", label = "Web Search & Scraping")), keys.switchable)
    }

    @Test
    fun aReactionIsTheUsersAndAClearCarriesNoEmoji() {
        assertEquals(
            """{"session_id":"r1","row_id":42,"emoji":"👍","author":"user"}""",
            wire.encodeToString(
                Methods.MESSAGE_REACT.params,
                MessageReactParams(sessionId = "r1", rowId = 42, emoji = "👍", author = ReactionAuthor.USER),
            ),
        )
        // The core reads an absent emoji as a clear.
        assertEquals(
            """{"session_id":"r1","row_id":42,"author":"user"}""",
            wire.encodeToString(
                Methods.MESSAGE_REACT.params,
                MessageReactParams(sessionId = "r1", rowId = 42, emoji = null, author = ReactionAuthor.USER),
            ),
        )

        val reaction = wire.decodeFromString(
            Methods.MESSAGE_REACT.result,
            """{"row_id":42,"reactions":[{"emoji":"👍","author":"user","at":1763300000.5}]}""",
        ).reactions.single()
        assertEquals("user", reaction.author)
        assertEquals(1763300000.5, reaction.at!!, 0.0)

        val painted = wire.decodeFromString(
            Events.MESSAGE_REACTION.payload,
            """{"row_id":42,"reactions":[{"emoji":"❤️","author":"agent","at":1.0,"seen":true}],"role":"assistant"}""",
        )
        assertEquals(42L, painted.rowId)
        assertEquals("assistant", painted.role)
        assertEquals(true, painted.reactions.single().seen)
    }

    @Test
    fun theCatalogKeysSkillsByTheirCommandAndACompletionSaysItsKind() {
        assertEquals("""{"session_id":"r1"}""", wire.encodeToString(Methods.COMMANDS_CATALOG.params, CommandsCatalogParams(sessionId = "r1")))

        val catalog = wire.decodeFromString(
            Methods.COMMANDS_CATALOG.result,
            """{"pairs":[["/new","Start a new session"],["/github","GitHub workflows"]],"canon":{"/new":"/new","/reset":"/new"},""" +
                """"commands":{"/new":{"argument_mode":null,"desktop":null},"/model":{"argument_mode":"options","desktop":"terminal"}},""" +
                """"categories":[{"name":"Session","pairs":[["/new","Start a new session"]]}],""" +
                """"skills":{"/github":{"usage":3,"origin":"bundled"}},"skill_count":1,"warning":""}""",
        )
        assertEquals(3L, catalog.skills!!.getValue("/github").usage)
        assertEquals("bundled", catalog.skills!!.getValue("/github").origin)
        assertEquals(1L, catalog.skillCount)
        assertEquals("/new", catalog.canon!!.getValue("/reset"))
        assertEquals(ArgumentMode.OPTIONS, catalog.commands!!.getValue("/model").argumentMode)

        assertEquals(
            """{"text":"/git","session_id":"r1"}""",
            wire.encodeToString(Methods.COMPLETE_SLASH.params, CompleteSlashParams(text = "/git", sessionId = "r1")),
        )
        val completed = wire.decodeFromString(
            Methods.COMPLETE_SLASH.result,
            """{"items":[{"text":"github","display":"/github","meta":"GitHub workflows","kind":"skill"}],"replace_from":1}""",
        )
        assertEquals("skill", completed.items!!.single().kind)
        assertEquals(1L, completed.replaceFrom)
    }

    @Test
    fun aDispatchAnswersOneOfTheCoresSixDirectivesWithDisplayApartFromMessage() {
        assertEquals(
            """{"name":"github","arg":"open PRs","session_id":"r1"}""",
            wire.encodeToString(Methods.COMMAND_DISPATCH.params, CommandDispatchParams(name = "github", arg = "open PRs", sessionId = "r1")),
        )

        // The bubble shows `display`; the model gets `message`.
        val skill = wire.decodeFromString(
            Methods.COMMAND_DISPATCH.result,
            """{"type":"skill","message":"<the expanded github skill>\n\nopen PRs","name":"github","display":"/github open PRs"}""",
        )
        assertEquals(DispatchType.SKILL, skill.type)
        assertEquals("/github open PRs", skill.display)
        assertEquals("<the expanded github skill>\n\nopen PRs", skill.message)

        val send = wire.decodeFromString(Methods.COMMAND_DISPATCH.result, """{"type":"send","message":"p1 probe"}""")
        assertEquals(DispatchType.SEND, send.type)
        assertNull(send.display)
        val exec = wire.decodeFromString(Methods.COMMAND_DISPATCH.result, """{"type":"exec","output":"done"}""")
        assertEquals(DispatchType.EXEC, exec.type)
        assertEquals("done", exec.output)
        assertEquals(DispatchType.ALIAS, wire.decodeFromString(Methods.COMMAND_DISPATCH.result, """{"type":"alias","target":"/new"}""").type)

        // A directive the contract does not name is a decode failure, never a default.
        assertThrows(SerializationException::class.java) {
            wire.decodeFromString(Methods.COMMAND_DISPATCH.result, """{"type":"slash","output":"x"}""")
        }
    }
}
