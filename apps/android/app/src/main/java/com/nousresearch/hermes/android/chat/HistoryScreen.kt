package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import java.text.DateFormat
import java.util.Date

/** Past conversations (spec §2.5): each by its title, or how it began, with when and how long; a tap continues it. */
@Composable
fun HistoryScreen(ui: HistoryUi, onPick: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().safeDrawingPadding().padding(12.dp).testTag("history"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.history_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClose, Modifier.testTag("history-close")) { Text(stringResource(R.string.ssh_done)) }
        }
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.error?.let { Text(it, Modifier.testTag("history-error"), color = MaterialTheme.colorScheme.error) }
        if (ui.sessions.isEmpty() && !ui.busy && ui.error == null) {
            Text(stringResource(R.string.history_empty), Modifier.testTag("history-empty"))
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(ui.sessions, key = { it.id }) { row ->
                Column(Modifier.fillMaxWidth().clickable(enabled = !ui.busy) { onPick(row.id) }.padding(vertical = 10.dp)) {
                    Text(row.title.ifBlank { row.preview }.ifBlank { row.id }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(row.startedAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date((it * 1000).toLong())) },
                                       pluralStringResource(R.plurals.history_messages, row.messageCount.toInt(), row.messageCount.toInt()))
                             .joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }
    }
}
