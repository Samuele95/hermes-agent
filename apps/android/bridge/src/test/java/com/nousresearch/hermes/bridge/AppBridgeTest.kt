package com.nousresearch.hermes.bridge

import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.CronRunsParams
import com.nousresearch.hermes.bridge.contract.CronUpdateParams
import com.nousresearch.hermes.bridge.contract.Pinned
import com.nousresearch.hermes.bridge.contract.SaveCustomEndpointParams
import com.nousresearch.hermes.bridge.contract.SettingsWriteParams
import com.nousresearch.hermes.bridge.contract.SkillsEnableParams
import com.nousresearch.hermes.bridge.contract.SshHostKeyParams
import com.nousresearch.hermes.bridge.contract.TelegramEnableParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's typed bridge calls send the contract's request and read its reply (D26). */
class AppBridgeTest {
    private val sent = mutableListOf<Pair<String, String>>()
    private fun answering(reply: String) = AppBridge { function, arg -> sent += function to arg; reply }

    @Test
    fun aRequestGoesOutUnderTheCallsNameWithTheContractsFieldNames() = runBlocking {
        answering("""{"id":"lan","key_env":"","forget":null}""")
            .ask(BridgeCalls.SAVE_CUSTOM_ENDPOINT, SaveCustomEndpointParams(null, "lan", "http://10.0.0.2/v1", "m", true))

        val (function, arg) = sent.single()
        assertEquals("save_custom_endpoint", function)
        assertEquals(Json.parseToJsonElement("""{"id":null,"name":"lan","base_url":"http://10.0.0.2/v1","model":"m","has_key":true}"""),
                     Json.parseToJsonElement(arg))
    }

    @Test
    fun aCallWithoutParamsSendsAnEmptyObject() = runBlocking {
        answering("""{"public":"ssh-ed25519 AAAA"}""").ask(BridgeCalls.SSH_PUBLIC_KEY)

        assertEquals("{}", sent.single().second)
    }

    @Test
    fun theReplyDecodesAsTheCallsDeclaredType() = runBlocking {
        val seen = answering("""{"key":"ssh-ed25519 AAAA","fingerprint":"SHA256:x","pinned":"changed"}""")
            .ask(BridgeCalls.SSH_HOST_KEY, SshHostKeyParams("h", 22))

        assertEquals(Pinned.CHANGED, seen.pinned)
    }

    @Test
    fun anErrorReplyIsTheUsersWords() {
        val refused = assertThrows(CoreError::class.java) {
            runBlocking { answering("""{"error":"Could not reach h:22"}""").ask(BridgeCalls.SSH_HOST_KEY, SshHostKeyParams("h", 22)) }
        }

        assertEquals("Could not reach h:22", refused.message)
    }

    @Test
    fun aJobEditSendsEveryDeclaredFieldWithNullForTheUnchangedOnes() = runBlocking {
        val saved = answering("""{"job_id":"a1b2c3d4e5f6"}""")
            .ask(BridgeCalls.CRON_UPDATE, CronUpdateParams("a1b2c3d4e5f6", null, "every 2h", null, null, null))

        assertEquals(Json.parseToJsonElement("""{"job_id":"a1b2c3d4e5f6","name":null,"schedule":"every 2h","prompt":null,"deliver":null,"repeat":null}"""),
                     Json.parseToJsonElement(sent.single().second))
        assertEquals("a1b2c3d4e5f6", saved.jobId)
    }

    @Test
    fun theCronRepliesDecodeTheirNullableFields() = runBlocking {
        val run = answering("""{"runs":[{"session_id":"cron_a1b2c3d4e5f6_20261113_090000","started_at":1794583456,"ended_at":null,"messages":4,"preview":null,"active":true}]}""")
            .ask(BridgeCalls.CRON_RUNS, CronRunsParams("a1b2c3d4e5f6", 20)).runs.single()
        assertNull(run.endedAt)
        assertEquals(1794583456, run.startedAt)
        assertTrue(run.active)

        val ticker = answering("""{"heartbeat_age_s":null,"success_age_s":null,"last_error":null,"stale_after_s":200}""")
            .ask(BridgeCalls.CRON_SCHEDULER)
        assertNull(ticker.heartbeatAgeS)
        assertEquals(200, ticker.staleAfterS)

        val target = answering("""{"targets":[{"id":"telegram","name":"Telegram","home_set":false}]}""")
            .ask(BridgeCalls.CRON_DELIVERY_TARGETS).targets.single()
        assertEquals(false, target.homeSet)
    }

