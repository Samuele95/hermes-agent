package com.nousresearch.hermes.android.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.SectionLabel
import com.nousresearch.hermes.android.ui.WordmarkFont
import com.nousresearch.hermes.bridge.protocol.SessionListRow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** One place the drawer leads to. */
data class DrawerLink(val icon: ImageVector, val label: String, val tag: String, val go: () -> Unit)

/**
 * What a session row's menu does (sessions-05/06/12): rename, export or delete. [edit] / [askDelete] take a row's stored
 * key, null to stop; [export] hands a row's stored key to the share sheet's export (none: the menu offers no Export).
 */
class SessionActions(val edit: (stored: StoredSessionId?) -> Unit, val saveTitle: (String) -> Unit,
                     val askDelete: (stored: StoredSessionId?) -> Unit, val confirmDelete: () -> Unit,
                     val export: ((stored: StoredSessionId) -> Unit)? = null)

/**
 * The drawer the chat slides out, in the shape of the desktop's sidebar: the brand, a new chat and the places the
 * app has, then the sessions, newest first under their headings; [about] (the runtime line) closes it. With [actions],
 * a long press on a session offers to rename, export or delete it.
 */
@Composable
fun ColumnScope.SessionDrawerContent(links: List<DrawerLink>, sessions: HistoryUi?, onPick: (StoredSessionId) -> Unit, about: String?,
                                     actions: SessionActions? = null) {
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
        SessionList(sessions, onPick, Modifier.weight(1f), actions)
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

/**
 * Past conversations under their headings (spec §2.5); a tap continues one. With [actions], a long press opens the
 * row's menu (Rename… · Export · Delete), the row being renamed is a title field, and a delete asks first.
 */
@Composable
fun SessionList(ui: HistoryUi, onPick: (StoredSessionId) -> Unit, modifier: Modifier = Modifier, actions: SessionActions? = null) {
    val now = remember(ui) { System.currentTimeMillis() }
    val groups = remember(ui.sessions, now) { groupSessions(ui.sessions, now) }
    var menuFor by remember { mutableStateOf<StoredSessionId?>(null) }  // the row whose menu is open: transient UI (D7)
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
                    if (actions != null && ui.editing == row.stored) RenameField(row, ui.busy, actions)
                    else Box {
                        SessionRow(row, now, enabled = !ui.busy, onPick = { onPick(row.stored) },
                                   onMenu = actions?.let { { menuFor = row.stored } })
                        if (actions != null) RowMenu(open = menuFor == row.stored, onDismiss = { menuFor = null },
                                                     onRename = { menuFor = null; actions.edit(row.stored) },
                                                     onExport = actions.export?.let { ex -> { menuFor = null; ex(row.stored) } },
                                                     onDelete = { menuFor = null; actions.askDelete(row.stored) })
                    }
                }
            }
        }
    }
    if (actions != null) ui.confirmDelete?.let { id -> DeleteDialog(ui.sessions.find { it.stored == id }?.label() ?: id.value, actions) }
}

/** What a row is called: its title, else the start of its first message, else its id. */
private fun SessionListRow.label() = title.ifBlank { preview }.ifBlank { id }

@Composable
private fun SessionRow(row: SessionListRow, now: Long, enabled: Boolean, onPick: () -> Unit, onMenu: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
            .combinedClickable(enabled = enabled, onClick = onPick, onLongClickLabel = stringResource(R.string.session_actions),
                               onLongClick = onMenu)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(row.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(start = 8.dp)) {
            Text(sessionAge(row.startedAt, now), style = MaterialTheme.typography.labelSmall, color = Hermes.colors.textTertiary)
        }
    }
}

/** A row's menu, as the desktop sidebar's: Rename…, Export (the work item before the destructive one) and Delete. */
@Composable
private fun RowMenu(open: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onExport: (() -> Unit)?, onDelete: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.session_rename)) }, onClick = onRename, Modifier.testTag("session-rename"),
                         leadingIcon = { Icon(HermesIcons.Pencil, null, Modifier.size(16.dp)) })
        onExport?.let {
            DropdownMenuItem(text = { Text(stringResource(R.string.session_export)) }, onClick = it, Modifier.testTag("session-export"),
                             leadingIcon = { Icon(HermesIcons.Download, null, Modifier.size(16.dp)) })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.session_delete), color = MaterialTheme.colorScheme.error) }, onClick = onDelete,
                         Modifier.testTag("session-delete"),
                         leadingIcon = { Icon(HermesIcons.Trash, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) })
    }
}

/**
 * The row being renamed: its title, selected, in a field. Done or the check saves it (a blank one cannot be saved,
 * D5); Back or the cross stops. Composed after the chat's drawer Back handler, so Back stops the rename first.
 */
@Composable
private fun RenameField(row: SessionListRow, busy: Boolean, actions: SessionActions) {
    var typed by rememberSaveable(row.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(row.title, TextRange(0, row.title.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(row.id) { focus.requestFocus() }
    BackHandler { actions.edit(null) }
    val ok = typed.text.isNotBlank() && !busy
    val save = { if (ok) actions.saveTitle(typed.text) }
    OutlinedTextField(typed, { typed = it },
                      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).focusRequester(focus).testTag("rename-field"),
                      singleLine = true, enabled = !busy, placeholder = { Text(stringResource(R.string.session_untitled)) },
                      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save() }),
                      trailingIcon = {
                          Row {
                              IconButton(save, Modifier.testTag("rename-save"), enabled = ok) {
                                  Icon(HermesIcons.Check, stringResource(R.string.session_rename_save))
                              }
                              IconButton({ actions.edit(null) }, Modifier.testTag("rename-cancel")) {
                                  Icon(HermesIcons.Close, stringResource(R.string.session_rename_cancel))
                              }
                          }
                      })
}

/** Asks before a row is deleted, in the desktop's words; Delete hands over to the drawer's progress line (D8). */
@Composable
private fun DeleteDialog(label: String, actions: SessionActions) {
    AlertDialog(onDismissRequest = { actions.askDelete(null) }, modifier = Modifier.testTag("delete-dialog"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                title = { Text(stringResource(R.string.session_delete_title)) },
                text = { Text(stringResource(R.string.session_delete_body, label)) },
                confirmButton = { PrimaryButton(stringResource(R.string.session_delete), actions.confirmDelete, Modifier.testTag("delete-confirm")) },
                dismissButton = { SecondaryButton(stringResource(R.string.session_cancel), { actions.askDelete(null) },
                                                  Modifier.testTag("delete-cancel")) })
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
