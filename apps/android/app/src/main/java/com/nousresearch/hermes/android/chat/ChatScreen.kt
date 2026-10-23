package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R

/** A chat's transcript and its message field. It holds only the draft; [onSend] gets the trimmed text. */
@Composable
fun ChatScreen(chat: Chat, onSend: (String) -> Unit, modifier: Modifier = Modifier, about: String? = null,
               onSetup: (() -> Unit)? = null, answers: PromptAnswers? = null, onTerminal: (() -> Unit)? = null,
               onGateway: (() -> Unit)? = null) {
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val rows = chat.entries.size + (if (chat.running) 1 else 0) + (if (chat.activity != null) 1 else 0)
    LaunchedEffect(rows, chat.streaming) { if (rows > 0) list.animateScrollToItem(rows - 1) }

    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(about.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            onGateway?.let { TextButton(it, Modifier.testTag("open-gateway")) { Text(stringResource(R.string.chat_gateway)) } }
            onTerminal?.let { TextButton(it, Modifier.testTag("open-ssh")) { Text(stringResource(R.string.chat_terminal)) } }
            onSetup?.let { TextButton(it, Modifier.testTag("open-setup")) { Text(stringResource(R.string.chat_model)) } }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), list, PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat.entries) { EntryRow(it) }
            chat.streaming?.let { text -> item { Bubble(text.ifEmpty { "…" }, mine = false) } }
            chat.activity?.let { text -> item { Text(text, style = MaterialTheme.typography.labelMedium) } }
        }
        // One question at a time, the oldest first: the turn waits on it.
        if (answers != null) chat.prompts.firstOrNull()?.let { PromptCard(it, answers) }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f).testTag("draft"),
                              placeholder = { Text(stringResource(R.string.chat_hint)) })
            Button({ onSend(draft.trim()); draft = "" }, Modifier.padding(start = 8.dp).testTag("send"),
                   enabled = draft.isNotBlank() && !chat.running) { Text(stringResource(R.string.chat_send)) }
        }
    }
}

@Composable
private fun EntryRow(entry: Entry) {
    when (entry) {
        is Entry.User -> Bubble(entry.text, mine = true)
        is Entry.Reply -> Column(Modifier.testTag("entry-reply")) {
            if (entry.text.isNotEmpty()) Bubble(entry.text, mine = false)
            // The core often words the error into the reply itself; show it apart only when it did not.
            entry.error?.takeUnless { it in entry.text }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        is Entry.Tool -> Text(if (entry.done) listOfNotNull(entry.name, entry.summary?.takeIf { it.isNotBlank() }).joinToString(" — ")
                              else "${entry.name}…",
                              style = MaterialTheme.typography.labelMedium)
        is Entry.Notice -> Text(entry.text, Modifier.testTag("entry-notice"), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text, Modifier.widthIn(max = 320.dp)
            .background(if (mine) colors.primaryContainer else colors.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(10.dp), color = if (mine) colors.onPrimaryContainer else Color.Unspecified)
    }
}
