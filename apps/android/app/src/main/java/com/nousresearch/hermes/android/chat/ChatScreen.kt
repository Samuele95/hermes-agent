package com.nousresearch.hermes.android.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.ui.CodeFont
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.Wordmark
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A chat in the desktop's shape: the transcript is the page, replies flat across it and the user's words in
 * bubbles, the composer at the bottom naming the model, and a drawer with the sessions and the app's other places.
 * It holds only the draft; [onSend] gets the trimmed text. [onSessions] hears the drawer open (load the sessions)
 * and close.
 */
@Composable
fun ChatScreen(chat: Chat, onSend: (String) -> Unit, modifier: Modifier = Modifier, about: String? = null,
               onSetup: (() -> Unit)? = null, answers: PromptAnswers? = null, onTerminal: (() -> Unit)? = null,
               onGateway: (() -> Unit)? = null, onStop: () -> Unit = {}, onSettings: (() -> Unit)? = null,
               onNewChat: (() -> Unit)? = null, sessions: HistoryUi? = null, onSessions: (open: Boolean) -> Unit = {},
               onPickSession: (String) -> Unit = {}) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(drawer) { snapshotFlow { drawer.isOpen }.distinctUntilChanged().drop(1).collect(onSessions) }
    // Back closes an open drawer before it leaves the app.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    fun leaveTo(go: () -> Unit) = {
        scope.launch { drawer.snapTo(DrawerValue.Closed) }
        go()
    }
    val links = listOfNotNull(
        onNewChat?.let { DrawerLink(HermesIcons.NewChat, stringResource(R.string.chat_new), "drawer-new-chat", leaveTo(it)) },
        onGateway?.let { DrawerLink(HermesIcons.Gateway, stringResource(R.string.chat_gateway), "open-gateway", leaveTo(it)) },
        onTerminal?.let { DrawerLink(HermesIcons.Terminal, stringResource(R.string.chat_terminal), "open-ssh", leaveTo(it)) },
        onSettings?.let { DrawerLink(HermesIcons.Settings, stringResource(R.string.chat_settings), "open-settings", leaveTo(it)) },
    )
    val hasDrawer = links.isNotEmpty() || sessions != null || about != null
    ModalNavigationDrawer(drawerContent = {
        ModalDrawerSheet(Modifier.width(304.dp), drawerShape = RectangleShape,
                         drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            SessionDrawerContent(links, sessions, onPick = { id -> scope.launch { drawer.close() }; onPickSession(id) }, about)
        }
    }, modifier, drawerState = drawer, gesturesEnabled = hasDrawer) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            TopBar(onMenu = if (hasDrawer) ({ scope.launch { drawer.open() } }) else null, onNewChat)
            if (chat.entries.isEmpty() && !chat.running && chat.prompts.isEmpty()) Intro(Modifier.weight(1f))
            else Transcript(chat, Modifier.weight(1f))
            // One question at a time, the oldest first: the turn waits on it.
            if (answers != null) chat.prompts.firstOrNull()?.let { PromptCard(it, answers) }
            Composer(chat, onSend, onStop, onSetup)
        }
    }
}

@Composable
private fun TopBar(onMenu: (() -> Unit)?, onNewChat: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onMenu != null) {
            IconButton(onMenu, Modifier.testTag("open-drawer")) { Icon(HermesIcons.Menu, stringResource(R.string.chat_menu), Modifier.size(22.dp)) }
        } else Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.app_name), Modifier.weight(1f).padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
        onNewChat?.let {
            IconButton(it, Modifier.testTag("new-chat")) { Icon(HermesIcons.NewChat, stringResource(R.string.chat_new), Modifier.size(20.dp)) }
        }
    }
}

