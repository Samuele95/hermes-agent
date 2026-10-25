package com.nousresearch.hermes.android

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Chat
import com.nousresearch.hermes.android.chat.ChatScreen
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import org.junit.Assert.assertEquals
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
    fun theComposerNamesTheModelAndOpensItsSetup() {
        var opened = 0
        compose.setContent { ChatScreen(Chat(model = "kimi-k2"), onSend = {}, onSetup = { opened++ }) }

        compose.onNodeWithText("kimi-k2").assertExists()
        compose.onNodeWithTag("open-setup").performClick()

        assertEquals(1, opened)
    }
}