    @Test
    fun theBotSwitchSendsItsOneFieldAndTheStatusCarriesTheCoresEntry() = runBlocking {
        val switched = answering("""{"enabled":false}""").ask(BridgeCalls.TELEGRAM_ENABLE, TelegramEnableParams(false))
        assertEquals(Json.parseToJsonElement("""{"enabled":false}"""), Json.parseToJsonElement(sent.single().second))
        assertEquals(false, switched.enabled)

        val status = answering("""{"token":true,"allowed_user":"42","sdk":true,"enabled":false,"live":{"state":"fatal","error_message":"Conflict: terminated by other getUpdates request","writer_pid":12345,"updated_at":"2026-11-13T15:48:13.243611+00:00"}}""")
            .ask(BridgeCalls.TELEGRAM_STATUS)
        assertEquals(false, status.enabled)
        assertEquals(12345, status.live!!.writerPid)
        assertEquals("fatal", status.live!!.state)

        val none = answering("""{"token":true,"allowed_user":"42","sdk":true,"enabled":false,"live":null}""")
            .ask(BridgeCalls.TELEGRAM_STATUS)
        assertNull(none.live)
    }

    @Test
    fun aSettingsEditSendsEveryDeclaredFieldWithNullForTheUntouchedOnes() = runBlocking {
        val written = answering("""{"applied":true}""").ask(
            BridgeCalls.SETTINGS_WRITE,
            SettingsWriteParams(approvalsTimeoutS = 120, memoryEnabled = null, userProfileEnabled = false,
                                memoryCharLimit = null, userCharLimit = null, allowlistRemove = listOf("docker *")))

        val (function, arg) = sent.single()
        assertEquals("settings_write", function)
        assertEquals(Json.parseToJsonElement("""{"approvals_timeout_s":120,"memory_enabled":null,"user_profile_enabled":false,"memory_char_limit":null,"user_char_limit":null,"allowlist_remove":["docker *"]}"""),
                     Json.parseToJsonElement(arg))
        assertTrue(written.applied)
    }

    @Test
    fun theSettingsReplyDecodesItsListsAndPersonalities() = runBlocking {
        val read = answering("""{"approvals_timeout_s":300,"command_allowlist":["git status"],"memory_enabled":true,"user_profile_enabled":false,"memory_char_limit":2200,"user_char_limit":1375,"personalities":[{"name":"helpful","description":"You are a helpful, friendly AI assistant."}]}""")
            .ask(BridgeCalls.SETTINGS_READ)

        assertEquals("settings_read" to "{}", sent.single())
        assertEquals(300, read.approvalsTimeoutS)
        assertEquals(listOf("git status"), read.commandAllowlist)
        assertEquals(false, read.userProfileEnabled)
        assertEquals("helpful", read.personalities.single().name)
    }

    @Test
    fun aSkillsSwitchSendsItsNamesAndDecodesTheCoresList() = runBlocking {
        val skills = answering("""{"skills":[{"name":"plain","description":"","category":null,"enabled":false},{"name":"arxiv","description":"Search arXiv.","category":"research","enabled":true}]}""")
            .ask(BridgeCalls.SKILLS_ENABLE, SkillsEnableParams(listOf("arxiv", "scholar"), false)).skills

        val (function, arg) = sent.single()
        assertEquals("skills_enable", function)
        assertEquals(Json.parseToJsonElement("""{"names":["arxiv","scholar"],"enabled":false}"""), Json.parseToJsonElement(arg))
        assertNull(skills[0].category)
        assertEquals("research", skills[1].category)
        assertTrue(skills[1].enabled)

        sent.clear()
        answering("""{"skills":[]}""").ask(BridgeCalls.SKILLS_LIST)
        assertEquals("skills_list" to "{}", sent.single())
    }
}
