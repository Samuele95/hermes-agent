package com.nousresearch.hermes.android

import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.ChatPlaces
import com.nousresearch.hermes.android.entry.IncomingText
import com.nousresearch.hermes.android.chat.ContextActions
import com.nousresearch.hermes.android.chat.QueueActions
import com.nousresearch.hermes.android.chat.QueueUi
import com.nousresearch.hermes.android.chat.Queued
import com.nousresearch.hermes.android.chat.ContextUi
import com.nousresearch.hermes.android.chat.ExportActions
import com.nousresearch.hermes.android.chat.ExportUi
import com.nousresearch.hermes.android.chat.StatusActions
import com.nousresearch.hermes.android.chat.StatusUi
import com.nousresearch.hermes.android.chat.ModelActions
import com.nousresearch.hermes.android.chat.PickerUi
import com.nousresearch.hermes.android.setup.ModelCatalog
import com.nousresearch.hermes.android.setup.ModelRow
import com.nousresearch.hermes.bridge.protocol.ContextCategory
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownResult
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.nousresearch.hermes.android.setup.Readiness
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.BillingWall
import com.nousresearch.hermes.android.chat.BusyMode
import com.nousresearch.hermes.android.chat.Chat
import com.nousresearch.hermes.android.chat.Correction
import com.nousresearch.hermes.android.chat.ChatScreen
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.NoticeShortcut
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.chat.ContextMeter
import com.nousresearch.hermes.android.chat.Todo
import com.nousresearch.hermes.android.chat.TodoList
import com.nousresearch.hermes.android.chat.TodoStatus
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import com.nousresearch.hermes.bridge.CoreEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.nousresearch.hermes.android.chat.transcriptOf
import com.nousresearch.hermes.bridge.protocol.TranscriptMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nousresearch.hermes.android.chat.HistoryUi
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import org.junit.Rule
import android.text.format.DateFormat
import java.util.Calendar
import org.junit.Test
import org.junit.runner.RunWith

