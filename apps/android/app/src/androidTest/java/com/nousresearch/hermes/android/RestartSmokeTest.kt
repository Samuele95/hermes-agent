package com.nousresearch.hermes.android

import android.os.Process
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SessionListParams
import com.nousresearch.hermes.bridge.protocol.SessionMostRecentParams
import com.nousresearch.hermes.bridge.protocol.SkillsManageParams
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CP5: what a turn makes survives the app's process. `scripts/restart_smoke.sh` runs [aMemoryASkillAndAConversationAreMadeInTurns]
 * on a clean install, kills the app (`am force-stop`), then runs [afterAProcessKillTheCoreStillFindsThem] in a new process.
 * A test cannot outlive its own process, so the two phases share what they need through a file of the app.
 */
@Smoke
@RunWith(AndroidJUnit4::class)
class RestartSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val steps = SmokeSteps(compose)
    private val token = requireNotNull(steps.args.getString("token")) { "no token given: run scripts/restart_smoke.sh" }

    // Three words, each living in one place only; phase B's prompts name none of them, so a reply that
    // carries one found it where it lives (memory, the skill's body, the earlier conversation).
    private val memoryWord = "mem$token"
    private val skillWord = "sk$token"
    private val spotWord = "spot$token"
    private val skill = "restart-smoke-skill"
    private val home = File(steps.app.filesDir, ".hermes")
    private val handover = File(steps.app.filesDir, "restart-smoke.json")

    @Before
    fun allowNotifications() = steps.allowNotifications()

    private fun gateway() = (steps.app.core.value as CoreState.Ready).gateway

    /** Every memory store the core writes (facts about the user land in USER.md, others in MEMORY.md). */
    private fun memories(): String = File(home, "memories").listFiles().orEmpty().filter { it.isFile }.joinToString("\n") { it.readText() }

    private fun skillFile(): File? = File(home, "skills").walkTopDown().firstOrNull { it.name == "SKILL.md" && it.parentFile?.name == skill }

    private fun List<Entry>.tools() = filterIsInstance<Entry.Tool>().filter { it.done }.map { it.name }

    private fun List<Entry>.replyText() = filterIsInstance<Entry.Reply>().lastOrNull()?.text.orEmpty()

    @Test
    fun aMemoryASkillAndAConversationAreMadeInTurns() {
        steps.setUpEndpoint()

        val remembered = steps.say("Use the memory tool to save this fact about me: my test word is $memoryWord.")
        assertTrue("the memory tool ran: $remembered", "memory" in remembered.tools())
        assertTrue("the memory is on disk", memoryWord in memories())

        val created = steps.say("Use the skill_manage tool to create a skill named $skill with exactly this SKILL.md content:\n" +
                                "---\nname: $skill\ndescription: Holds a word for the restart smoke test.\n---\n" +
                                "The word this skill holds is $skillWord.")
        assertTrue("skill_manage ran: $created", "skill_manage" in created.tools())
        assertTrue("the skill is on disk", skillFile()?.readText()?.contains(skillWord) == true)

        steps.say("Just for this conversation, without saving it to memory: I parked in spot $spotWord. Reply with OK.")
        assertTrue("the spot is only in the conversation, not in memory", spotWord !in memories())

        val stored = runBlocking { gateway().call(Methods.SESSION_MOST_RECENT, SessionMostRecentParams()) }.sessionId
        handover.writeText(JSONObject(mapOf("session" to stored, "pid" to Process.myPid())).toString())
    }

    @Test
    fun afterAProcessKillTheCoreStillFindsThem() {
        val before = JSONObject(handover.readText())
        assertNotEquals("a new process", before.getInt("pid"), Process.myPid())
        steps.awaitChat()

        // On disk, and in the core's own listings (no model involved).
        assertTrue("the memory is on disk", memoryWord in memories())
        assertTrue("the skill is on disk", skillFile() != null)
        val listed = runBlocking { gateway().call(Methods.SKILLS_MANAGE, SkillsManageParams()) }.skills.orEmpty().values.flatten()
        assertTrue("the core lists the skill: $listed", skill in listed)
        val sessions = runBlocking { gateway().call(Methods.SESSION_LIST, SessionListParams(limit = 50)) }.sessions.map { it.id }
        assertTrue("the conversation is listed: $sessions", before.getString("session") in sessions)

        // And in turns of a new session: the memory is in its prompt, the skill and the old conversation are found.
        val recalled = steps.say("What is my test word? Answer with the word only, without using any tool.")
        assertTrue("recalled from the prompt's memory, no tool: $recalled", recalled.tools().isEmpty() && memoryWord in recalled.replyText())
        val viewed = steps.say("Use the skill_view tool to read the skill $skill and tell me the word it holds.")
        assertTrue("skill_view read the skill's body: $viewed", "skill_view" in viewed.tools() && skillWord in viewed.replyText())
        // A background review may save memories after a turn: the spot must still be in the conversation only.
        assertTrue("the spot is not in memory", spotWord !in memories())
        // The query is given: this checks that a conversation from before the kill is found, not the model's choice of words.
        val searched = steps.say("Use the session_search tool with the query \"parked\" to find my earlier conversation about " +
                                 "where I parked, and tell me the spot.")
        assertTrue("session_search found the earlier conversation: $searched",
                   "session_search" in searched.tools() && spotWord in searched.replyText())
        handover.delete()
    }
}
