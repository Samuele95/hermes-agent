package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.Hint
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.CodeBox
import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.protocol.ClarifyQuestion

/** Answers to a prompt, one callback per kind; a null secret is a skipped one. */
class PromptAnswers(
    val approve: (Prompt.Approval, ApprovalChoice) -> Unit,
    val clarify: (Prompt.Clarify, Map<String, String?>) -> Unit,
    val secret: (Prompt.Secret, ByteArray?) -> Unit,
)

/** The question a running turn waits on. Its fields live in composition only, keyed by the prompt. */
@Composable
fun PromptCard(prompt: Prompt, answers: PromptAnswers, modifier: Modifier = Modifier) {
    // The desktop's inline widget: the widget surface, a hairline border, above the composer.
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
               .background(Hermes.colors.widget, shape).border(1.dp, Hermes.colors.stroke, shape)
               .padding(14.dp).testTag("prompt"),
           verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (prompt) {
            is Prompt.Approval -> Approval(prompt, answers.approve)
            is Prompt.Clarify -> Clarify(prompt, answers.clarify)
            is Prompt.Secret -> Secret(prompt, answers.secret)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Approval(prompt: Prompt.Approval, onChoice: (Prompt.Approval, ApprovalChoice) -> Unit) {
    Text(stringResource(R.string.prompt_approval), style = MaterialTheme.typography.titleSmall)
    CodeBox(prompt.command, Modifier.testTag("approval-command"))
    if (prompt.description.isNotBlank()) Hint(prompt.description)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        prompt.choices.forEach { choice ->
            val label = stringResource(when (choice) {
                ApprovalChoice.ONCE -> R.string.prompt_allow_once
                ApprovalChoice.SESSION -> R.string.prompt_allow_session
                ApprovalChoice.ALWAYS -> R.string.prompt_allow_always
                ApprovalChoice.DENY -> R.string.prompt_deny
            })
            val tag = Modifier.testTag("approve-${choice.name.lowercase()}")
            when (choice) {
                ApprovalChoice.ONCE -> PrimaryButton(label, { onChoice(prompt, choice) }, tag)
                ApprovalChoice.DENY -> SecondaryButton(label, { onChoice(prompt, choice) }, tag, color = MaterialTheme.colorScheme.error)
                else -> SecondaryButton(label, { onChoice(prompt, choice) }, tag)
            }
        }
    }
}

@Composable
private fun Clarify(prompt: Prompt.Clarify, onAnswers: (Prompt.Clarify, Map<String, String?>) -> Unit) {
    val typed = remember(prompt.id) { mutableStateMapOf<String, String>() }
    val picked = remember(prompt.id) { mutableStateMapOf<String, List<String>>() }
    prompt.questions.forEach { question -> Question(question, typed, picked) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(stringResource(R.string.prompt_answer), { onAnswers(prompt, prompt.questions.associate { it.qid to answer(it, typed, picked) }) },
                      Modifier.testTag("clarify-submit"))
        TextButton({ onAnswers(prompt, prompt.questions.associate { it.qid to null }) }, Modifier.testTag("clarify-skip")) {
            Text(stringResource(R.string.prompt_skip))
        }
    }
}

@Composable
private fun Question(question: ClarifyQuestion, typed: MutableMap<String, String>, picked: MutableMap<String, List<String>>) {
    Text(question.question, style = MaterialTheme.typography.titleSmall)
    question.choices.orEmpty().forEach { choice ->
        val chosen = choice in picked[question.qid].orEmpty()
        Row(Modifier.fillMaxWidth().testTag("choice-${question.qid}-$choice").selectable(chosen) {
                picked[question.qid] = when {
                    !question.multiSelect -> listOf(choice)
                    chosen -> picked[question.qid].orEmpty() - choice
                    else -> picked[question.qid].orEmpty() + choice
                }
            }, verticalAlignment = Alignment.CenterVertically) {
            if (question.multiSelect) Checkbox(chosen, null) else RadioButton(chosen, null)
            Text(choice)
        }
    }
    // A question with choices still takes a typed answer, as the Desktop's "Other" row does.
    OutlinedTextField(typed[question.qid].orEmpty(), { typed[question.qid] = it }, Modifier.fillMaxWidth().testTag("answer-${question.qid}"),
                      placeholder = { Text(stringResource(if (question.choices.isNullOrEmpty()) R.string.prompt_your_answer else R.string.prompt_other)) })
}

/** Typed text wins over picked choices; nothing given is a skipped question. */
private fun answer(question: ClarifyQuestion, typed: Map<String, String>, picked: Map<String, List<String>>): String? {
    typed[question.qid]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val chosen = picked[question.qid].orEmpty().takeIf { it.isNotEmpty() } ?: return null
    return if (question.multiSelect) multiSelect(chosen) else chosen.single()
}

@Composable
private fun Secret(prompt: Prompt.Secret, onSecret: (Prompt.Secret, ByteArray?) -> Unit) {
    // remember, not rememberSaveable: saved state is written out with the task, and the secret must not be.
    var value by remember(prompt.id) { mutableStateOf("") }
    Text(prompt.prompt, style = MaterialTheme.typography.titleSmall)
    Hint(stringResource(R.string.prompt_secret_kept, prompt.envVar))
    OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().testTag("secret"), singleLine = true,
                      visualTransformation = PasswordVisualTransformation(),
                      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
    Row(verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(stringResource(R.string.setup_save_key), { onSecret(prompt, value.trim().toByteArray()); value = "" },
                      Modifier.testTag("secret-save"), enabled = value.isNotBlank())
        TextButton({ onSecret(prompt, null); value = "" }, Modifier.testTag("secret-skip")) { Text(stringResource(R.string.prompt_skip)) }
    }
}
