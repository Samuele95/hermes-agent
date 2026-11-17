package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.CommandDispatchResult
import com.nousresearch.hermes.bridge.protocol.CommandsCatalogResult
import com.nousresearch.hermes.bridge.protocol.CompleteSlashResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core's `commands.catalog` as the shipped core answers it (P1's FOUND): registry commands under their category,
 * a TUI extra, a quick command under "User commands", a plugin's under "Plugin commands", skills in `pairs` and `skills`
 * only. Shared with [ChatSessionTest]'s fake core.
 */
internal const val CATALOG = """{
  "pairs":[["/new","Start a new session"],["/retry","Retry the last message"],["/compress","Compress the context"],
           ["/queue","Queue a prompt"],["/steer","Steer the turn"],["/model","Switch model"],["/status","Show status"],
           ["/undo","Undo the last turn"],["/goal","Set a goal"],["/memory","Review memory writes"],["/focus","Focus view"],
           ["/snapshot","Snapshots"],["/skills","Skills hub"],["/density","TUI density"],["/hi","alias → github"],
           ["/plug","Plugin command"],["/github","Work with GitHub"],["/worktree-kickoff","Start a worktree"]],
  "sub":{},
  "canon":{"/new":"/new","/retry":"/retry","/compress":"/compress","/queue":"/queue","/steer":"/steer","/model":"/model",
           "/status":"/status","/undo":"/undo","/goal":"/goal","/memory":"/memory","/focus":"/focus","/snapshot":"/snapshot",
           "/skills":"/skills","/density":"/density","/hi":"/hi","/plug":"/plug",
           "/reset":"/new","/q":"/queue","/s":"/steer","/compact":"/compress","/snap":"/snapshot"},
  "commands":{},
  "categories":[
    {"name":"Session","pairs":[["/new","Start a new session"],["/retry","Retry the last message"],["/compress","Compress the context"],
                               ["/queue","Queue a prompt"],["/steer","Steer the turn"],["/model","Switch model"],["/status","Show status"],
                               ["/undo","Undo the last turn"],["/goal","Set a goal"],["/memory","Review memory writes"],
                               ["/focus","Focus view"],["/snapshot","Snapshots"],["/skills","Skills hub"]]},
    {"name":"TUI","pairs":[["/density","TUI density"]]},
    {"name":"User commands","pairs":[["/hi","alias → github"]]},
    {"name":"Plugin commands","pairs":[["/plug","Plugin command"]]}],
  "skills":{"/github":{"usage":3,"origin":"bundled"},"/worktree-kickoff":{"usage":7,"origin":"local"}},
  "skill_count":2,
  "warning":""
}"""

