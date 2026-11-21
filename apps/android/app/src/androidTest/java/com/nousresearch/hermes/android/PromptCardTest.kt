package com.nousresearch.hermes.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermes.android.chat.Chat
import com.nousresearch.hermes.android.chat.ChatScreen
import com.nousresearch.hermes.android.chat.Prompt
import com.nousresearch.hermes.android.chat.PromptAnswers
import com.nousresearch.hermes.android.chat.PromptCard
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.protocol.ClarifyQuestion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A turn's question as a card: the user's answer goes out through the callbacks, the card keeps nothing. */
@RunWith(AndroidJUnit4::class)
class PromptCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val given = mutableListOf<String>()
    private val answers = PromptAnswers(
        approve = { p, choice -> given += "${p.id}:$choice" },
        clarify = { p, map -> given += "${p.id}:$map" },
        secret = { p, value -> given += "${p.id}:${value?.let(::String)}" },
    )
    private val approval = Prompt.Approval("srq-1", "r1", "rm -rf build", "removes the build output",
                                           listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.DENY))

    @Test
    fun anApprovalShowsTheCommandAndOnlyTheCoresChoices() {
        compose.setContent { PromptCard(approval, answers) }

        compose.onNodeWithText("rm -rf build").assertExists()
        compose.onNodeWithTag("approve-always").assertDoesNotExist()
        compose.onNodeWithTag("approve-session").performClick()

        assertEquals(listOf("srq-1:SESSION"), given)
    }

    @Test
    fun clarifyTakesAChoiceSeveralChoicesOrTypedTextPerQuestion() {
        val clarify = Prompt.Clarify("srq-2", listOf(
            ClarifyQuestion("q1", "Colour?", listOf("red", "blue")),
            ClarifyQuestion("q2", "Toppings?", listOf("a", "b", "c"), multiSelect = true),
            ClarifyQuestion("q3", "Name?"),
            ClarifyQuestion("q4", "Anything else?")))
        compose.setContent { PromptCard(clarify, answers) }

        compose.onNodeWithTag("choice-q1-red").performClick()
        compose.onNodeWithTag("choice-q1-blue").performClick()
        compose.onNodeWithTag("choice-q2-a").performClick()
        compose.onNodeWithTag("choice-q2-c").performClick()
        compose.onNodeWithTag("answer-q3").performTextInput(" Delta ")
        compose.onNodeWithTag("clarify-submit").performClick()

        assertEquals(listOf("""srq-2:{q1=blue, q2=["a","c"], q3=Delta, q4=null}"""), given)
    }

    @Test
    fun aSecretIsMaskedHandedOverTrimmedOrSkipped() {
        compose.setContent { PromptCard(Prompt.Secret("srq-3", "TAVILY_API_KEY", "Tavily key"), answers) }
        compose.onNodeWithTag("secret").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("secret-save").assertIsNotEnabled()

        compose.onNodeWithTag("secret").performTextInput("  tvly-1  ")
        compose.onNodeWithTag("secret-save").performClick()
        compose.onNodeWithTag("secret-skip").performClick()

        assertEquals(listOf("srq-3:tvly-1", "srq-3:null"), given)
    }

    @Test
    fun theChatShowsTheOldestQuestionOnly() {
        val chat = Chat(prompts = listOf(approval, Prompt.Secret("srq-3", "TAVILY_API_KEY", "Tavily key")))
        compose.setContent { ChatScreen(chat, onSend = {}, answers = answers) }

        assertEquals(1, compose.onAllNodesWithTag("prompt").fetchSemanticsNodes().size)
        compose.onNodeWithTag("approval-command").assertExists()
        compose.onNodeWithTag("secret").assertDoesNotExist()
    }
}
