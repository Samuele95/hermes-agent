package com.nousresearch.hermes.android.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A chat in the desktop's shape: the transcript is the page, replies flat across it and the user's words in
 * bubbles, the composer at the bottom naming the model, and a drawer with the sessions and the app's other places.
 * It holds only the draft; [onSend] gets the trimmed text, [onRetry] runs the last prompt again. [onSessions] hears the drawer open (load the sessions)
 * and close; [sessionActions] is the rows' menu (rename, delete). [onContext] opens the context sheet from the meter;
 * [context] is that sheet while it is open. The transcript follows a streaming reply only while the reader is at its end;
 * Jump brings it back (chat-17). [onCopy] puts an answer, one of the user's messages or a code block on the clipboard
 * (chat-09, chat-10); without it nothing offers Copy.
 */
@Composable
fun ChatScreen(chat: Chat, onSend: (String) -> Unit, modifier: Modifier = Modifier, about: String? = null,
               onSetup: (() -> Unit)? = null, answers: PromptAnswers? = null, onTerminal: (() -> Unit)? = null,
               onGateway: (() -> Unit)? = null, onStop: () -> Unit = {}, onRetry: (() -> Unit)? = null, onCopy: ((String) -> Unit)? = null,
               onSettings: (() -> Unit)? = null,
               onNewChat: (() -> Unit)? = null, sessions: HistoryUi? = null, onSessions: (open: Boolean) -> Unit = {},
               onPickSession: (String) -> Unit = {}, sessionActions: SessionActions? = null, context: ContextUi? = null, onContext: (() -> Unit)? = null,
               onCompress: (String?) -> Unit = {}, onCloseContext: () -> Unit = {}) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var picked by remember { mutableIntStateOf(0) }  // a conversation picked in the drawer is shown from its end (V5)
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
            SessionDrawerContent(links, sessions, onPick = { id -> scope.launch { drawer.close() }; picked++; onPickSession(id) }, about, sessionActions)
        }
    }, modifier, drawerState = drawer, gesturesEnabled = hasDrawer) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            TopBar(onMenu = if (hasDrawer) ({ scope.launch { drawer.open() } }) else null, onNewChat)
            if (chat.entries.isEmpty() && !chat.running && chat.prompts.isEmpty()) Intro(Modifier.weight(1f))
            else Transcript(chat, onRetry, onCopy, picked, Modifier.weight(1f))
            // One question at a time, the oldest first: the turn waits on it.
            if (answers != null) chat.prompts.firstOrNull()?.let { PromptCard(it, answers) }
            // The agent's plan while it works through it (D1: a turn that ended leaves no stale plan pinned).
            chat.todos?.takeIf { chat.running && it.active }?.let { TodoStrip(it) }
            Composer(chat, onSend, onStop, onSetup, onContext)
            context?.let { ContextSheet(it, running = chat.running, onCompress = onCompress, onClose = onCloseContext) }
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
private fun Transcript(chat: Chat, onRetry: (() -> Unit)?, onCopy: ((String) -> Unit)?, picked: Int, modifier: Modifier) {
    val list = rememberLazyListState()
    // chat-17 (F9, DC-B16): whether the list follows its newest row belongs to the view, never to the conversation.
    var following by rememberSaveable { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()  // the reader's finger only, never a scroll of ours
    val retry = onRetry?.takeIf { chat.retryTarget() != null }
    // Only the reader moves the follow: dragging back stops it, and reaching the end starts it again (Follow.kt, V1).
    LaunchedEffect(list) {
        snapshotFlow { Triple(dragged, list.lastScrolledBackward, list.canScrollForward) }
            .collect { (dragging, back, more) -> following = follows(following, atEnd = !more, draggedBack = dragging && back) }
    }
    // A conversation picked in the drawer is shown from its end (V5). The first composition already follows.
    LaunchedEffect(picked) { if (picked > 0) following = true }
    // While following, every change keeps the end in view at once (V3, §6), never against the reader's finger.
    // A drag that ends re-runs this.
    LaunchedEffect(chat.entries.size, chat.entries.lastOrNull(), chat.streaming, chat.reasoning, chat.activity != null,
                   retry != null, following, dragged) {
        if (following && !dragged) list.scrollToEnd()
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize().testTag("transcript"), list, PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                   verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // The last answer carries Retry in its own action row, beside Copy (F10); after a turn that ended in a notice or a
            // tool row, Retry stands alone in words, as before.
            itemsIndexed(chat.entries) { i, entry ->
                EntryRow(entry, onCopy, retry.takeIf { i == chat.entries.lastIndex && entry is Entry.Reply })
            }
            retry?.takeIf { chat.entries.last() !is Entry.Reply }?.let { item { RetryAction(failed = true, it) } }
            chat.reasoning?.takeIf { chat.running && it.isNotBlank() }?.let { text -> item { Reasoning(text, Modifier.testTag("reasoning-streaming")) } }
            chat.streaming?.let { text ->
                item { if (text.isEmpty()) Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant) else Reply(text, onCopy) }
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
        if (!following) JumpToLatest(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { following = true }
    }
}

/**
 * Brings the end of the last row into view, not its top, so a reply taller than the screen is followed to its newest
 * line (V4). This effect may start before the frame's layout. A scroll measures first and waits for the first layout,
 * so nothing here trusts a [LazyListState.layoutInfo] read before a scroll more than it must.
 */
private suspend fun LazyListState.scrollToEnd() {
    if (layoutInfo.totalItemsCount == 0) scrollBy(0f)  // not laid out yet: a scroll waits for the first layout
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    // Far above (a picked conversation, Jump from deep in the history): to the last row first, with no animation.
    if ((layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) < last) scrollToItem(last)
    // Then on to its end, one screen at a time. A scroll measures this frame's content and stops at its end.
    do { val moved = scrollBy(layoutInfo.viewportSize.height.toFloat()) } while (moved > 0f && canScrollForward)
}

/** Runs the last prompt again: a quiet icon under an answer, the icon and its word after a failed turn (the desktop's two). */
@Composable
private fun RetryAction(failed: Boolean, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(R.string.chat_retry)
    Row(Modifier.clip(CircleShape).clickable(onClick = onRetry).testTag("retry").heightIn(min = 48.dp)
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(HermesIcons.Retry, if (failed) null else label, Modifier.size(16.dp), tint = muted)
        if (failed) Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = muted)
    }
}

/** Under a finished answer: Copy (its Markdown, DC-B13), and Retry when it is the last (the desktop's action bar). */
@Composable
private fun ReplyActions(reply: Entry.Reply, onCopy: ((String) -> Unit)?, onRetry: (() -> Unit)?) {
    Row(Modifier.testTag("reply-actions"), verticalAlignment = Alignment.CenterVertically) {
        onCopy?.let { CopyButton(reply.text, it, stringResource(R.string.chat_copy), Modifier.testTag("copy-reply")) }
        onRetry?.let { RetryAction(failed = reply.status != TurnStatus.COMPLETE, it) }
    }
}

/**
 * Copy, as the desktop's control: one tap hands [text] to [onCopy], and the icon reads "Copied" for 1.5 s
 * (copy-button.tsx:15,119-124,142). Which control was just used is this view's own state (§2.5, V5).
 */
@Composable
internal fun CopyButton(text: String, onCopy: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_500); copied = false } }
    Row(modifier.clip(CircleShape).clickable(onClickLabel = label) { onCopy(text); copied = true }.heightIn(min = 48.dp)
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (copied) HermesIcons.Check else HermesIcons.Copy, if (copied) stringResource(R.string.chat_copied) else label,
             Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Back to the newest row, following it again (chat-17): Desktop's jump control, made round for a phone. */
@Composable
private fun JumpToLatest(modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick, modifier.size(40.dp).testTag("jump-latest"), shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, Hermes.colors.stroke), shadowElevation = 2.dp) {
        Box(contentAlignment = Alignment.Center) {
            Icon(HermesIcons.ArrowDown, stringResource(R.string.chat_scroll_to_bottom), Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, onCopy: ((String) -> Unit)?, retry: (() -> Unit)?) {
    when (entry) {
        is Entry.User -> UserBubble(entry.text, onCopy)
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
            if (entry.text.isNotEmpty()) Reply(entry.text, onCopy)
            // The core often words the error into the reply itself; show it apart only when it did not.
            entry.error?.takeUnless { it in entry.text }?.let { Problem(it) }
            val copy = onCopy?.takeIf { entry.text.isNotBlank() }
            if (copy != null || retry != null) ReplyActions(entry, copy, retry)
        }
        // Commentary reads like the agent's words, without the reply's reasoning fold (and, later, its actions).
        is Entry.Interim -> Column(Modifier.fillMaxWidth().testTag("entry-interim")) { Reply(entry.text, onCopy) }
        is Entry.Tool -> ToolRow(entry)
        is Entry.Notice -> Problem(entry.text, Modifier.testTag("entry-notice"))
    }
}

/** The agent's words: Markdown drawn flat across the column, selectable like the desktop's. */
@Composable
private fun Reply(text: String, onCopy: ((String) -> Unit)? = null) {
    SelectionContainer { MarkdownText(text, Modifier.fillMaxWidth(), onCopy = onCopy) }
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

/** The user's words in a bubble; a long press offers Copy (V6: the drawer row's menu, a phone has no hover bar). */
@Composable
private fun UserBubble(text: String, onCopy: ((String) -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    var menu by remember { mutableStateOf(false) }
    val press = onCopy?.let {
        Modifier.combinedClickable(onClick = {}, onLongClickLabel = stringResource(R.string.chat_message_actions), onLongClick = { menu = true })
    } ?: Modifier
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box {
            Text(text, Modifier.widthIn(max = 320.dp).clip(shape).then(press).background(Hermes.colors.userBubble, shape)
                     .border(1.dp, Hermes.colors.userBubbleBorder, shape).padding(horizontal = 14.dp, vertical = 10.dp),
                 style = MaterialTheme.typography.bodyLarge)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_copy)) }, onClick = { menu = false; onCopy?.invoke(text) },
                                 Modifier.testTag("copy-message"), leadingIcon = { Icon(HermesIcons.Copy, null, Modifier.size(16.dp)) })
            }
        }
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
private fun Composer(chat: Chat, onSend: (String) -> Unit, onStop: () -> Unit, onSetup: (() -> Unit)?, onContext: (() -> Unit)?) {
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
            chat.meter?.let { ContextChip(it, onContext) }
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

/** The agent's plan above the composer: "Tasks 2/5" and the item it is on, the whole list one tap away. */
@Composable
private fun TodoStrip(todos: TodoList) {
    val shape = RoundedCornerShape(14.dp)
    var open by rememberSaveable { mutableStateOf(false) }  // collapsed to a count by default (DC-B15)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp)
               .background(MaterialTheme.colorScheme.surfaceContainer, shape).border(1.dp, Hermes.colors.stroke, shape).testTag("todo-strip")) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.testTag("todo-toggle").heightIn(min = 44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(HermesIcons.Checklist, null, Modifier.size(15.dp), tint = muted)
            Text(stringResource(R.string.chat_tasks, todos.done, todos.items.size), Modifier.padding(start = 8.dp),
                 style = MaterialTheme.typography.labelLarge)
            if (!open) {
                Text(todos.current?.content.orEmpty(), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                     color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Spacer(Modifier.weight(1f))
            Icon(if (open) HermesIcons.ChevronDown else HermesIcons.ChevronRight, null, Modifier.size(14.dp), tint = muted)
        }
        if (open) {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                todos.tree().forEach { (todo, depth) -> TodoRow(todo, depth) }
            }
        }
    }
}

