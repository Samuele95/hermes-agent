package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons

/**
 * What the queue panel does (chat-19): [add] the composer's draft to the queue, [edit] an item in place (null ends the
 * edit), [save] its new words, [steer] the running turn with it now, send it [next], [delete] it, and [resume] a paused
 * queue. Each takes the item's own id, so a tap on a row already sent changes nothing.
 */
class QueueActions(val add: (String) -> Unit, val edit: (id: Long?) -> Unit, val save: (id: Long, text: String) -> Unit,
                   val steer: (Long) -> Unit, val next: (Long) -> Unit, val delete: (Long) -> Unit, val resume: () -> Unit)

/**
 * The chat's queue above the composer, as the desktop's queue panel: "N Queued" (or "— paused", with Resume), then
 * the messages in the order they go out, one per finished turn. Each row's menu edits it in place, steers the running
 * turn with it when a correction can land ([steerable]), sends it next ([busy]) or now (idle), or deletes it.
 */
@Composable
fun QueuePanel(queue: QueueUi, busy: Boolean, steerable: Boolean, actions: QueueActions) {
    val shape = RoundedCornerShape(14.dp)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp)
               .background(MaterialTheme.colorScheme.surfaceContainer, shape).border(1.dp, Hermes.colors.stroke, shape).testTag("queue-panel")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (queue.paused) HermesIcons.Pause else HermesIcons.Queue, null, Modifier.size(15.dp), tint = muted)
            Text(stringResource(if (queue.paused) R.string.queue_paused else R.string.queue_count, queue.items.size),
                 Modifier.padding(start = 8.dp).weight(1f).testTag("queue-count"), style = MaterialTheme.typography.labelLarge)
            if (queue.paused) TextButton(actions.resume, Modifier.testTag("queue-resume")) { Text(stringResource(R.string.queue_resume)) }
        }
        Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 4.dp, bottom = 6.dp)) {
            queue.items.forEach { item ->
                key(item.id) {
                    if (queue.editing == item.id) EditedRow(item, actions)
                    else QueuedRow(item, otherEdited = queue.editing != null, busy, steerable, actions)
                }
            }
        }
    }
}

/** One queued message, two lines at most, and its menu (as the user bubble's). */
@Composable
private fun QueuedRow(item: Queued, otherEdited: Boolean, busy: Boolean, steerable: Boolean, actions: QueueActions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().testTag("queue-item"), verticalAlignment = Alignment.CenterVertically) {
        Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box {
            IconButton({ menu = true }, Modifier.testTag("queue-more")) {
                Icon(HermesIcons.More, stringResource(R.string.queue_actions), Modifier.size(18.dp),
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                fun pick(action: (Long) -> Unit): () -> Unit = { menu = false; action(item.id) }
                // One edit at a time (the desktop's queue-panel.tsx:135).
                DropdownMenuItem(text = { Text(stringResource(R.string.queue_edit)) }, onClick = pick(actions.edit), Modifier.testTag("queue-edit"),
                                 enabled = !otherEdited)
                if (steerable) DropdownMenuItem(text = { Text(stringResource(R.string.queue_steer)) }, onClick = pick(actions.steer),
                                                Modifier.testTag("queue-steer"))
                DropdownMenuItem(text = { Text(stringResource(if (busy) R.string.queue_next else R.string.queue_send)) }, onClick = pick(actions.next),
                                 Modifier.testTag("queue-next"))
                DropdownMenuItem(text = { Text(stringResource(R.string.queue_delete)) }, onClick = pick(actions.delete),
                                 Modifier.testTag("queue-delete"))
            }
        }
    }
}

/** The item being edited, in place: its words in a field, then Cancel and Save (a blank one cannot be saved). */
@Composable
private fun EditedRow(item: Queued, actions: QueueActions) {
    var draft by rememberSaveable(item.id) { mutableStateOf(item.text) }
    Column(Modifier.fillMaxWidth().padding(end = 8.dp)) {
        TextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("queue-field"), maxLines = 4,
                  textStyle = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({ actions.edit(null) }, Modifier.testTag("queue-cancel")) { Text(stringResource(R.string.queue_cancel)) }
            Spacer(Modifier.size(4.dp))
            TextButton({ actions.save(item.id, draft) }, Modifier.testTag("queue-save"), enabled = draft.isNotBlank()) {
                Text(stringResource(R.string.queue_save))
            }
        }
    }
}
