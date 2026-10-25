package com.nousresearch.hermes.android.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.SectionLabel
import com.nousresearch.hermes.android.ui.WordmarkFont
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One place the drawer leads to. */
data class DrawerLink(val icon: ImageVector, val label: String, val tag: String, val go: () -> Unit)

/**
 * The drawer the chat slides out, in the shape of the desktop's sidebar: the brand, a new chat and the places the
 * app has, then the sessions, newest first under their headings; [about] (the runtime line) closes it.
 */
@Composable
fun ColumnScope.SessionDrawerContent(links: List<DrawerLink>, sessions: HistoryUi?, onPick: (String) -> Unit, about: String?) {
    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(if (Hermes.colors.dark) R.drawable.brand_mark_dark else R.drawable.brand_mark), null,
              Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(12.dp))
        Text("HERMES AGENT", fontFamily = WordmarkFont, fontSize = 20.sp, letterSpacing = 0.08.em,
             color = if (Hermes.colors.dark) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
    }
    links.forEach { DrawerRow(it) }
    if (sessions != null) {
        Caption(stringResource(R.string.sessions_title), Modifier.padding(top = 12.dp))
        SessionList(sessions, onPick, Modifier.weight(1f))
    } else Spacer(Modifier.weight(1f))
    about?.let {
        Text(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.labelSmall,
             color = Hermes.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DrawerRow(link: DrawerLink) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = link.go)
            .testTag(link.tag).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(link.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(link.label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The sidebar's caption: a small tracked label and a hairline. */
@Composable
private fun Caption(label: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = SectionLabel, color = Hermes.colors.textTertiary)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = Hermes.colors.stroke)
    }
}

/** Past conversations under their headings (spec §2.5); a tap continues one. */
@Composable
fun SessionList(ui: HistoryUi, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val now = remember(ui) { System.currentTimeMillis() }
    val groups = remember(ui.sessions, now) { groupSessions(ui.sessions, now) }
    Column(modifier.fillMaxWidth().testTag("history")) {
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).height(2.dp))
        ui.error?.let {
            Text(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("history-error"), color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.sessions.isEmpty() && !ui.busy && ui.error == null) {
            Text(stringResource(R.string.history_empty), Modifier.padding(horizontal = 20.dp, vertical = 8.dp).testTag("history-empty"),
                 style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            groups.forEach { group ->
                item(key = "heading:${group.heading}") {
                    Text(heading(group.heading), Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                         style = MaterialTheme.typography.labelMedium, color = Hermes.colors.textTertiary)
                }
                items(group.sessions, key = { it.id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !ui.busy) { onPick(row.id) }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(row.title.ifBlank { row.preview }.ifBlank { row.id }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.padding(start = 8.dp)) {
                            Text(sessionAge(row.startedAt, now), style = MaterialTheme.typography.labelSmall, color = Hermes.colors.textTertiary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun heading(heading: SessionHeading): String = when (heading) {
    SessionHeading.Today -> stringResource(R.string.sessions_today)
    SessionHeading.Yesterday -> stringResource(R.string.sessions_yesterday)
    SessionHeading.ThisWeek -> stringResource(R.string.sessions_this_week)
    SessionHeading.LastWeek -> stringResource(R.string.sessions_last_week)
    SessionHeading.ThisMonth -> stringResource(R.string.sessions_this_month)
    SessionHeading.Older -> stringResource(R.string.sessions_older)
    is SessionHeading.Source -> heading.name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    is SessionHeading.Month -> {
        val thisYear = Calendar.getInstance().get(Calendar.YEAR) == heading.year
        val month = Calendar.getInstance().apply { clear(); set(heading.year, heading.month - 1, 1) }.time
        SimpleDateFormat(if (thisYear) "LLLL" else "LLLL yyyy", Locale.getDefault()).format(month)
    }
}