/** The empty chat: the wordmark and one of the desktop's lines. */
@Composable
private fun Intro(modifier: Modifier) {
    val lines = stringArrayResource(R.array.intro_lines)
    val line by rememberSaveable { mutableStateOf(Random.nextInt(lines.size)) }
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark("HERMES AGENT", if (Hermes.colors.dark) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
                                     else MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().testTag("intro"))
            Text(lines[line % lines.size], Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Transcript(chat: Chat, modifier: Modifier) {
    val list = rememberLazyListState()
    val rows = chat.entries.size + (if (chat.running) 1 else 0) + (if (chat.activity != null) 1 else 0)
    LaunchedEffect(rows, chat.streaming) { if (rows > 0) list.animateScrollToItem(rows - 1) }
    LazyColumn(modifier.fillMaxWidth(), list, PaddingValues(horizontal = 16.dp, vertical = 12.dp),
               verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(chat.entries) { EntryRow(it) }
        chat.reasoning?.takeIf { chat.running && it.isNotBlank() }?.let { text -> item { Reasoning(text, Modifier.testTag("reasoning-streaming")) } }
        chat.streaming?.let { text ->
            item { if (text.isEmpty()) Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant) else Reply(text) }
        }
        chat.activity?.let { text ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: Entry) {
    when (entry) {
        is Entry.User -> UserBubble(entry.text)
        is Entry.Reply -> Column(Modifier.fillMaxWidth().testTag("entry-reply")) {
            entry.reasoning?.let { reasoning ->
                var shown by rememberSaveable { mutableStateOf(false) }
                Row(Modifier.clickable { shown = !shown }.testTag("reasoning-toggle").padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (shown) HermesIcons.ChevronDown else HermesIcons.ChevronRight, null, Modifier.size(14.dp),
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.chat_thinking), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (shown) Reasoning(reasoning, Modifier.padding(bottom = 8.dp))
            }
            if (entry.text.isNotEmpty()) Reply(entry.text)
            // The core often words the error into the reply itself; show it apart only when it did not.
            entry.error?.takeUnless { it in entry.text }?.let { Problem(it) }
        }
        is Entry.Tool -> ToolRow(entry)
        is Entry.Notice -> Problem(entry.text, Modifier.testTag("entry-notice"))
    }
}

/** The agent's words: Markdown drawn flat across the column, selectable like the desktop's. */
@Composable
private fun Reply(text: String) {
    SelectionContainer { MarkdownText(text, Modifier.fillMaxWidth()) }
}

/** The model's reasoning: set apart from the reply by a rule, quieter than it. */
@Composable
private fun Reasoning(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        Box(Modifier.width(2.dp).heightIn(min = 16.dp).background(Hermes.colors.stroke))
        Text(text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UserBubble(text: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(text, Modifier.widthIn(max = 320.dp).background(Hermes.colors.userBubble, shape).border(1.dp, Hermes.colors.userBubbleBorder, shape)
                 .padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** A tool the agent ran: one quiet line, its name in the code face. */
@Composable
private fun ToolRow(tool: Entry.Tool) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(HermesIcons.Tool, null, Modifier.size(14.dp), tint = Hermes.colors.textTertiary)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = CodeFont, fontSize = 13.sp)) { append(tool.name) }
            tool.summary?.takeIf { tool.done && it.isNotBlank() }?.let { withStyle(SpanStyle(color = muted)) { append(" — $it") } }
        }, Modifier.weight(1f, fill = false).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
             overflow = TextOverflow.Ellipsis)
        if (!tool.done) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(12.dp), strokeWidth = 1.5.dp, color = muted)
    }
}

@Composable
private fun Problem(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(HermesIcons.Alert, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/** The desktop's composer: a rounded field, the model it will ask beneath, and Send — or Stop while a turn runs. */
@Composable
private fun Composer(chat: Chat, onSend: (String) -> Unit, onStop: () -> Unit, onSetup: (() -> Unit)?) {
    var draft by rememberSaveable { mutableStateOf("") }
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
               .background(MaterialTheme.colorScheme.surfaceContainer, shape).border(1.dp, Hermes.colors.stroke, shape)) {
        TextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("draft"), maxLines = 6,
                  textStyle = MaterialTheme.typography.bodyLarge, placeholder = { Text(stringResource(R.string.chat_hint)) },
                  colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                                    disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                                    unfocusedIndicatorColor = Color.Transparent))
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The model takes the room the button leaves; a long name is cut at its end.
            Box(Modifier.weight(1f)) {
                if (onSetup != null) {
                    Row(Modifier.clip(CircleShape).clickable(onClick = onSetup).testTag("open-setup").padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(HermesIcons.Model, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(chat.model ?: stringResource(R.string.chat_model), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (chat.running) {
                FilledIconButton(onStop, Modifier.size(36.dp).testTag("stop"),
                                 colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                                                                                    contentColor = MaterialTheme.colorScheme.surface)) {
                    Icon(HermesIcons.Stop, stringResource(R.string.chat_stop), Modifier.size(14.dp))
                }
            } else {
                FilledIconButton({ onSend(draft.trim()); draft = "" }, Modifier.size(36.dp).testTag("send"), enabled = draft.isNotBlank()) {
                    Icon(HermesIcons.Send, stringResource(R.string.chat_send), Modifier.size(18.dp))
                }
            }
        }
    }
}
