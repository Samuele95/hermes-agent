package com.nousresearch.hermes.android

import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermes.android.chat.ContextUi
import com.nousresearch.hermes.bridge.protocol.ContextCategory
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownResult
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Chat
import com.nousresearch.hermes.android.chat.ChatScreen
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.chat.ContextMeter
import com.nousresearch.hermes.android.chat.Todo
import com.nousresearch.hermes.android.chat.TodoList
import com.nousresearch.hermes.android.chat.TodoStatus
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nousresearch.hermes.android.chat.HistoryUi
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The chat screen shows a transcript and hands a typed message over; it holds no chat state itself. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTranscriptShowsEveryKindOfEntry() {
        val chat = Chat(listOf(Entry.User("what's the weather?"), Entry.Tool("t1", "web_search", "3 results", done = true),
                               Entry.Tool("t2", "clarify", "", done = true),
                               Entry.Reply("Sunny.", TurnStatus.COMPLETE), Entry.Reply("", TurnStatus.ERROR, error = "provider unreachable"),
                               Entry.Notice("session busy")))

        compose.setContent { ChatScreen(chat, onSend = {}) }

        listOf("what's the weather?", "web_search — 3 results", "Sunny.", "provider unreachable", "session busy")
            .forEach { compose.onNodeWithText(it, substring = true).assertExists() }
        compose.onNodeWithText("clarify").assertExists()  // a blank summary leaves the name alone, no dangling dash
    }

    @Test
    fun aRepliesMarkdownIsDrawnNotShownAsMarkup() {
        val reply = Entry.Reply("## Plan\n\n**Bold** and `code`\n\n- one\n- two\n\n```sh\nls -la\n```", TurnStatus.COMPLETE)

        compose.setContent { ChatScreen(Chat(listOf(reply)), onSend = {}) }

        listOf("Plan", "Bold and code", "one", "two", "ls -la").forEach { compose.onNodeWithText(it).assertExists() }
        listOf("**", "## ", "- one", "```").forEach { compose.onNodeWithText(it, substring = true).assertDoesNotExist() }
    }

    @Test
    fun commentaryIsDrawnAsMarkdownAboveItsTool() {
        val chat = Chat(listOf(Entry.User("what day is it?"), Entry.Interim("Let me **check** the date."),
                               Entry.Tool("t1", "terminal", "Mon 3 Nov", done = true), Entry.Reply("It is Monday.", TurnStatus.COMPLETE)))

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
        compose.setContent { ChatScreen(Chat(listOf(Entry.User("hi"), Entry.Interim("Let me check.")), streaming = ""), onSend = {}) }

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
        val chat = Chat(listOf(Entry.Reply("It is 42.", TurnStatus.COMPLETE, reasoning = "Counted twice"),
                               Entry.Reply("Plain answer.", TurnStatus.COMPLETE)))
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
        val reply = Entry.Reply("The request failed.\nDetails: no provider", TurnStatus.ERROR, error = "no provider")

        compose.setContent { ChatScreen(Chat(listOf(reply)), onSend = {}) }

        assertEquals(1, compose.onAllNodesWithText("no provider", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun theDrawerHoldsTheAppsPlacesAndTheRuntimeLineOnOneLine() {
        val about = "Python 3.14.7 · SQLite 3.53.4 with FTS5 · ready in 1090 ms"
        val went = mutableListOf<String>()
        compose.setContent {
            ChatScreen(Chat(), onSend = {}, about = about, onSetup = { went += "setup" }, onTerminal = { went += "ssh" },
                       onGateway = { went += "gateway" }, onSettings = { went += "settings" }, onNewChat = { went += "new" })
        }

        compose.onNodeWithTag("open-drawer").performClick()

        val height = compose.onNodeWithText(about).getUnclippedBoundsInRoot().height
        assertTrue("the runtime line is $height tall: a column of words, not one caption line", height <= 24.dp)
        compose.onNodeWithTag("open-gateway").performClick()
        assertEquals(listOf("gateway"), went)
        listOf("drawer-new-chat", "open-ssh", "open-settings").forEach { compose.onNodeWithTag(it).assertExists() }
    }

    @Test
    fun theDrawerListsTheSessionsAsItOpensAndATapContinuesOne() {
        val heard = mutableListOf<String>()
        val sessions = HistoryUi(listOf(SessionListRow(id = "db-1", title = "Calendar", messageCount = 3,
                                                       startedAt = System.currentTimeMillis() / 1000.0)))
        compose.setContent {
            ChatScreen(Chat(), onSend = {}, sessions = sessions, onSessions = { heard += "open=$it" }, onPickSession = { heard += it })
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

        chat = Chat(listOf(Entry.User("hi")))

        compose.onNodeWithTag("intro").assertDoesNotExist()
    }

    @Test
    fun theAgentsUnfinishedPlanShowsAboveTheComposerWhileItWorks() {
        val plan = TodoList(listOf(Todo("1", "Read the repo", TodoStatus.COMPLETED), Todo("2", "Run the tests", TodoStatus.IN_PROGRESS),
                                   Todo("3", "Fix failures", TodoStatus.PENDING, "2")), 5)
        val chat by mutableStateOf(Chat(listOf(Entry.User("tidy up")), streaming = "", todos = plan))
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
        var chat by mutableStateOf(Chat(listOf(Entry.User("tea")), streaming = "", todos = resolved))
        compose.setContent { ChatScreen(chat, onSend = {}) }
        compose.onNodeWithTag("todo-strip").assertDoesNotExist()  // running, nothing left to do

        chat = Chat(listOf(Entry.User("tea"), Entry.Reply("ok", TurnStatus.COMPLETE)), todos = active)
        compose.onNodeWithTag("todo-strip").assertDoesNotExist()  // the turn ended on an unfinished plan (D1)

        chat = Chat(listOf(Entry.User("tea")), streaming = "")
        compose.onNodeWithTag("todo-strip").assertDoesNotExist()  // running, no plan

        chat = Chat(listOf(Entry.User("tea")), streaming = "", todos = active)
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
        compose.setContent { ChatScreen(Chat(model = "kimi-k2"), onSend = {}, onSetup = { opened++ }) }

        compose.onNodeWithText("kimi-k2").assertExists()
        compose.onNodeWithTag("open-setup").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theLastAnswerOffersRetryAndATapAsksForIt() {
        var taps = 0
        compose.setContent {
            ChatScreen(Chat(listOf(Entry.User("hi", 1), Entry.Reply("Hello", TurnStatus.COMPLETE))), onSend = {}, onRetry = { taps++ })
        }

        compose.onNodeWithTag("retry").performClick()

        assertEquals(1, taps)
        compose.onNodeWithContentDescription("Retry").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()  // under an ordinary answer: the icon alone
    }

    @Test
    fun aFailedTurnOffersRetryInWords() {
        var chat by mutableStateOf(Chat(listOf(Entry.User("hi"), Entry.Notice("Provider unavailable"))))
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = {}) }

        compose.onNodeWithText("Retry").assertExists()
        chat = Chat(listOf(Entry.User("hi"), Entry.Reply("", TurnStatus.ERROR, error = "timed out")))
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun noRetryWhileATurnRunsBeforeAnAnswerOrWithoutAHandler() {
        var chat by mutableStateOf(Chat(listOf(Entry.User("hi")), streaming = "He"))
        var onRetry by mutableStateOf<(() -> Unit)?>({})
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = onRetry) }

        compose.onNodeWithTag("retry").assertDoesNotExist()  // running
        chat = Chat(listOf(Entry.User("hi")))
        compose.onNodeWithTag("retry").assertDoesNotExist()  // sent, no answer yet
        chat = Chat(listOf(Entry.User("hi"), Entry.Reply("Hello", TurnStatus.COMPLETE)))
        onRetry = null
        compose.onNodeWithTag("retry").assertDoesNotExist()  // no handler
    }

    private val meter = ContextMeter(53_800, 128_000, 42, true)
    private val breakdown = SessionContextBreakdownResult(
        listOf(ContextCategory("var(--context-usage-system)", "system_prompt", "System prompt", 12_000),
               ContextCategory("var(--context-usage-conversation)", "conversation", "Conversation", 41_800)),
        contextMax = 128_000, contextPercent = 42, contextUsed = 53_800, estimatedTotal = 53_800, contextEstimated = true,
        contextSource = "estimate", model = "gemma4")
    private val answered = Chat(listOf(Entry.User("hi"), Entry.Reply("Hello", TurnStatus.COMPLETE)), meter = meter)

    @Test
    fun theMeterOpensTheContextSheet() {
        var opened = 0
        var onContext: (() -> Unit)? by mutableStateOf(null)
        compose.setContent { ChatScreen(answered, onSend = {}, onContext = onContext) }

        compose.onNodeWithTag("context-meter").assertHasNoClickAction()  // a plain readout without a sheet to open

        onContext = { opened++ }
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
        compose.setContent { ChatScreen(chat, onSend = {}, context = context, onCompress = { focuses += it }) }

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
        var chat by mutableStateOf(Chat(listOf(Entry.User("hi"), Entry.Notice("x")), submitted = true))
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
    fun theSheetClosesOnBackButNotWhileCompressing() {
        var chat by mutableStateOf(answered.copy(compressing = true))
        var context: ContextUi? by mutableStateOf(ContextUi(meter, breakdown))
        var closes = 0
        compose.setContent { ChatScreen(chat, onSend = {}, context = context, onCloseContext = { closes++; context = null }) }
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
    private val earlier = (1..6).flatMap { listOf(Entry.User("Question $it"), Entry.Reply("Answer $it", TurnStatus.COMPLETE)) }
    private fun reply(lines: Int) = (1..lines).joinToString("\n\n") { "Line $it of a long reply." }  // paragraphs: one per line
    /** The transcript's position: the lazy list's estimate, equal to its maximum exactly when nothing is below (1.12.1). */
    private fun position(): ScrollAxisRange =
        compose.onNodeWithTag("transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    private fun atTheEnd() = position().let { it.value() == it.maxValue() }
    private fun readBack() { compose.onNodeWithTag("transcript").performTouchInput { swipeDown() }; compose.waitForIdle() }
    private fun streamingChat() = Chat(earlier + Entry.User("Tell me a lot"), streaming = reply(20))

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
        chat = Chat(chat.entries + Entry.Reply(reply(60), TurnStatus.COMPLETE))
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
        val other = Chat((1..30).flatMap { listOf(Entry.User("Other $it"), Entry.Reply("Reply $it", TurnStatus.COMPLETE)) })
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
            ChatScreen(Chat(listOf(Entry.User("hi"), Entry.Reply("**Bold** and `code`", TurnStatus.COMPLETE))), onSend = {},
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
            ChatScreen(Chat(listOf(Entry.User("a", 1), Entry.Reply("One", TurnStatus.COMPLETE), Entry.User("b", 2),
                                   Entry.Reply("Two", TurnStatus.COMPLETE))), onSend = {}, onRetry = {}, onCopy = {})
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
        var chat by mutableStateOf(Chat(listOf(Entry.User("hi")), streaming = "He"))
        var onCopy by mutableStateOf<((String) -> Unit)?>({})
        compose.setContent { ChatScreen(chat, onSend = {}, onRetry = {}, onCopy = onCopy) }
        compose.waitForIdle()
        compose.onNodeWithTag("copy-reply").assertDoesNotExist()

        chat = Chat(listOf(Entry.User("hi"), Entry.Reply("", TurnStatus.ERROR, error = "timed out")))
        compose.waitForIdle()
        compose.onNodeWithTag("copy-reply").assertDoesNotExist()
        compose.onNodeWithTag("retry").assertExists()

        chat = Chat(listOf(Entry.User("hi"), Entry.Reply("Hello", TurnStatus.COMPLETE)))
        onCopy = null
        compose.waitForIdle()
        compose.onNodeWithTag("copy-reply").assertDoesNotExist()
    }

    @Test
    fun aLongPressOnYourMessageCopiesIt() {
        val copies = mutableListOf<String>()
        compose.setContent { ChatScreen(Chat(listOf(Entry.User("ls -la ~/notes"))), onSend = {}, onCopy = { copies += it }) }

        compose.onNodeWithText("ls -la ~/notes").performTouchInput { longClick() }
        compose.onNodeWithTag("copy-message").performClick()

        assertEquals(listOf("ls -la ~/notes"), copies)
        compose.onNodeWithTag("copy-message").assertDoesNotExist()
    }

    private val kotlinReply = Entry.Reply("Run:\n\n```kotlin\nval x = \"hi\" // note\n```", TurnStatus.COMPLETE)

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
}