/** One item of the plan, indented under its parent; resolved items are quieter (the desktop's status-row). */
@Composable
private fun TodoRow(todo: Todo, depth: Int) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.testTag("todo-item").padding(start = (depth * 16).dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when (todo.status) {
                TodoStatus.PENDING -> Icon(HermesIcons.Circle, null, Modifier.size(14.dp), tint = Hermes.colors.textTertiary)
                TodoStatus.IN_PROGRESS -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = muted)
                TodoStatus.COMPLETED -> Icon(HermesIcons.CircleCheck, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                TodoStatus.CANCELLED -> Icon(HermesIcons.Ban, null, Modifier.size(14.dp), tint = Hermes.colors.textTertiary)
            }
        }
        val resolved = todo.status == TodoStatus.COMPLETED || todo.status == TodoStatus.CANCELLED
        Text(todo.content, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
             color = if (resolved) muted else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** How full the context is: a small ring and the percent; the error colour near the window's end. [onClick] opens its detail. */
@Composable
private fun ContextChip(meter: ContextMeter, onClick: (() -> Unit)?) {
    val tint = if (meter.warns) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val approx = if (meter.estimated) "~" else ""
    val percent = "$approx${meter.percent}%"
    val said = stringResource(R.string.chat_context, percent, approx + compactTokens(meter.used), compactTokens(meter.max))
    val opens = onClick?.let { Modifier.clickable(onClickLabel = stringResource(R.string.context_open), role = Role.Button, onClick = it) } ?: Modifier
    Row(Modifier.padding(end = 8.dp).clip(CircleShape).then(opens).padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag("context-meter").semantics(mergeDescendants = true) { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(progress = { meter.percent / 100f }, Modifier.size(14.dp), color = tint, strokeWidth = 2.dp,
                                  trackColor = Hermes.colors.stroke)
        Text(percent, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