/** The slash menu and its lines: what the phone runs, read from the core's catalog, and the core's directives (chat-20, bet 9). */
class SlashTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val catalog = SlashCatalog.of(json.decodeFromString(CommandsCatalogResult.serializer(), CATALOG))

    private fun completion(raw: String) = json.decodeFromString(CompleteSlashResult.serializer(), raw)
    private fun directive(raw: String) = Dispatch.of(json.decodeFromString(CommandDispatchResult.serializer(), raw))

    @Test
    fun theCatalogListsTheAppsOwnTheDispatchedAndTheSkillsNeverASlashExecCommand() {
        val items = catalog.items

        assertEquals(listOf("/new", "/model", "/compress", "/retry", "/steer", "/queue", "/undo", "/goal", "/memory", "/hi", "/plug",
                            "/worktree-kickoff", "/github"), items.map { it.name })
        assertEquals(List(6) { SlashKind.COMMAND to Runner.APP }, items.take(6).map { it.kind to it.runs })
        assertEquals(List(5) { SlashKind.COMMAND to Runner.DISPATCH }, items.drop(6).take(5).map { it.kind to it.runs })
        assertEquals(List(2) { SlashKind.SKILL to Runner.DISPATCH }, items.takeLast(2).map { it.kind to it.runs })
        assertEquals("Start a new chat", items.first().description)  // the app's own words for the app's own six
        assertEquals("Review memory writes", items.single { it.name == "/memory" }.description)  // the core's for the rest
        listOf("/status", "/focus", "/snapshot", "/skills", "/density").forEach { name ->
            assertTrue("$name is slash.exec's (chat-21)", items.none { it.name == name })
        }
        assertEquals(2L, catalog.skillCount)
    }

    @Test
    fun aLineIsKnownByItsNameOrAliasAndAnythingElseIsText() {
        assertEquals(SlashLine("github", "fix the CI", Runner.DISPATCH), catalog.line("/github fix the CI"))
        assertEquals(SlashLine("queue", "later", Runner.APP), catalog.line("/Q later"))
        assertEquals(SlashLine("new", "", Runner.APP), catalog.line("/reset"))
        assertEquals(SlashLine("github", "and more", Runner.DISPATCH), catalog.line("/github\nand more"))
        listOf("/status", "/sdcard/notes.txt sum it up", "/", "hi /github").forEach { text ->
            assertNull(text, catalog.line(text))
        }

        assertEquals("/status", catalog.unavailable("/status"))
        assertEquals("/snapshot", catalog.unavailable("/snap"))
        assertNull(catalog.unavailable("/sdcard/x"))
        assertNull("a line the phone runs is not unavailable", catalog.unavailable("/github x"))
    }

    @Test
    fun typingNarrowsTheMenuToWhatThePhoneRuns() {
        val git = completion("""{"items":[{"text":"worktree","kind":"command"},{"text":"diff","kind":"command"},
                                          {"text":"github","kind":"skill"}],"replace_from":1}""")

        assertEquals(listOf(SlashItem("/github", "Work with GitHub", SlashKind.SKILL, Runner.DISPATCH, fill = "/github ")),
                     catalog.narrowed("/git", git))

        val q = completion("""{"items":[{"text":"worktree-kickoff","kind":"skill"},{"text":"queue","kind":"command"},
                                        {"text":"goal","kind":"command"},{"text":"queue","kind":"command"}],"replace_from":1}""")
        assertEquals(listOf("/queue" to SlashKind.COMMAND, "/goal" to SlashKind.COMMAND, "/worktree-kickoff" to SlashKind.SKILL),
                     catalog.narrowed("/q", q).map { it.name to it.kind })
    }

    @Test
    fun aPickFillsTheLineFromReplaceFrom() {
        assertEquals("/github", fillOf("/gi", 1, "github"))
        assertEquals("/github", fillOf("/gi", 1, "/github"))
        assertEquals("/memory pending", fillOf("/memory pe", 8, "pending"))
        assertEquals("/y", fillOf("/x", null, "y"))

        val option = completion("""{"items":[{"text":"pending","meta":"list staged writes"}],"replace_from":8}""")
        assertEquals(listOf(SlashItem("pending", "list staged writes", SlashKind.OPTION, Runner.DISPATCH, fill = "/memory pending")),
                     catalog.narrowed("/memory pe", option))
        assertEquals("the app's own take no options", emptyList<SlashItem>(), catalog.narrowed("/model gp", option))
        assertEquals("nor does a command the phone cannot run", emptyList<SlashItem>(), catalog.narrowed("/status x", option))
    }

    @Test
    fun browsingShowsCommandsThenSkillsMostUsedFirstAndAFailedCompletionFallsBackByName() {
        val menu = catalog.browse()

        assertEquals(listOf("/plug", "/worktree-kickoff", "/github"), menu.takeLast(3).map { it.name })
        assertEquals("/new", menu.first().name)
        assertTrue(menu.all { it.fill == it.name + " " })

        assertEquals(listOf("/github"), catalog.prefixed("/git").map { it.name })
        assertEquals(listOf("/retry"), catalog.prefixed("/re").map { it.name })
        assertEquals("/github ", catalog.prefixed("/git").single().fill)
        assertEquals(emptyList<SlashItem>(), catalog.prefixed("/memory p"))
    }

    @Test
    fun eachDispatchDirectiveMapsOnce() {
        assertEquals(Dispatch.Send("<BODY>", "/github fix it", null),
                     directive("""{"type":"skill","message":"<BODY>","name":"github","display":"/github fix it"}"""))
        assertEquals(Dispatch.Send("Plan this", null, null), directive("""{"type":"send","message":"Plan this","notice":"  "}"""))
        assertEquals("⊙ Goal set", (directive("""{"type":"send","message":"Go","notice":"⊙ Goal set"}""") as Dispatch.Send).notice)
        assertEquals(Dispatch.Prefill("earlier words", "↶ Undid 1 turn"),
                     directive("""{"type":"prefill","message":"earlier words","notice":"↶ Undid 1 turn"}"""))
        assertEquals(Dispatch.Said("No pending writes."), directive("""{"type":"exec","output":"No pending writes."}"""))
        assertEquals(Dispatch.Said("(no output)"), directive("""{"type":"exec","output":""}"""))
        assertEquals(Dispatch.Said("(no output)"), directive("""{"type":"plugin"}"""))
        assertEquals(Dispatch.Alias("github"), directive("""{"type":"alias","target":"/github"}"""))
    }
}