/** The chat screen shows a transcript and hands a typed message over; it holds no chat state itself. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTranscriptShowsEveryKindOfEntry() {
        val chat = Chat(listOf(Entry.User("what's the weather?", stableId = 0), Entry.Tool("t1", "web_search", "3 results", done = true, stableId = 1),
                               Entry.Tool("t2", "clarify", "", done = true, stableId = 2),
                               Entry.Reply("Sunny.", TurnStatus.COMPLETE, stableId = 3),
                               Entry.Reply("", TurnStatus.ERROR, error = "provider unreachable", stableId = 4),
                               Entry.Notice("session busy", stableId = 5)))

        compose.setContent { ChatScreen(chat, onSend = {}) }

        listOf("what's the weather?", "web_search — 3 results", "Sunny.", "provider unreachable", "session busy")
            .forEach { compose.onNodeWithText(it, substring = true).assertExists() }
        compose.onNodeWithText("clarify").assertExists()  // a blank summary leaves the name alone, no dangling dash
    }

    @Test
    fun aRepliesMarkdownIsDrawnNotShownAsMarkup() {
        val reply = Entry.Reply("## Plan\n\n**Bold** and `code`\n\n- one\n- two\n\n```sh\nls -la\n```", TurnStatus.COMPLETE, stableId = 0)

        compose.setContent { ChatScreen(Chat(listOf(reply)), onSend = {}) }

        listOf("Plan", "Bold and code", "one", "two", "ls -la").forEach { compose.onNodeWithText(it).assertExists() }
        listOf("**", "## ", "- one", "```").forEach { compose.onNodeWithText(it, substring = true).assertDoesNotExist() }
    }

    @Test
    fun commentaryIsDrawnAsMarkdownAboveItsTool() {
        val chat = Chat(listOf(Entry.User("what day is it?", stableId = 0), Entry.Interim("Let me **check** the date.", 1),
                               Entry.Tool("t1", "terminal", "Mon 3 Nov", done = true, stableId = 2),
                               Entry.Reply("It is Monday.", TurnStatus.COMPLETE, stableId = 3)))

        compose.setContent { ChatScreen(chat, onSend = {}) }

        compose.onNodeWithTag("entry-interim").assertExists()
        compose.onNodeWithText("Let me check the date.").assertExists()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
        val tops = listOf(compose.onNodeWithTag("entry-interim"), compose.onNodeWithText("terminal — Mon 3 Nov"),
                          compose.onNodeWithText("It is Monday.")).map { it.getUnclippedBoundsInRoot().top }
        assertTrue("commentary, then its tool, then the answer: $tops", tops.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun commentaryStaysWhileTheTurnRunsOn() {
        compose.setContent { ChatScreen(Chat(listOf(Entry.User("hi", stableId = 0), Entry.Interim("Let me check.", 1)), streaming = ""), onSend = {}) }

        compose.onNodeWithText("Let me check.").assertExists()
        compose.onNodeWithText("…").assertExists()
        compose.onNodeWithTag("stop").assertExists()
    }

    @Test
    fun aStreamingReplyAndTheAgentsActivityShowWhileTheTurnRuns() {
        compose.setContent { ChatScreen(Chat(streaming = "Hel", activity = "Thinking…"), onSend = {}) }

        compose.onNodeWithText("Hel").assertExists()
        compose.onNodeWithText("Thinking…").assertExists()
    }

    @Test
    fun theModelsReasoningShowsWhileItStreams() {
        compose.setContent { ChatScreen(Chat(streaming = "", reasoning = "Weighing the options"), onSend = {}) }

        compose.onNodeWithTag("reasoning-streaming").assertExists()
        compose.onNodeWithText("Weighing the options", substring = true).assertExists()
    }

    @Test
    fun aRepliesReasoningIsFoldedAwayUntilAskedFor() {
        val chat = Chat(listOf(Entry.Reply("It is 42.", TurnStatus.COMPLETE, reasoning = "Counted twice", stableId = 0),
                               Entry.Reply("Plain answer.", TurnStatus.COMPLETE, stableId = 1)))
        compose.setContent { ChatScreen(chat, onSend = {}) }

        compose.onNodeWithText("Counted twice", substring = true).assertDoesNotExist()
        compose.onAllNodesWithTag("reasoning-toggle").assertCountEquals(1)  // the plain answer has none
        compose.onNodeWithTag("reasoning-toggle").performClick()

        compose.onNodeWithText("Counted twice", substring = true).assertExists()
    }

    @Test
    fun aRunningTurnCanBeStoppedAndAnIdleChatSends() {
        var stopped = 0
        compose.setContent { ChatScreen(Chat(streaming = "Once upon"), onSend = {}, onStop = { stopped++ }) }

        compose.onNodeWithTag("send").assertDoesNotExist()
        compose.onNodeWithTag("stop").performClick()

        assertEquals(1, stopped)
    }

    @Test
    fun whileATurnRunsSendStandsBesideStopNamedByTheCoresMode() {
        val sent = mutableListOf<String>()
        var stopped = 0
        var chat by mutableStateOf(Chat(streaming = "Once upon", busyMode = BusyMode.INTERRUPT))
        compose.setContent { ChatScreen(chat, onSend = { sent += it }, onStop = { stopped++ }) }

        compose.onNodeWithTag("stop").assertExists()
        compose.onNodeWithTag("send").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("Redirect the current run", useUnmergedTree = true).assertExists()  // the field says it too

        compose.onNodeWithTag("draft").performTextInput("use metric units")
        compose.onNodeWithTag("send").assertIsEnabled()
        compose.onNodeWithContentDescription("Redirect the current run").assertExists()
        // Stop stays the right-most control, the only interrupt.
        assertTrue(compose.onNodeWithTag("stop").getUnclippedBoundsInRoot().left >= compose.onNodeWithTag("send").getUnclippedBoundsInRoot().right)
        compose.onNodeWithTag("send").performClick()

        assertEquals(listOf("use metric units"), sent)
        compose.onNodeWithTag("draft").assert(!hasText("use metric units", substring = true))
        compose.onNodeWithTag("stop").performClick()
        assertEquals(1, stopped)

        chat = chat.copy(busyMode = BusyMode.STEER)
        compose.onNodeWithContentDescription("Steer the current run").assertExists()

        chat = chat.copy(busyMode = BusyMode.QUEUE)  // the app's queue is M3's: Stop alone, as before
        compose.onNodeWithTag("send").assertDoesNotExist()
        compose.onNodeWithTag("stop").assertExists()
    }

    /** What the queue panel and the Queue button asked for, in order. */
    private class QueueCalls {
        val added = mutableListOf<String>()
        val edits = mutableListOf<Long?>()
        val saves = mutableListOf<Pair<Long, String>>()
        val steered = mutableListOf<Long>()
        val next = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
        var resumed = 0
        val actions = QueueActions(add = { added += it }, edit = { edits += it }, save = { id, text -> saves += id to text },
                                   steer = { steered += it }, next = { next += it }, delete = { deleted += it }, resume = { resumed++ })
    }

    private val twoQueued = QueueUi(listOf(Queued(0, "first"), Queued(1, "second")))

    @Test
    fun theQueueShowsAboveTheComposerAndEachRowOffersItsActions() {
        val calls = QueueCalls()
        var queue by mutableStateOf(QueueUi())
        val chat = Chat(streaming = "Working", busyMode = BusyMode.INTERRUPT)
        compose.setContent { ChatScreen(chat, onSend = {}, queue = queue, queueActions = calls.actions) }

        compose.onNodeWithTag("queue-panel").assertDoesNotExist()  // an empty queue shows nothing

        queue = twoQueued
        compose.onNodeWithText("2 Queued").assertIsDisplayed()
        compose.onAllNodesWithTag("queue-item").assertCountEquals(2)
        assertTrue(compose.onNodeWithTag("queue-panel").getUnclippedBoundsInRoot().bottom <= compose.onNodeWithTag("draft").getUnclippedBoundsInRoot().top)

        compose.onAllNodesWithTag("queue-more")[0].performClick()
        listOf("Edit", "Steer now", "Send next", "Delete").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Send next").performClick()
        assertEquals(listOf(0L), calls.next)

        compose.onAllNodesWithTag("queue-more")[1].performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf(1L), calls.deleted)

        compose.onAllNodesWithTag("queue-more")[0].performClick()
        compose.onNodeWithText("Steer now").performClick()
        assertEquals(listOf(0L), calls.steered)
    }

    @Test
    fun aPausedQueueOffersResumeAndAnIdleRowSendsAtOnce() {
        val calls = QueueCalls()
        compose.setContent { ChatScreen(Chat(), onSend = {}, queue = twoQueued.copy(paused = true), queueActions = calls.actions) }

        compose.onNodeWithText("2 Queued — paused").assertIsDisplayed()
        compose.onNodeWithTag("queue-resume").performClick()
        assertEquals(1, calls.resumed)

        compose.onAllNodesWithTag("queue-more")[0].performClick()
        compose.onNodeWithText("Send").assertIsDisplayed()
        compose.onNodeWithText("Send next").assertDoesNotExist()
        compose.onNodeWithText("Steer now").assertDoesNotExist()  // nothing runs: no correction can land
    }

    @Test
    fun theQueueButtonQueuesTheDraftWhileATurnRuns() {
        val calls = QueueCalls()
        var chat by mutableStateOf(Chat(streaming = "Working", busyMode = BusyMode.QUEUE))
        compose.setContent { ChatScreen(chat, onSend = {}, queueActions = calls.actions) }

        compose.onNodeWithTag("queue").assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("send").assertDoesNotExist()  // queue mode: Queue is the only send control (D12)
        compose.onNodeWithTag("stop").assertExists()
        compose.onNodeWithText("Queue message", useUnmergedTree = true).assertExists()  // the field says it too

        compose.onNodeWithTag("draft").performTextInput("later")
        compose.onNodeWithTag("queue").assertIsEnabled()
        compose.onNodeWithContentDescription("Queue message").assertExists()
        compose.onNodeWithTag("queue").performClick()

        assertEquals(listOf("later"), calls.added)
        compose.onNodeWithTag("draft").assert(!hasText("later", substring = true))

        chat = chat.copy(busyMode = BusyMode.INTERRUPT)  // Queue, then Send, then Stop, left to right
        val queue = compose.onNodeWithTag("queue").getUnclippedBoundsInRoot()
        val send = compose.onNodeWithTag("send").getUnclippedBoundsInRoot()
        val stop = compose.onNodeWithTag("stop").getUnclippedBoundsInRoot()
        assertTrue(queue.right <= send.left && send.right <= stop.left)

        chat = Chat()
        compose.onNodeWithTag("queue").assertDoesNotExist()
    }

    @Test
    fun aQueuedMessageIsEditedInPlace() {
        val calls = QueueCalls()
        var queue by mutableStateOf(twoQueued)
        compose.setContent { ChatScreen(Chat(streaming = "Working", busyMode = BusyMode.INTERRUPT), onSend = {}, queue = queue,
                                        queueActions = calls.actions) }

        compose.onAllNodesWithTag("queue-more")[0].performClick()
        compose.onNodeWithText("Edit").performClick()
        assertEquals(listOf<Long?>(0L), calls.edits)

        queue = queue.copy(editing = 0)
        compose.onNodeWithTag("queue-field").assert(hasText("first"))
        compose.onNodeWithTag("queue-field").performTextClearance()
        compose.onNodeWithTag("queue-field").performTextInput("first, shorter")
        compose.onNodeWithTag("queue-save").performClick()
        assertEquals(listOf(0L to "first, shorter"), calls.saves)

        compose.onNodeWithTag("queue-field").performTextClearance()
        compose.onNodeWithTag("queue-save").assertIsNotEnabled()  // a blank message cannot be saved

        compose.onNodeWithTag("queue-cancel").performClick()
        assertEquals(listOf<Long?>(0L, null), calls.edits)
    }

    @Test
    fun aCorrectionSaysWhatTheCoreDidWithIt() {
        val chat = Chat(listOf(Entry.User("hi", stableId = 0),
                               Entry.User("shorter", stableId = 1, mode = BusyMode.INTERRUPT, correction = Correction.REDIRECTED),
                               Entry.User("metric", stableId = 2, mode = BusyMode.STEER, correction = Correction.STEERED),
                               Entry.User("later", stableId = 3, mode = BusyMode.INTERRUPT, correction = Correction.QUEUED_BY_CORE)))

        compose.setContent { ChatScreen(chat, onSend = {}) }

        listOf("Redirected the current run", "Steered the current run", "Hermes will run this next").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(1)
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onAllNodesWithTag("correction").assertCountEquals(3)
    }

    @Test
    fun sendingHandsOverTheTrimmedDraftAndClearsIt() {
        val sent = mutableListOf<String>()
        compose.setContent { ChatScreen(Chat(), onSend = { sent += it }) }

        compose.onNodeWithTag("draft").performTextInput("  hello there ")
        compose.onNodeWithTag("send").performClick()

        assertEquals(listOf("hello there"), sent)
        compose.onNodeWithTag("draft").assert(!hasText("hello there", substring = true))
    }

    @Test
    fun nothingIsSentWhileATurnRuns() {
        // While a turn runs, Stop takes Send's place: a draft typed meanwhile waits for the turn to end.
        compose.setContent { ChatScreen(Chat(streaming = ""), onSend = {}) }

        compose.onNodeWithTag("draft").performTextInput("next")

        compose.onNodeWithTag("send").assertDoesNotExist()
        compose.onNodeWithTag("stop").assertExists()
    }

    @Test
    fun anIdleChatAcceptsADraft() {
        compose.setContent { ChatScreen(Chat(), onSend = {}) }
        compose.onNodeWithTag("send").assertIsNotEnabled()

        compose.onNodeWithTag("draft").performTextInput("hi")

        compose.onNodeWithTag("send").assertIsEnabled()
    }

    @Test
    fun aFailedReplyThatAlreadyExplainsItsErrorShowsItOnce() {
        val reply = Entry.Reply("The request failed.\nDetails: no provider", TurnStatus.ERROR, error = "no provider", stableId = 0)

        compose.setContent { ChatScreen(Chat(listOf(reply)), onSend = {}) }

        assertEquals(1, compose.onAllNodesWithText("no provider", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theDrawerHoldsTheAppsPlacesAndTheRuntimeLineOnOneLine() {
        val about = "Python 3.14.7 · SQLite 3.53.4 with FTS5 · ready in 1090 ms"
        val went = mutableListOf<String>()
        compose.setContent {
            ChatScreen(Chat(), onSend = {}, about = about,
                       places = ChatPlaces(setup = { went += "setup" }, terminal = { went += "ssh" }, gateway = { went += "gateway" },
                                           settings = { went += "settings" }, newChat = { went += "new" }, jobs = { went += "jobs" }))
        }

        compose.onNodeWithTag("open-drawer").performClick()

        val height = compose.onNodeWithText(about).getUnclippedBoundsInRoot().height
        assertTrue("the runtime line is $height tall: a column of words, not one caption line", height <= 24.dp)
        compose.onNodeWithTag("drawer-new-chat").performClick()
        // Each link snaps the drawer closed, so the next one is reached through the menu again (its scrim covers the bar).
        listOf("open-gateway", "open-jobs", "open-ssh", "open-settings").forEach {
            compose.onNodeWithTag("open-drawer").performClick()
            compose.onNodeWithTag(it).performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithTag("new-chat").performClick()
        compose.onNodeWithTag("open-setup").performClick()

        assertEquals(listOf("new", "gateway", "jobs", "ssh", "settings", "new", "setup"), went)  // every link opens its own place
    }

    @Test
    fun aPlaceLeftOutHasNoWayThere() {
        var places: ChatPlaces? by mutableStateOf(null)
        compose.setContent { ChatScreen(Chat(), onSend = {}, places = places) }

        // No places, no sessions, no runtime line: no drawer, no new chat, no model pill to tap.
        listOf("open-drawer", "new-chat", "open-setup").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }

        places = ChatPlaces(gateway = {})
        compose.onNodeWithTag("open-drawer").assertExists().performClick()
        compose.onNodeWithTag("open-gateway").assertExists()
        listOf("drawer-new-chat", "open-jobs", "open-ssh", "open-settings", "new-chat", "open-setup").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    @Test
    fun theDrawerListsTheSessionsAsItOpensAndATapContinuesOne() {
        val heard = mutableListOf<String>()
        val sessions = HistoryUi(listOf(SessionListRow(id = "db-1", title = "Calendar", messageCount = 3,
                                                       startedAt = System.currentTimeMillis() / 1000.0)))
        compose.setContent {
            ChatScreen(Chat(), onSend = {}, sessions = sessions, onSessions = { heard += "open=$it" }, onPickSession = { heard += it.value })
        }

        compose.onNodeWithTag("open-drawer").performClick()
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Calendar").performClick()
        compose.waitForIdle()

        assertEquals(listOf("open=true", "db-1", "open=false"), heard)
    }

    @Test
    fun anEmptyChatShowsTheWordmarkAndATranscriptReplacesIt() {
        var chat by mutableStateOf(Chat())
        compose.setContent { ChatScreen(chat, onSend = {}) }
        compose.onNodeWithTag("intro").assertExists()

        chat = Chat(listOf(Entry.User("hi", stableId = 0)))

        compose.onNodeWithTag("intro").assertDoesNotExist()
    }

    @Test
    fun theAgentsUnfinishedPlanShowsAboveTheComposerWhileItWorks() {
        val plan = TodoList(listOf(Todo("1", "Read the repo", TodoStatus.COMPLETED), Todo("2", "Run the tests", TodoStatus.IN_PROGRESS),
                                   Todo("3", "Fix failures", TodoStatus.PENDING, "2")), 5)
        val chat by mutableStateOf(Chat(listOf(Entry.User("tidy up", stableId = 0)), streaming = "", todos = plan))
        compose.setContent { ChatScreen(chat, onSend = {}) }

        compose.onNodeWithTag("todo-strip").assertExists()
        compose.onNodeWithText("Tasks 1/3").assertExists()
        compose.onNodeWithText("Run the tests").assertExists()  // the item the agent is on
        compose.onAllNodesWithTag("todo-item").assertCountEquals(0)  // collapsed to a count

        compose.onNodeWithTag("todo-toggle").performClick()

        compose.onAllNodesWithTag("todo-item").assertCountEquals(3)
        compose.onNodeWithText("Fix failures").assertExists()
    }

    @Test
    fun thePlanLeavesWhenTheTurnEndsOrNothingIsLeftToDo() {
        val active = TodoList(listOf(Todo("1", "Boil", TodoStatus.COMPLETED), Todo("2", "Pour", TodoStatus.PENDING)), 2)
        val resolved = TodoList(listOf(Todo("1", "Boil", TodoStatus.COMPLETED), Todo("2", "Pour", TodoStatus.CANCELLED)), 3)
        var chat by mutableStateOf(Chat(listOf(Entry.User("tea", stableId = 0)), streaming = "", todos = resolved))
        compose.setContent { ChatScreen(chat, onSend = {}) }
        compose.onNodeWithTag("todo-strip").assertDoesNotExist()  // running, nothing left to do

        chat = Chat(listOf(Entry.User("tea", stableId = 0), Entry.Reply("ok", TurnStatus.COMPLETE, stableId = 1)), todos = active)
        compose.onNodeWithTag("todo-strip").assertDoesNotExist()  // the turn ended on an unfinished plan (D1)

        chat = Chat(listOf(Entry.User("tea", stableId = 0)), streaming = "")
        compose.onNodeWithTag("todo-strip").assertDoesNotExist()  // running, no plan

        chat = Chat(listOf(Entry.User("tea", stableId = 0)), streaming = "", todos = active)
        compose.onNodeWithTag("todo-strip").assertExists()
    }

    @Test
    fun theComposerShowsHowFullTheContextIs() {
        var chat by mutableStateOf(Chat(meter = ContextMeter(53_800, 128_000, 42, true)))
        compose.setContent { ChatScreen(chat, onSend = {}) }

        compose.onNodeWithTag("context-meter").assert(hasText("~42%"))
            .assert(hasContentDescription("Context ~42% full, ~53.8k / 128k tokens"))

        chat = Chat()
        compose.onNodeWithTag("context-meter").assertDoesNotExist()

        chat = Chat(meter = ContextMeter(7000, 8000, 88, false))
        compose.onNodeWithTag("context-meter").assert(hasText("88%"))
    }

    @Test
    fun theComposerNamesTheModelAndOpensItsSetup() {
        var opened = 0
        compose.setContent { ChatScreen(Chat(model = "kimi-k2"), onSend = {}, places = ChatPlaces(setup = { opened++ })) }

        compose.onNodeWithText("kimi-k2").assertExists()
        compose.onNodeWithTag("open-setup").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theLastAnswerOffersRetryAndATapAsksForIt() {
        var taps = 0
        compose.setContent {
            ChatScreen(Chat(listOf(Entry.User("hi", 1, 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1))), onSend = {}, onRetry = { taps++ })
        }

        compose.onNodeWithTag("retry").performClick()

        assertEquals(1, taps)
        compose.onNodeWithContentDescription("Retry").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()  // under an ordinary answer: the icon alone
    }

    @Test
    fun aFailedTurnOffersRetryInWords() {
        var chat by mutableStateOf(Chat(listOf(Entry.User("hi", stableId = 0), Entry.Notice("Provider unavailable", stableId = 1))))
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = {}) }

        compose.onNodeWithText("Retry").assertExists()
        chat = Chat(listOf(Entry.User("hi", stableId = 0), Entry.Reply("", TurnStatus.ERROR, error = "timed out", stableId = 2)))
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun noRetryWhileATurnRunsBeforeAnAnswerOrWithoutAHandler() {
        var chat by mutableStateOf(Chat(listOf(Entry.User("hi", stableId = 0)), streaming = "He"))
        var onRetry by mutableStateOf<(() -> Unit)?>({})
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = onRetry) }

        compose.onNodeWithTag("retry").assertDoesNotExist()  // running
        chat = Chat(listOf(Entry.User("hi", stableId = 0)))
        compose.onNodeWithTag("retry").assertDoesNotExist()  // sent, no answer yet
        chat = Chat(listOf(Entry.User("hi", stableId = 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1)))
        onRetry = null
        compose.onNodeWithTag("retry").assertDoesNotExist()  // no handler
    }

    private val meter = ContextMeter(53_800, 128_000, 42, true)
    private val breakdown = SessionContextBreakdownResult(
        listOf(ContextCategory("var(--context-usage-system)", "system_prompt", "System prompt", 12_000),
               ContextCategory("var(--context-usage-conversation)", "conversation", "Conversation", 41_800)),
        contextMax = 128_000, contextPercent = 42, contextUsed = 53_800, estimatedTotal = 53_800, contextEstimated = true,
        contextSource = "estimate", model = "gemma4")
    private val answered = Chat(listOf(Entry.User("hi", stableId = 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 1)), meter = meter)

    @Test
    fun theMeterOpensTheContextSheet() {
        var opened = 0
        var actions: ContextActions? by mutableStateOf(null)
        compose.setContent { ChatScreen(answered, onSend = {}, contextActions = actions) }

        compose.onNodeWithTag("context-meter").assertHasNoClickAction()  // a plain readout without a sheet to open

        actions = ContextActions(open = { opened++ }, compress = {}, close = {})
        compose.onNodeWithTag("context-meter").assertHasClickAction().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theSheetShowsWhatFillsTheContext() {
        var context: ContextUi? by mutableStateOf(ContextUi(meter, breakdown))
        compose.setContent { ChatScreen(answered, onSend = {}, context = context) }

        compose.onNodeWithTag("context-panel").assertExists()
        listOf("Context usage", "~53.8k / 128k tokens", "~42% full", "System prompt", "~12k", "Conversation", "~41.8k")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onAllNodesWithTag("context-category").assertCountEquals(2)

        context = ContextUi(meter)
        compose.onNodeWithText("Loading breakdown…").assertExists()

        context = ContextUi(meter, breakdown.copy(categories = emptyList()))
        compose.onNodeWithText("No context data yet").assertExists()

        context = ContextUi(meter, error = "Could not compute context breakdown: boom")
        compose.onNodeWithTag("context-error").assertTextEquals("Could not compute context breakdown: boom")

        context = null
        compose.onNodeWithTag("context-panel").assertDoesNotExist()
    }

    @Test
    fun compressSendsTheFocusAndWaitsWhileItRuns() {
        var chat by mutableStateOf(answered)
        var context by mutableStateOf(ContextUi(meter, breakdown))
        val focuses = mutableListOf<String?>()
        compose.setContent { ChatScreen(chat, onSend = {}, context = context, contextActions = ContextActions(open = {}, compress = { focuses += it }, close = {})) }

        compose.onNodeWithTag("context-focus").performScrollTo().performTextInput("here 1")
        compose.onNodeWithTag("compress").performScrollTo().performClick()
        assertEquals(listOf<String?>("here 1"), focuses)

        compose.onNodeWithTag("context-focus").performTextClearance()
        compose.onNodeWithTag("compress").performScrollTo().performClick()
        assertEquals(null, focuses[1])  // a blank focus is none

        chat = chat.copy(compressing = true)  // the chat's session compresses (invariant 6)
        compose.onNodeWithTag("compress").assertIsNotEnabled()
        compose.onNodeWithTag("context-compressing").assertExists()

        chat = chat.copy(compressing = false)
        context = ContextUi(meter, breakdown, outcome = "Compressed: 6 → 2 messages\nApprox request size: ~9,000 → ~2,100 tokens")
        compose.onNodeWithTag("context-outcome").assert(hasText("Compressed: 6 → 2 messages", substring = true))
        compose.onNodeWithTag("compress").assertIsEnabled()

        chat = chat.copy(streaming = "")  // a reply is being written: Compress waits
        compose.onNodeWithTag("compress").assertIsNotEnabled()
    }

    @Test
    fun aPromptOnItsWayShowsStopAndHoldsRetryAndCompress() {
        var chat by mutableStateOf(Chat(listOf(Entry.User("hi", stableId = 0), Entry.Notice("x", stableId = 1)), submitted = true))
        var stopped = 0
        compose.setContent { ChatScreen(chat, onSend = {}, onStop = { stopped++ }, onRetry = {}, context = ContextUi(meter, breakdown)) }

        compose.onNodeWithTag("send").assertDoesNotExist()  // Stop stands where Send was from the tap on
        compose.onNodeWithTag("stop").performClick()
        assertEquals(1, stopped)
        compose.onNodeWithTag("retry").assertDoesNotExist()
        compose.onNodeWithTag("compress").performScrollTo().assertIsNotEnabled()

        chat = chat.unsent()  // refused, or never reached the core: Send comes back
        compose.onNodeWithTag("send").assertExists()
        compose.onNodeWithTag("retry").assertExists()
        compose.onNodeWithTag("compress").performScrollTo().assertIsEnabled()
    }

    @Test
    fun aCompressionHoldsSendAndRetry() {
        var chat by mutableStateOf(answered.copy(compressing = true))
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = {}) }

        compose.onNodeWithTag("draft").performTextInput("next")
        compose.onNodeWithTag("send").assertExists().assertIsNotEnabled()  // Send waits; Stop does not end a compression (V2)
        compose.onNodeWithTag("stop").assertDoesNotExist()
        compose.onNodeWithTag("retry").assertDoesNotExist()

        chat = chat.copy(compressing = false)
        compose.onNodeWithTag("send").assertIsEnabled()
        compose.onNodeWithTag("retry").assertExists()
    }

    @Test
    fun anOwnedRunCanBeReadButNotWritten() {
        val words = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_run_owned)
        var chat by mutableStateOf(answered.copy(runOwned = true))
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = {}) }

        compose.onNodeWithTag("run-owned").assertExists().assertTextEquals(words)
        compose.onNodeWithTag("draft").performTextInput("next")
        compose.onNodeWithTag("send").assertExists().assertIsNotEnabled()  // the draft stays; Send waits (V5)
        compose.onNodeWithTag("retry").assertDoesNotExist()

        chat = chat.copy(runOwned = false)
        compose.onNodeWithTag("run-owned").assertDoesNotExist()
        compose.onNodeWithTag("send").assertIsEnabled()
        compose.onNodeWithTag("retry").assertExists()
    }

    @Test
    fun theSheetClosesOnBackButNotWhileCompressing() {
        var chat by mutableStateOf(answered.copy(compressing = true))
        var context: ContextUi? by mutableStateOf(ContextUi(meter, breakdown))
        var closes = 0
        compose.setContent { ChatScreen(chat, onSend = {}, context = context,
                                        contextActions = ContextActions(open = {}, compress = {}, close = { closes++; context = null })) }
        compose.onNodeWithTag("context-panel").assertExists()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation

        ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("context-panel").assertExists()
        assertEquals(0, closes)

        chat = chat.copy(compressing = false)
        compose.waitForIdle()
        ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        compose.waitUntil(5_000) { closes == 1 }
        compose.onNodeWithTag("context-panel").assertDoesNotExist()
    }

    // chat-17: the transcript follows a streaming reply only while the reader is at its end.
    private val earlier = (1..6).flatMap {
        listOf(Entry.User("Question $it", stableId = 2L * it), Entry.Reply("Answer $it", TurnStatus.COMPLETE, stableId = 2L * it + 1))
    }
    private fun reply(lines: Int) = (1..lines).joinToString("\n\n") { "Line $it of a long reply." }  // paragraphs: one per line
    /** The transcript's position: the lazy list's estimate, equal to its maximum exactly when nothing is below (1.12.1). */
    private fun position(): ScrollAxisRange =
        compose.onNodeWithTag("transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    private fun atTheEnd() = position().let { it.value() == it.maxValue() }
    private fun readBack() { compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }; compose.waitForIdle() }
    private fun streamingChat() = Chat(earlier + Entry.User("Tell me a lot", stableId = 100), streaming = reply(20))

    @Test
    fun aLongStreamingReplyIsFollowedToItsNewestLine() {
        var chat by mutableStateOf(streamingChat())
        compose.setContent { ChatScreen(chat, onSend = {}) }
        compose.waitForIdle()
        assertTrue("a reply taller than the screen is followed to its newest line", atTheEnd())
        compose.onNodeWithTag("jump-latest").assertDoesNotExist()

        for (grown in listOf(chat.copy(streaming = reply(40)), chat.copy(streaming = reply(60)))) {
            chat = grown
            compose.waitForIdle()
            assertTrue("followed as the reply grows", atTheEnd())
        }
        chat = chat.copy(reasoning = "Weighing it")
        compose.waitForIdle()
        assertTrue("followed as the reasoning streams", atTheEnd())
        chat = Chat(chat.entries + Entry.Reply(reply(60), TurnStatus.COMPLETE, stableId = 101))
        compose.waitForIdle()
        assertTrue("followed as the reply completes", atTheEnd())
    }

    @Test
    fun readingBackWhileAReplyStreamsKeepsThePlace() {
        var chat by mutableStateOf(streamingChat())
        compose.setContent { ChatScreen(chat, onSend = {}) }
        compose.waitForIdle()

        readBack()
        val kept = position().value()
        assertFalse(atTheEnd())
        compose.onNodeWithTag("jump-latest").assertExists()
        for (lines in listOf(40, 60)) {
            chat = chat.copy(streaming = reply(lines))
            compose.waitForIdle()
            assertEquals("the reader's place is kept while the reply grows", kept, position().value())
            compose.onNodeWithTag("jump-latest").assertExists()
        }
    }

    @Test
    fun jumpToLatestFollowsTheReplyAgain() {
        var chat by mutableStateOf(streamingChat())
        compose.setContent { ChatScreen(chat, onSend = {}) }
        compose.waitForIdle()

        readBack()
        compose.onNodeWithContentDescription("Scroll to bottom").performClick()
        compose.waitForIdle()
        assertTrue(atTheEnd())
        compose.onNodeWithTag("jump-latest").assertDoesNotExist()
        chat = chat.copy(streaming = reply(60))
        compose.waitForIdle()
        assertTrue("following again", atTheEnd())
    }

    @Test
    fun scrollingBackToTheEndByHandFollowsAgain() {
        var chat by mutableStateOf(streamingChat())
        compose.setContent { ChatScreen(chat, onSend = {}) }
        compose.waitForIdle()

        readBack()
        repeat(12) {
            if (!atTheEnd()) { compose.onNodeWithTag("transcript").performTouchInput { swipeUp() }; compose.waitForIdle() }
        }
        assertTrue(atTheEnd())
        compose.onNodeWithTag("jump-latest").assertDoesNotExist()
        chat = chat.copy(streaming = reply(60))
        compose.waitForIdle()
        assertTrue("following again", atTheEnd())
    }

    @Test
    fun aConversationPickedInTheDrawerIsShownFromItsEnd() {
        var chat by mutableStateOf(streamingChat())
        val other = Chat((1..30).flatMap {
            listOf(Entry.User("Other $it", stableId = 1000L + 2 * it), Entry.Reply("Reply $it", TurnStatus.COMPLETE, stableId = 1001L + 2 * it))
        })
        val sessions = HistoryUi(listOf(SessionListRow(id = "db-2", title = "Other", messageCount = 60,
                                                       startedAt = System.currentTimeMillis() / 1000.0)))
        compose.setContent { ChatScreen(chat, onSend = {}, sessions = sessions, onPickSession = { chat = other }) }
        compose.waitForIdle()

        readBack()
        compose.onNodeWithTag("jump-latest").assertExists()
        compose.onNodeWithTag("open-drawer").performClick()
        compose.onNodeWithText("Other").performClick()
        compose.waitForIdle()
        assertTrue("a picked conversation is shown from its end", atTheEnd())
        compose.onNodeWithTag("jump-latest").assertDoesNotExist()
    }

    @Test
    fun aFinishedAnswerIsCopiedWholeAsItsMarkdown() {
        val copies = mutableListOf<String>()
        compose.setContent {
            ChatScreen(Chat(listOf(Entry.User("hi", stableId = 0), Entry.Reply("**Bold** and `code`", TurnStatus.COMPLETE, stableId = 1))), onSend = {},
                       onCopy = { copies += it })
        }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("copy-reply").performClick()
        compose.mainClock.advanceTimeByFrame()

        assertEquals(listOf("**Bold** and `code`"), copies)
        compose.onNodeWithContentDescription("Copied").assertExists()
        compose.mainClock.advanceTimeBy(1_600)
        compose.onNodeWithContentDescription("Copy").assertExists()
    }

    @Test
    fun theLastAnswersCopyAndRetrySitInOneRow() {
        compose.setContent {
            ChatScreen(Chat(listOf(Entry.User("a", 1, 0), Entry.Reply("One", TurnStatus.COMPLETE, stableId = 1), Entry.User("b", 2, 2),
                                   Entry.Reply("Two", TurnStatus.COMPLETE, stableId = 3))), onSend = {}, onRetry = {}, onCopy = {})
        }

        compose.onAllNodesWithTag("copy-reply").assertCountEquals(2)
        compose.onAllNodesWithTag("retry").assertCountEquals(1)
        val c = compose.onAllNodesWithTag("copy-reply")[1].getUnclippedBoundsInRoot()
        val r = compose.onNodeWithTag("retry").getUnclippedBoundsInRoot()
        assertEquals(c.top, r.top)
        assertTrue("Copy left of Retry", c.right <= r.left)
    }

    @Test
    fun noCopyWhileAReplyStreamsForAnEmptyAnswerOrWithoutAHandler() {
        var chat by mutableStateOf(Chat(listOf(Entry.User("hi", stableId = 0)), streaming = "He"))
        var onCopy by mutableStateOf<((String) -> Unit)?>({})
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = {}, onCopy = onCopy) }
        compose.waitForIdle()
        compose.onNodeWithTag("copy-reply").assertDoesNotExist()

        chat = Chat(listOf(Entry.User("hi", stableId = 0), Entry.Reply("", TurnStatus.ERROR, error = "timed out", stableId = 1)))
        compose.waitForIdle()
        compose.onNodeWithTag("copy-reply").assertDoesNotExist()
        compose.onNodeWithTag("retry").assertExists()

        chat = Chat(listOf(Entry.User("hi", stableId = 0), Entry.Reply("Hello", TurnStatus.COMPLETE, stableId = 2)))
        onCopy = null
        compose.waitForIdle()
        compose.onNodeWithTag("copy-reply").assertDoesNotExist()
    }

    @Test
    fun aLongPressOnYourMessageCopiesIt() {
        val copies = mutableListOf<String>()
        compose.setContent { ChatScreen(Chat(listOf(Entry.User("ls -la ~/notes", stableId = 0))), onSend = {}, onCopy = { copies += it }) }

        compose.onNodeWithText("ls -la ~/notes").performTouchInput { longClick() }
        compose.onNodeWithTag("copy-message").performClick()

        assertEquals(listOf("ls -la ~/notes"), copies)
        compose.onNodeWithTag("copy-message").assertDoesNotExist()
    }

    private val kotlinReply = Entry.Reply("Run:\n\n```kotlin\nval x = \"hi\" // note\n```", TurnStatus.COMPLETE, stableId = 0)

    @Test
    fun aCodeBlockIsColouredByItsLanguage() {
        compose.setContent { ChatScreen(Chat(listOf(kotlinReply)), onSend = {}) }

        val spans = compose.onNodeWithText("val x = \"hi\" // note").fetchSemanticsNode()
            .config[SemanticsProperties.Text].first().spanStyles
        assertTrue("val", spans.any { it.start == 0 && it.end == 3 && it.item.color == Color(0xFFCF222E) })
        assertTrue("string", spans.any { it.item.color == Color(0xFF0A3069) })
        assertTrue("comment", spans.any { it.item.color == Color(0xFF57606A) })
    }

    @Test
    fun aCodeBlocksCopyHandsOverTheCodeAlone() {
        val copies = mutableListOf<String>()
        compose.setContent { ChatScreen(Chat(listOf(kotlinReply)), onSend = {}, onCopy = { copies += it }) }

        compose.onNodeWithContentDescription("Copy code").assertExists()
        compose.onNodeWithTag("copy-code").performClick()
        assertEquals(listOf("val x = \"hi\" // note"), copies)

        compose.onNodeWithTag("copy-reply").performClick()
        assertEquals(listOf("val x = \"hi\" // note", kotlinReply.text), copies)
    }

    @Test
    fun theJobNoticeOpensTheGatewayPage() {
        val went = mutableListOf<String>()
        compose.setContent {
            ChatScreen(Chat(listOf(Entry.User("every morning at 8, summarise the news", stableId = 0),
                                   Entry.Tool("t1", "cronjob_manage", done = true, stableId = 1),
                                   Entry.Notice(ChatSession.JOBS_NEED_GATEWAY, NoticeShortcut.GATEWAY, 2))),
                       onSend = {}, places = ChatPlaces(gateway = { went += "gateway" }))
        }

        compose.onNodeWithText(ChatSession.JOBS_NEED_GATEWAY).assertIsDisplayed()
        compose.onNodeWithTag("notice-open-gateway").assertIsDisplayed().assertTextEquals("Turn on the gateway").performClick()

        assertEquals(listOf("gateway"), went)
        compose.onNodeWithTag("open-gateway").assertIsNotDisplayed()  // straight to the page, the drawer left closed
    }

    @Test
    fun aNoticeOffersOnlyTheWayItNames() {
        var chat by mutableStateOf(Chat(listOf(Entry.Notice("session busy", stableId = 0))))
        var places by mutableStateOf<ChatPlaces?>(ChatPlaces(gateway = {}))
        compose.setContent { ChatScreen(chat, onSend = {}, places = places) }

        compose.onNodeWithText("session busy").assertIsDisplayed()
        compose.onNodeWithTag("notice-open-gateway").assertDoesNotExist()  // a plain notice is words only

        places = null
        chat = Chat(listOf(Entry.Notice(ChatSession.JOBS_NEED_GATEWAY, NoticeShortcut.GATEWAY, 1)))
        compose.onNodeWithText("drawer → Gateway", substring = true).assertIsDisplayed()  // no place: the words name the way
        compose.onNodeWithTag("notice-open-gateway").assertDoesNotExist()

        places = ChatPlaces(gateway = {})
        compose.onNodeWithTag("notice-open-gateway").assertIsDisplayed()
    }

    private fun frame(type: String, json: String? = null) = CoreEvent(type, "s1", json?.let { Json.parseToJsonElement(it) })
    private fun Chat.answered(text: String, thought: String) =
        reduce(frame("message.start")).reduce(frame("message.complete", """{"text":"$text","status":"complete","reasoning":"$thought"}"""))

    /** /improve 20261113T221531Z #8: a row's state is its entry's, not its place's; Retry's new answer at the old one's index starts folded. */
    @Test
    fun aRetriedAnswerOpensWithItsReasoningFolded() {
        var chat by mutableStateOf(Chat().sent("a").answered("One.", "First thought").sent("b").answered("Two.", "Counted twice"))
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = { chat = chat.rewound(checkNotNull(chat.retryTarget())).sent("b") }) }
        compose.onAllNodesWithTag("reasoning-toggle").assertCountEquals(2)
        compose.onAllNodesWithTag("reasoning-toggle")[1].performClick()          // the last answer's fold, opened
        compose.onNodeWithText("Counted twice", substring = true).assertExists()

        compose.onNodeWithTag("retry").performClick()                            // Retry rewinds: [a, One.] and b, on its way
        compose.waitForIdle()
        chat = chat.reduce(frame("message.start"))                                // the streaming row stands where the answer was
        compose.waitForIdle()
        chat = chat.reduce(frame("message.complete", """{"text":"Two again.","status":"complete","reasoning":"Counted again"}"""))
        compose.waitForIdle()

        compose.onNodeWithText("Two again.", substring = true).assertExists()
        compose.onNodeWithText("Counted again", substring = true).assertDoesNotExist()  // the new answer's own fold: closed
        compose.onNodeWithText("First thought", substring = true).assertDoesNotExist()  // the earlier one as it was left
        compose.onAllNodesWithTag("reasoning-toggle")[1].performClick()
        compose.onNodeWithText("Counted again", substring = true).assertExists()        // and it opens on its own tap
    }

    @Test
    fun aSharedTextLandsInTheDraftUnsentWithNewChatOneTapAway() {
        val offer = IncomingText("from Chrome")
        val sent = mutableListOf<String>()
        val taken = mutableListOf<IncomingText>()
        var newChats = 0
        compose.setContent {
            ChatScreen(Chat(offer = offer), onSend = { sent += it }, onOfferTaken = { taken += it },
                       places = ChatPlaces(newChat = { newChats++ }))
        }

        compose.onNodeWithTag("draft").assert(hasText("from Chrome"))
        compose.runOnIdle { assertEquals(listOf(offer), taken) }  // taken once
        assertEquals(emptyList<String>(), sent)  // bet 8: never sent unseen
        compose.onNodeWithTag("offer").assertIsDisplayed()

        compose.onNodeWithTag("offer-new-chat").performClick()
        assertEquals(1, newChats)
        compose.onNodeWithTag("draft").assert(hasText("from Chrome"))  // the words move with the user

        compose.onNodeWithTag("send").performClick()
        assertEquals(listOf("from Chrome"), sent)
        compose.onNodeWithTag("offer").assertDoesNotExist()  // the strip leaves with the words
    }

    @Test
    fun aSharedTextJoinsTheWordsTheUserTyped() {
        var chat by mutableStateOf(Chat())
        compose.setContent { ChatScreen(chat, onSend = {}) }

        compose.onNodeWithTag("draft").performTextInput("my words")
        chat = Chat(offer = IncomingText("shared"))

        compose.onNodeWithTag("draft").assert(hasText("my words\n\nshared"))  // D6: the user's own words are never lost
    }

    @Test
    fun aCutSharedTextSaysSo() {
        compose.setContent { ChatScreen(Chat(offer = IncomingText("x", cut = true)), onSend = {}) }

        val words = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.offer_cut)
        compose.onNodeWithTag("offer").assert(hasAnyDescendant(hasText(words)))
    }

    // The model pill says whether the provider will answer (F6): a dot, a word when not ready, the core's words on a tap.

    private fun stateIs(word: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, word)

    @Test
    fun thePillSaysWhetherTheProviderWillAnswer() {
        var readiness by mutableStateOf<Readiness>(Readiness.Checking)
        compose.setContent { ChatScreen(Chat(model = "kimi-k2"), onSend = {}, places = ChatPlaces(setup = {}), readiness = readiness) }

        compose.onNodeWithTag("open-setup").assert(stateIs("checking"))
        compose.onNodeWithTag("readiness-dot", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("readiness-label", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("kimi-k2", useUnmergedTree = true).assertExists()

        readiness = Readiness.Ready("deepseek", "m")
        compose.onNodeWithTag("open-setup").assert(stateIs("ready"))
        compose.onNodeWithTag("readiness-dot", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("readiness-label", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("kimi-k2", useUnmergedTree = true).assertExists()

        for ((state, word) in listOf(Readiness.NeedsSetup("x") to "needs setup", Readiness.Unavailable("x") to "inference unavailable",
                                     Readiness.Unknown to "unknown")) {
            readiness = state
            compose.onNodeWithTag("open-setup").assert(stateIs(word))
            compose.onNodeWithTag("readiness-label", useUnmergedTree = true).assertTextEquals(word)
            compose.onNodeWithText("kimi-k2", useUnmergedTree = true).assertExists()
        }
        // A check that did not finish is never said as a "no" (DC-M11).
        compose.onNodeWithText("inference unavailable", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aPillThatIsNotReadyShowsTheCoresWordsOnATap() {
        var opened = 0
        var checked = 0
        var readiness by mutableStateOf<Readiness>(Readiness.Unavailable("No usable credentials found for deepseek."))
        compose.setContent {
            ChatScreen(Chat(model = "kimi-k2"), onSend = {}, places = ChatPlaces(setup = { opened++ }), readiness = readiness,
                       onCheckReadiness = { checked++ })
        }

        compose.onNodeWithTag("open-setup").performClick()
        assertEquals(0, opened)
        compose.onNodeWithTag("readiness-words").assertTextEquals("No usable credentials found for deepseek.")

        compose.onNodeWithTag("readiness-check").performClick()
        assertEquals(1, checked)
        compose.onNodeWithTag("readiness-words").assertDoesNotExist()

        compose.onNodeWithTag("open-setup").performClick()
        compose.onNodeWithTag("readiness-setup").performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag("readiness-words").assertDoesNotExist()

        readiness = Readiness.Unknown
        compose.onNodeWithTag("open-setup").performClick()
        val unknown = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_ready_unknown_words)
        compose.onNodeWithTag("readiness-words").assertTextEquals(unknown)
        assertEquals(1, opened)
    }

    @Test
    fun aReadyPillOpensSetupAtOnce() {
        var opened = 0
        compose.setContent {
            ChatScreen(Chat(model = "kimi-k2"), onSend = {}, places = ChatPlaces(setup = { opened++ }),
                       readiness = Readiness.Ready("deepseek", "deepseek-v4-pro"), onCheckReadiness = {})
        }

        compose.onNodeWithTag("open-setup").performClick()

        assertEquals(1, opened)
        compose.onNodeWithTag("readiness-words").assertDoesNotExist()
    }

    @Test
    fun aReadyPillOpensThisChatsModelPickerAndItsAddProviderOpensSetup() {
        var opened = 0
        var setups = 0
        var ui by mutableStateOf<PickerUi?>(null)
        var readiness by mutableStateOf<Readiness>(Readiness.Ready("deepseek", "m"))
        val actions = ModelActions(open = { opened++ }, close = {}, query = {}, addCustom = {}, pick = {}, confirm = {}, keep = {},
                                   addProvider = { setups++ })
        compose.setContent {
            ChatScreen(Chat(model = "kimi-k2"), onSend = {}, places = ChatPlaces(setup = { setups++ }), readiness = readiness,
                       models = ui, modelActions = actions)
        }

        compose.onNodeWithTag("open-setup").performClick()
        assertEquals("a ready pill opens this chat's picker, not setup", 1, opened)
        assertEquals(0, setups)
        compose.onNodeWithTag("models-sheet").assertDoesNotExist()

        readiness = Readiness.Unavailable("No usable credentials found for deepseek.")
        compose.onNodeWithTag("open-setup").performClick()
        compose.onNodeWithTag("readiness-words").assertTextEquals("No usable credentials found for deepseek.")
        assertEquals("not ready: the core's words first", 1, opened)
        assertEquals(0, setups)
        compose.onNodeWithTag("readiness-setup").performClick()
        assertEquals("the words' Setup still opens setup", 1, setups)

        readiness = Readiness.Ready("deepseek", "m")
        ui = PickerUi(catalog = ModelCatalog(listOf(ModelRow("deepseek", "DeepSeek", "deepseek-v4-pro", current = true))))
        compose.onNodeWithTag("models-sheet").assertExists()
        compose.onNodeWithTag("models-add-provider").performClick()
        assertEquals(2, setups)
    }

    // The out-of-credits wall above the composer: one way to act and Dismiss, the composer still usable (models-23).

    private fun words(id: Int, vararg args: Any): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private val deepseekWords = "DeepSeek reported that billing, credits, or account entitlement is exhausted for deepseek-v4-pro."

    @Test
    fun aProvidersWallOffersItsBillingPageAndDismiss() {
        val opened = mutableListOf<String>()
        var setups = 0
        var dismissed = 0
        var chat by mutableStateOf(Chat(model = "kimi-k2", wall = BillingWall("deepseek", "DeepSeek", false,
                                                                               "https://platform.deepseek.com/top_up", deepseekWords)))
        compose.setContent {
            ChatScreen(chat, onSend = {}, places = ChatPlaces(setup = { setups++ }), onOpenUrl = { opened += it },
                       onDismissWall = { dismissed++ })
        }

        compose.onNodeWithTag("billing-title", useUnmergedTree = true).assertTextEquals(words(R.string.billing_title_provider, "DeepSeek"))
        compose.onNodeWithTag("billing-message", useUnmergedTree = true).assertTextEquals(deepseekWords)
        compose.onNodeWithTag("billing-action").assertTextEquals(words(R.string.billing_add)).performClick()
        assertEquals(listOf("https://platform.deepseek.com/top_up"), opened)
        assertEquals(0, setups)

        compose.onNodeWithTag("draft").performTextInput("hi")
        compose.onNodeWithTag("send").assertIsEnabled()  // the wall never stops the composer

        compose.onNodeWithContentDescription(words(R.string.billing_dismiss)).assertExists()  // TalkBack reads "Dismiss"
        compose.onNodeWithTag("billing-dismiss").performClick()
        assertEquals(1, dismissed)

        chat = chat.copy(wall = null)
        compose.onNodeWithTag("billing-wall").assertDoesNotExist()
    }

    @Test
    fun aNousWallOpensThePortalsBilling() {
        val opened = mutableListOf<String>()
        compose.setContent {
            ChatScreen(Chat(wall = BillingWall("nous", "Nous Portal", true, "https://portal.nousresearch.com/billing", BillingWall.FALLBACK)),
                       onSend = {}, places = ChatPlaces(setup = {}), onOpenUrl = { opened += it })
        }

        compose.onNodeWithTag("billing-title", useUnmergedTree = true).assertTextEquals("Out of Nous credits")
        compose.onNodeWithTag("billing-message", useUnmergedTree = true).assertTextEquals(BillingWall.FALLBACK)
        compose.onNodeWithTag("billing-action").assertTextEquals("Open billing").performClick()
        assertEquals(listOf("https://portal.nousresearch.com/billing"), opened)
    }

    @Test
    fun aWallWithoutAPageOpensProviderSetup() {
        val opened = mutableListOf<String>()
        var setups = 0
        var places by mutableStateOf<ChatPlaces?>(ChatPlaces(setup = { setups++ }))
        compose.setContent {
            ChatScreen(Chat(wall = BillingWall("acme", "Acme", false, null, "Out.")), onSend = {}, places = places,
                       onOpenUrl = { opened += it })
        }

        compose.onNodeWithTag("billing-action").assertTextEquals(words(R.string.setup_title)).performClick()
        assertEquals(1, setups)
        assertEquals(emptyList<String>(), opened)

        places = null  // nowhere to act: no button, Dismiss still there
        compose.onNodeWithTag("billing-action").assertDoesNotExist()
        compose.onNodeWithTag("billing-dismiss").assertExists()
    }

    @Test
    fun theChatsMenuOpensStatusAndExportsTheChat() {
        var opened = 0
        var exported = 0
        compose.setContent {
            ChatScreen(Chat(), onSend = {}, exportActions = ExportActions(export = { exported++ }, dismiss = {}),
                       statusActions = StatusActions(open = { opened++ }, close = {}))
        }

        compose.onNodeWithTag("chat-more").performClick()
        compose.onNodeWithTag("chat-status").performClick()
        assertEquals(1 to 0, opened to exported)
        compose.onNodeWithTag("chat-status").assertDoesNotExist()  // the menu closed first

        compose.onNodeWithTag("chat-more").performClick()
        compose.onNodeWithTag("chat-export").performClick()
        assertEquals(1 to 1, opened to exported)
    }

    @Test
    fun withoutTheirActionsTheTopBarHasNoMenu() {
        compose.setContent { ChatScreen(Chat(), onSend = {}, places = ChatPlaces(newChat = {})) }

        compose.onNodeWithTag("new-chat").assertExists()
        compose.onNodeWithTag("chat-more").assertDoesNotExist()
    }

    @Test
    fun theStatusSheetShowsTheCoresTextAsItIs() {
        val text = "Hermes TUI Status\n\nSession ID: s1\nModel: (unknown) (unknown)"
        var status: StatusUi? by mutableStateOf(StatusUi(text = text))
        var closed = 0
        compose.setContent { ChatScreen(Chat(), onSend = {}, status = status, statusActions = StatusActions(open = {}, close = { closed++ })) }

        compose.onNodeWithTag("status-sheet").assertExists()
        compose.onNodeWithText("Session status").assertExists()
        compose.onNodeWithTag("status-text").assertTextEquals(text)

        status = StatusUi()
        compose.onNodeWithTag("status-loading").assertExists()
        compose.onNodeWithTag("status-text").assertDoesNotExist()

        status = StatusUi(error = "x")
        compose.onNodeWithTag("status-error").assertTextEquals("x")

        status = null
        compose.onNodeWithTag("status-sheet").assertDoesNotExist()
        assertEquals(0, closed)
    }

    @Test
    fun aFailedExportSaysSoInItsWordsUntilDismissed() {
        var dismissed = 0
        compose.setContent {
            ChatScreen(Chat(), onSend = {}, exportUi = ExportUi(error = "Nothing to export yet: Hermes has not saved this conversation."),
                       exportActions = ExportActions(export = {}, dismiss = { dismissed++ }))
        }

        compose.onNodeWithTag("export-error").assertExists()
        compose.onNodeWithText("Could not export session").assertExists()
        compose.onNodeWithText("Nothing to export yet: Hermes has not saved this conversation.").assertExists()
        compose.onNodeWithTag("export-ok").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun whileAnExportRunsTheChatsExportWaits() {
        var exported = 0
        compose.setContent {
            ChatScreen(Chat(), onSend = {}, exportUi = ExportUi(busy = true), exportActions = ExportActions(export = { exported++ }, dismiss = {}),
                       statusActions = StatusActions(open = {}, close = {}))
        }

        compose.onNodeWithTag("export-busy").assertExists()
        compose.onNodeWithTag("chat-more").performClick()
        compose.onNodeWithTag("chat-export").assertIsNotEnabled()
        compose.onNodeWithTag("chat-status").assertIsEnabled()
        assertEquals(0, exported)
    }

    /** The core's real `inline_diff` for an edit: ANSI colour on every line under its review header (P4 D4). */
    private val coreDiff = "  ┊ review diff\n\u001b[38;2;180;160;255ma/a.txt → b/a.txt\u001b[0m\n" +
        "\u001b[38;2;120;120;140m@@ -1,2 +1,2 @@\u001b[0m\n\u001b[38;2;255;255;255;48;2;120;20;20m-old\u001b[0m\n" +
        "\u001b[38;2;255;255;255;48;2;20;90;20m+new\u001b[0m\n\u001b[38;2;150;150;150m same\u001b[0m"

    @Test
    fun aToolRowOpensIntoItsCommandOutputAndPayloadAndEachCopies() {
        val copies = mutableListOf<String>()
        val tool = Entry.Tool("t1", "terminal", done = true, stableId = 1, args = buildJsonObject { put("command", "ls -la") },
                              result = """{"output":"a.txt\nb.txt","exit_code":0,"error":null}""", durationS = 0.42)
        compose.setContent { ChatScreen(Chat(listOf(Entry.User("list", stableId = 0), tool)), onSend = {}, onCopy = { copies += it }) }

        // Collapsed: one line with how long it took, nothing of what it printed.
        compose.onNodeWithTag("tool-duration", useUnmergedTree = true).assertTextEquals("420ms")
        compose.onNodeWithTag("tool-detail").assertDoesNotExist()
        compose.onNodeWithText("a.txt", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("tool-row").assertHasClickAction()

        compose.onNodeWithTag("tool-row").performClick()
        compose.onNodeWithTag("tool-command").assert(hasText("ls -la", substring = true))
        compose.onNodeWithText("exit 0").assertExists()
        compose.onNodeWithTag("tool-output").assertTextEquals("a.txt\nb.txt")
        compose.onNodeWithTag("tool-payload").assertDoesNotExist()  // behind its disclosure (DC-P6)

        compose.onNodeWithTag("copy-command").performClick()
        compose.onNodeWithTag("copy-output").performClick()
        assertEquals(listOf("ls -la", "a.txt\nb.txt"), copies)

        compose.onNodeWithTag("tool-payload-toggle").performClick()
        compose.onNodeWithTag("tool-payload").assert(hasText("Arguments", substring = true))
        compose.onNodeWithTag("copy-payload").performClick()
        assertTrue(copies.last(), copies.last().startsWith("Arguments:\n"))

        compose.onNodeWithTag("tool-row").performClick()
        compose.onNodeWithTag("tool-detail").assertDoesNotExist()
    }

    @Test
    fun aFileEditShowsItsDiffAndItsSize() {
        val copies = mutableListOf<String>()
        val tool = Entry.Tool("t2", "patch", done = true, stableId = 1, args = buildJsonObject { put("path", "a.txt") },
                              result = """{"success":true}""", diff = coreDiff)
        compose.setContent { ChatScreen(Chat(listOf(Entry.User("edit", stableId = 0), tool)), onSend = {}, onCopy = { copies += it }) }

        compose.onNodeWithTag("tool-diff-stats", useUnmergedTree = true).assert(hasText("+1", substring = true))
            .assert(hasText("−1", substring = true))

        compose.onNodeWithTag("tool-row").performClick()
        compose.onNodeWithTag("tool-diff").assert(hasText("-old", substring = true)).assert(hasText("+new", substring = true))
            .assert(!hasText("\u001b", substring = true)).assert(!hasText("review diff", substring = true))
        compose.onNodeWithTag("tool-not-kept").assertDoesNotExist()
        compose.onNodeWithTag("copy-diff").performClick()
        assertEquals(listOf("a/a.txt → b/a.txt\n@@ -1,2 +1,2 @@\n-old\n+new\n same"), copies)
    }

    @Test
    fun aSearchShowsItsQueryAndHitsAndItsSummaryAlreadyNamesTheDuration() {
        val copies = mutableListOf<String>()
        val tool = Entry.Tool("t3", "web_search", "Did 1 search in 0.8s", done = true, stableId = 1, args = buildJsonObject { put("query", "kotlin flow") },
                              result = """{"success":true,"data":{"web":[{"title":"Flow","url":"https://kotlinlang.org/flow","description":"Cold streams","position":1}]}}""",
                              durationS = 0.8)
        compose.setContent { ChatScreen(Chat(listOf(Entry.User("look", stableId = 0), tool)), onSend = {}, onCopy = { copies += it }) }

        compose.onNodeWithText("web_search — Did 1 search in 0.8s", substring = true).assertExists()
        compose.onNodeWithTag("tool-duration", useUnmergedTree = true).assertDoesNotExist()  // the summary says it (D6)

        compose.onNodeWithTag("tool-row").performClick()
        listOf("kotlin flow", "Flow", "https://kotlinlang.org/flow").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithTag("copy-results").performClick()
        assertEquals(listOf("Flow\nhttps://kotlinlang.org/flow\nCold streams"), copies)
    }

    @Test
    fun anOldChatsToolSaysItsOutputWasNotKept() {
        val rows = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(TranscriptMessage.serializer()),
            """[{"role":"tool","name":"terminal","tool_call_id":"c1","context":"ls","args":{"command":"ls"}}]""")
        compose.setContent { ChatScreen(Chat(transcriptOf(rows, 0)), onSend = {}, onCopy = {}) }

        compose.onNodeWithTag("tool-row").performClick()
        compose.onNodeWithTag("tool-command").assert(hasText("ls", substring = true))
        compose.onNodeWithTag("tool-not-kept").assertTextEquals("Output not kept for this tool.")
        compose.onNodeWithTag("tool-output").assertDoesNotExist()
        compose.onNodeWithTag("copy-output").assertDoesNotExist()
        compose.onNodeWithTag("tool-payload-toggle").assertExists()
    }

    @Test
    fun aRunningRowOpensToItsCommandAndStaysOpenAsTheTranscriptGrows() {
        val user = Entry.User("wait", stableId = 0)
        val tool = Entry.Tool("t4", "terminal", stableId = 1, args = buildJsonObject { put("command", "sleep 5") })
        var chat by mutableStateOf(Chat(listOf(user, tool)))
        compose.setContent { ChatScreen(chat, onSend = {}, onCopy = {}) }

        compose.onNodeWithTag("tool-row").performClick()
        compose.onNodeWithTag("tool-command").assert(hasText("sleep 5", substring = true))
        compose.onNodeWithTag("tool-not-kept").assertDoesNotExist()  // it is running, not forgotten

        chat = chat.copy(entries = listOf(user, tool.copy(done = true, result = """{"output":"done","exit_code":0}""", durationS = 5.0)))
        compose.onNodeWithTag("tool-detail").assertExists()
        compose.onNodeWithTag("tool-output").assertTextEquals("done")

        chat = chat.copy(entries = chat.entries + Entry.Reply("Slept.", TurnStatus.COMPLETE, stableId = 2))
        compose.onNodeWithText("Slept.").assertExists()
        compose.onNodeWithTag("tool-detail").assertExists()  // the open flag follows the entry's id
    }

    /** Epoch ms of a local time in the device's own zone (the screen's default); months are 1-based here. */
    private fun local(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    /** The clock the screen writes: the phone's own 24-hour setting, read, never set. */
    private val h24: Boolean get() = DateFormat.is24HourFormat(InstrumentationRegistry.getInstrumentation().targetContext)

    /** The wall clock the times are read against, so the device's real date never matters. */
    private val nowMs = local(2026, 11, 16, 10, 0)

    @Test
    fun aTapOnYourMessageShowsWhenYouSentIt() {
        val chat = Chat(listOf(Entry.User("hi", stableId = 0, at = local(2026, 11, 15, 15, 27)),
                               Entry.User("again", stableId = 1, at = local(2026, 11, 16, 9, 5)),
                               Entry.User("undated", stableId = 2)))
        compose.setContent { ChatScreen(chat, onSend = {}, now = { nowMs }) }

        compose.onAllNodesWithTag("message-time").assertCountEquals(0)
        compose.onNodeWithText("hi").performClick()
        compose.onNodeWithTag("message-time").assertTextEquals("Yesterday, " + (if (h24) "15:27" else "3:27 PM"))

        compose.onNodeWithText("hi").performClick()
        compose.onAllNodesWithTag("message-time").assertCountEquals(0)

        compose.onNodeWithText("again").performClick()
        compose.onNodeWithTag("message-time").assertTextEquals(if (h24) "09:05" else "9:05 AM")
        compose.onNodeWithText("undated").assertHasNoClickAction()
    }

    @Test
    fun anAnswerSaysHowLongItsTurnTookAndATapShowsWhenItCame() {
        val chat = Chat(listOf(Entry.User("weather?", stableId = 0),
                               Entry.Reply("Sunny.", TurnStatus.COMPLETE, stableId = 1, at = local(2025, 9, 28, 15, 27), tookMs = 64_000)))
        compose.setContent { ChatScreen(chat, onSend = {}, onCopy = {}, onRetry = {}, now = { nowMs }) }

        compose.onNodeWithTag("turn-duration").assertTextEquals("\u23F1 1:04")
        compose.onNodeWithContentDescription("This turn took 1:04").assertExists()
        compose.onAllNodesWithTag("message-time").assertCountEquals(0)

        compose.onNodeWithText("Sunny.").performClick()
        compose.onNodeWithTag("message-time").assertTextEquals("Sep 28, 2025, " + (if (h24) "15:27" else "3:27 PM"))
        // One row: Copy and Retry at its start, the time and the turn's length at its end.
        val took = compose.onNodeWithTag("turn-duration").getUnclippedBoundsInRoot()
        for (tag in listOf("copy-reply", "retry", "message-time")) {
            val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertEquals(tag, (took.top + took.bottom).value / 2, (b.top + b.bottom).value / 2, 1f)
        }

        // A second tap inside the double-tap timeout is the selection's own (SelectionContainer selects the word it
        // touches): the reader's second tap comes later.
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Sunny.").performClick()
        compose.onAllNodesWithTag("message-time").assertCountEquals(0)
    }

    @Test
    fun aTurnTheAppDidNotSeeStartShowsNoLengthAndAnUntimedAnswerNoTime() {
        val chat = Chat(listOf(Entry.Reply("Resumed.", TurnStatus.COMPLETE, stableId = 0, at = local(2026, 11, 16, 9, 5)),
                               Entry.Reply("Old.", TurnStatus.COMPLETE, stableId = 1)))
        compose.setContent { ChatScreen(chat, onSend = {}, onCopy = {}, now = { nowMs }) }

        compose.onAllNodesWithTag("turn-duration").assertCountEquals(0)
        compose.onNodeWithText("Old.").performClick()
        compose.onAllNodesWithTag("message-time").assertCountEquals(0)
        compose.onAllNodesWithTag("entry-reply")[1].assertHasNoClickAction()

        compose.onNodeWithText("Resumed.").performClick()
        compose.onNodeWithTag("message-time").assertExists()
    }
}
