package com.nousresearch.hermes.android.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.entry.IncomingText
import com.nousresearch.hermes.android.setup.ModelRow
import com.nousresearch.hermes.android.setup.Readiness
import com.nousresearch.hermes.android.ui.CodeBox
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesIcons
import com.nousresearch.hermes.android.ui.PrimaryButton
import com.nousresearch.hermes.android.ui.SecondaryButton
import com.nousresearch.hermes.android.ui.Wordmark
import com.nousresearch.hermes.bridge.protocol.TurnStatus
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.random.Random

/** The places the chat leads to (the drawer's links, the top bar's new chat, the composer's model pill); a null one has no way there. */
class ChatPlaces(val setup: (() -> Unit)? = null, val terminal: (() -> Unit)? = null, val gateway: (() -> Unit)? = null,
                 val settings: (() -> Unit)? = null, val newChat: (() -> Unit)? = null, val jobs: (() -> Unit)? = null)

/** What the chat's Export does (sessions-12): [export] the chat's own session to the share sheet, [dismiss] a failure's words. */
class ExportActions(val export: () -> Unit, val dismiss: () -> Unit)

/** What the chat's Status does (protocol-21): [open] the sheet with the core's `/status` text, [close] it. */
class StatusActions(val open: () -> Unit, val close: () -> Unit)

/**
 * What the chat's model picker does (models-08): [open] it from the pill, [close] it, [query] the field, [addCustom]
 * turn the field into a model id, [pick] a row (switch this chat to it), [confirm] or [keep] when the core asks first,
 * and [addProvider] close it and open Setup (where the default model is set).
 */
class ModelActions(val open: () -> Unit, val close: () -> Unit, val query: (String) -> Unit, val addCustom: () -> Unit,
                   val pick: (ModelRow) -> Unit, val confirm: () -> Unit, val keep: () -> Unit, val addProvider: () -> Unit)

/**
 * A chat in the desktop's shape: the transcript is the page, replies flat across it and the user's words in
 * bubbles, the composer at the bottom naming the model, and a drawer with the sessions and the app's other places.
 * It holds only the draft; [onSend] gets the trimmed text, [onRetry] runs the last prompt again. [onEdit] sends an edited
 * prompt again from its place (chat-12) and hands what became of it to its callback: a long press on a sent prompt offers
 * Edit message; a cut that archives later turns asks first. The editor keeps the typed words until the edit is taken
 * (Q7): a refusal reopens it with them and why, on the prompt or the core's copy of it; with no such prompt left they
 * join the draft, never sent. [recovery] is where a failed answer's card sends its fixes (chat-16): the newest failed answer, while
 * nothing runs, offers one main fix and at most one second; every failed answer opens its details. Without it a failed
 * answer shows its words and Retry, as before. [onSessions] hears the drawer open (load the sessions)
 * and close; [sessionActions] is the rows' menu (rename, delete). [contextActions] opens the context sheet from the meter,
 * compresses and closes it; [context] is that sheet while it is open. [places] are where the drawer, the top bar, the
 * model pill and a notice's shortcut lead. The transcript follows a streaming reply only while the reader is at its end;
 * Jump brings it back (chat-17). [onCopy] puts an answer, one of the user's messages, a code block or a part of a tool
 * call on the clipboard (chat-09, chat-10, chat-08); without it nothing offers Copy. [queue] is the chat's queue of unsent messages, shown above the
 * composer with each row's actions, and [queueActions] what they do and what the composer's Queue button adds to it
 * (chat-19); without them the chat offers no queue. [chat]'s offer is taken into the composer's draft once and handed
 * back to [onOfferTaken]; the strip above the field offers [places]' New chat. [readiness] marks the model pill (F6);
 * while it is not ready a tap shows the core's words, with setup and [onCheckReadiness]. [chat]'s billing wall shows above
 * the composer until dismissed ([onDismissWall]); its action opens the core's page through [onOpenUrl], else setup (models-23).
 * The top bar's ⋮ menu offers Status ([statusActions]; [status] is that sheet while it is open) and Export of the chat's own
 * session ([exportActions]; [exportUi] shows one on its way and a failure's words); without their actions there is no menu.
 * [models] is this chat's model picker while it is open and [modelActions] what it does: a ready pill opens it; without
 * them the pill opens setup (models-08).
 * [now] is the wall clock a message's time is read against when a tap shows it (a test passes its own), as `SetupScreen`'s.
 * [onReact] reacts to an answer as the user (chat-14): React in an answer's action row offers the six quick reactions,
 * and the user's chip removes its own. Without it the switch is off: no React, and a stored reaction shows without a tap
 * (D3).
 * [onSlash] lists the slash menu for a draft that starts with `/` (chat-20); without it there is no menu. [chat]'s
 * prefill (`/undo`'s words) replaces the draft once and goes back to [onPrefillTaken].
 * [dictation] is the phone's speech recognizer's intent when an activity answers it (chat-30, D48): the composer's mic
 * starts it and what it heard joins the draft, never sent; without it there is no mic.
 */
@Composable
fun ChatScreen(chat: Chat, onSend: (String) -> Unit, modifier: Modifier = Modifier, answers: PromptAnswers? = null,
               sessions: HistoryUi? = null, onSessions: (open: Boolean) -> Unit = {}, onPickSession: (StoredSessionId) -> Unit = {},
               sessionActions: SessionActions? = null, context: ContextUi? = null, contextActions: ContextActions? = null,
               places: ChatPlaces? = null, onStop: () -> Unit = {}, onRetry: (() -> Unit)? = null,
               onEdit: ((Rewind, (Resend) -> Unit) -> Unit)? = null, recovery: RecoveryRoutes? = null, onCopy: ((String) -> Unit)? = null, about: String? = null, queue: QueueUi = QueueUi(),
               queueActions: QueueActions? = null, onOfferTaken: (IncomingText) -> Unit = {}, readiness: Readiness? = null,
               onCheckReadiness: (() -> Unit)? = null, onOpenUrl: ((String) -> Unit)? = null, onDismissWall: () -> Unit = {},
               exportUi: ExportUi = ExportUi(), exportActions: ExportActions? = null, status: StatusUi? = null,
               statusActions: StatusActions? = null, models: PickerUi? = null, modelActions: ModelActions? = null,
               now: () -> Long = System::currentTimeMillis, onReact: ((stableId: Long, emoji: String?) -> Unit)? = null,
               onSlash: (suspend (draft: String) -> List<SlashItem>)? = null, onPrefillTaken: (String) -> Unit = {},
               dictation: Intent? = null) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var picked by remember { mutableIntStateOf(0) }  // a conversation picked in the drawer is shown from its end (V5)
    var kept by remember { mutableStateOf<String?>(null) }  // a refused edit's words with no prompt left to edit, for the draft (Q7)
    LaunchedEffect(drawer) { snapshotFlow { drawer.isOpen }.distinctUntilChanged().drop(1).collect(onSessions) }
    // Back closes an open drawer before it leaves the app.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    fun leaveTo(go: () -> Unit) = {
        scope.launch { drawer.snapTo(DrawerValue.Closed) }
        go()
    }
    val links = listOfNotNull(
        places?.newChat?.let { DrawerLink(HermesIcons.NewChat, stringResource(R.string.chat_new), "drawer-new-chat", leaveTo(it)) },
        places?.gateway?.let { DrawerLink(HermesIcons.Gateway, stringResource(R.string.chat_gateway), "open-gateway", leaveTo(it)) },
        places?.jobs?.let { DrawerLink(HermesIcons.Jobs, stringResource(R.string.chat_jobs), "open-jobs", leaveTo(it)) },
        places?.terminal?.let { DrawerLink(HermesIcons.Terminal, stringResource(R.string.chat_terminal), "open-ssh", leaveTo(it)) },
        places?.settings?.let { DrawerLink(HermesIcons.Settings, stringResource(R.string.chat_settings), "open-settings", leaveTo(it)) },
    )
    val hasDrawer = links.isNotEmpty() || sessions != null || about != null
    ModalNavigationDrawer(drawerContent = {
        ModalDrawerSheet(Modifier.width(304.dp), drawerShape = RectangleShape,
                         drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            SessionDrawerContent(links, sessions, onPick = { id -> scope.launch { drawer.close() }; picked++; onPickSession(id) }, about, sessionActions)
        }
    }, modifier, drawerState = drawer, gesturesEnabled = hasDrawer) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            TopBar(onMenu = if (hasDrawer) ({ scope.launch { drawer.open() } }) else null, places?.newChat, statusActions?.open,
                   exportActions?.export, exporting = exportUi.busy)
            if (exportUi.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("export-busy"))
            if (chat.entries.isEmpty() && !chat.running && chat.prompts.isEmpty()) Intro(Modifier.weight(1f))
            else Transcript(chat, onRetry, onEdit, onCopy, recovery, picked, places?.gateway, now, onReact, { kept = it }, Modifier.weight(1f))
            // The billing wall tops the stack above the composer: a blocker outranks the rest (D7); the composer stays usable.
            chat.wall?.let { BillingBanner(it, onOpenUrl, places?.setup, onDismissWall) }
            // One question at a time, the oldest first: the turn waits on it.
            if (answers != null) chat.prompts.firstOrNull()?.let { PromptCard(it, answers) }
            // The agent's plan while it works through it (D1: a turn that ended leaves no stale plan pinned).
            chat.todos?.takeIf { chat.running && it.active }?.let { TodoStrip(it) }
            // The messages that wait for a turn's end, in the order they go out (chat-19).
            queueActions?.takeIf { queue.items.isNotEmpty() }?.let { QueuePanel(queue, chat.busy, chat.steerMode != null, it) }
            Composer(chat, onSend, onStop, places?.setup, contextActions?.open, queueActions?.add, chat.offer, onOfferTaken, places?.newChat,
                     readiness, onCheckReadiness, modelActions?.open, onSlash, onPrefillTaken, dictation, kept) { kept = null }
            context?.let { ContextSheet(it, running = chat.busy, compressing = chat.compressing, onCompress = contextActions?.compress ?: {},
                                          onClose = contextActions?.close ?: {}) }
            status?.let { StatusSheet(it, onClose = statusActions?.close ?: {}) }
            models?.let { ui -> modelActions?.let { ModelSwitchSheet(ui, it, chat.model, now) } }
            exportUi.error?.let { ExportFailed(it, onDismiss = exportActions?.dismiss ?: {}) }
        }
    }
}

@Composable
private fun TopBar(onMenu: (() -> Unit)?, onNewChat: (() -> Unit)?, onStatus: (() -> Unit)?, onExport: (() -> Unit)?, exporting: Boolean) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onMenu != null) {
            IconButton(onMenu, Modifier.testTag("open-drawer")) { Icon(HermesIcons.Menu, stringResource(R.string.chat_menu), Modifier.size(22.dp)) }
        } else Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.app_name), Modifier.weight(1f).padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium)
        onNewChat?.let {
            IconButton(it, Modifier.testTag("new-chat")) { Icon(HermesIcons.NewChat, stringResource(R.string.chat_new), Modifier.size(20.dp)) }
        }
        if (onStatus != null || onExport != null) {
            var open by remember { mutableStateOf(false) }  // the menu's open flag: transient UI
            Box {
                IconButton({ open = true }, Modifier.testTag("chat-more")) {
                    Icon(HermesIcons.More, stringResource(R.string.chat_more), Modifier.size(20.dp))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    onStatus?.let {
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_status)) }, onClick = { open = false; it() },
                                         Modifier.testTag("chat-status"), leadingIcon = { Icon(HermesIcons.Info, null, Modifier.size(16.dp)) })
                    }
                    onExport?.let {
                        DropdownMenuItem(text = { Text(stringResource(R.string.session_export)) }, onClick = { open = false; it() },
                                         Modifier.testTag("chat-export"), enabled = !exporting,
                                         leadingIcon = { Icon(HermesIcons.Download, null, Modifier.size(16.dp)) })
                    }
                }
            }
        }
    }
}

/** An export that failed, titled in Desktop's words over the failure's own; a successful one needs no notice (D11). */
@Composable
private fun ExportFailed(words: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("export-error"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                title = { Text(stringResource(R.string.export_failed)) },
                text = { Text(words) },
                confirmButton = { PrimaryButton(stringResource(R.string.export_ok), onDismiss, Modifier.testTag("export-ok")) })
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
private fun Transcript(chat: Chat, onRetry: (() -> Unit)?, onEdit: ((Rewind, (Resend) -> Unit) -> Unit)?, onCopy: ((String) -> Unit)?,
                       recovery: RecoveryRoutes?, picked: Int,
                       onGateway: (() -> Unit)?, now: () -> Long, onReact: ((Long, String?) -> Unit)?, onKeep: (String) -> Unit,
                       modifier: Modifier) {
    val list = rememberLazyListState()
    val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)  // the phone's own clock setting (D3)
    // chat-17 (F9, DC-B16): whether the list follows its newest row belongs to the view, never to the conversation.
    var following by rememberSaveable { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()  // the reader's finger only, never a scroll of ours
    val retry = onRetry?.takeIf { chat.retryTarget() != null }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }  // the prompt being edited, by its entry's id: the view's own
    val target = editing?.let(chat::editTarget)
    LaunchedEffect(target == null) { if (target == null) editing = null }  // gone, compressing, a job's run: the editor closes
    var restoring by remember { mutableStateOf<Rewind?>(null) }  // an edit that waits for "Restore & rerun"
    // The edit on its way and the refusal its editor shows (Q7). Neither is saved across an activity's recreation: a
    // Rewind has no Saver, and the outcome goes to the old scope; after one the editor reopens from [editing] with its
    // saved draft and Send on.
    var pending by remember { mutableStateOf<PendingEdit?>(null) }
    var refusal by remember { mutableStateOf<EditRefusal?>(null) }
    val scope = rememberCoroutineScope()
    val shown by rememberUpdatedState(chat)  // the transcript when an outcome arrives, not when its edit left
    val keep by rememberUpdatedState(onKeep)
    /** [p]'s outcome, unless a newer send or a Cancel replaced it: taken or left closes the editor; a refusal reopens it. */
    fun settle(p: PendingEdit, outcome: Resend) {
        if (pending !== p) return
        pending = null
        when (outcome) {
            Resend.Taken, Resend.Left -> editing = null
            is Resend.Refused -> {
                val old = p.rewind.target
                // The prompt itself when nothing was cut, else the core's re-read copy of it at its place (a new id, V4).
                val id = p.id.takeIf { shown.editTarget(it)?.text == old.text }
                    ?: (shown.entries.getOrNull(old.index) as? Entry.User)?.stableId?.takeIf { shown.editTarget(it)?.text == old.text }
                if (id != null) {
                    editing = id
                    refusal = EditRefusal(id, p.rewind.text, outcome.why)
                } else {  // no bubble left to edit: the words join the draft, never sent
                    editing = null
                    keep(p.rewind.text)
                }
            }
        }
    }
    /** Sends [rewind] and keeps its editor open, its words in it, until the outcome (Q7). */
    fun admit(rewind: Rewind) {
        val edit = onEdit ?: return
        val p = PendingEdit(rewind, editing ?: return)
        pending = p
        refusal = null
        // The outcome comes on the app's scope; it is settled on the main thread, and dropped once this view is gone.
        edit(rewind) { outcome -> scope.launch { settle(p, outcome) } }
    }
    fun send(rewind: Rewind) { if (chat.laterTurns(rewind.target) > 0) restoring = rewind else admit(rewind) }
    // The newest failed answer, while nothing runs, offers its fixes (D8: retry's own gate); an older one keeps its details.
    val fixing = recovery?.takeIf { chat.retryTarget() != null }
    val editable = onEdit?.let { chat.retryTarget() }?.let { chat.entries[it.index].stableId }?.takeIf { chat.editTarget(it) != null }
    /** [action]'s run for [failure], or null: its route cannot act now, so no button (as Desktop's renderers return null). */
    fun fix(action: RecoveryAction, failure: Failure, routes: RecoveryRoutes): (() -> Unit)? = when (action) {
        RecoveryAction.RETRY -> routes.retry
        RecoveryAction.CONTINUE -> { { routes.send(CONTINUE_WORDS) } }
        RecoveryAction.CHOOSE_MODEL -> routes.pickModel
        RecoveryAction.SWITCH_PROVIDER -> routes.setup
        RecoveryAction.UPDATE_KEY, RecoveryAction.SIGN_IN_AGAIN -> failure.provider?.let { p -> { routes.reauth(p) } }
        RecoveryAction.NEW_CHAT -> routes.newChat
        RecoveryAction.COMPRESS -> routes.compress
        RecoveryAction.EDIT_MESSAGE -> editable?.let { id -> { editing = id } }  // P7's editor on the failed turn's prompt
        RecoveryAction.OPEN_BILLING -> failure.billingUrl?.let { url -> { routes.openUrl(url) } }
    }
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
            // Each row's state (the reasoning fold, the bubble's menu) is kept by its entry's id, not its place: a rewind or a
            // re-read transcript never hands it to another row (/improve 20261113T221531Z #8).
            itemsIndexed(chat.entries, key = { _, entry -> entry.stableId }) { i, entry ->
                if (entry is Entry.User && editing == entry.stableId && target != null) {
                    val refused = refusal?.takeIf { it.id == entry.stableId }
                    EditBubble(entry, start = refused?.words ?: entry.text, why = refused?.why, waiting = pending != null,
                               onSend = { words -> send(Rewind(target, words, deep = false)) },
                               onCancel = { editing = null; pending = null; refusal = null })
                } else {
                    val edit = onEdit?.takeIf { chat.editTarget(entry.stableId) != null }?.let { { editing = entry.stableId } }
                    val failure = (entry as? Entry.Reply)?.failure
                    val card = if (failure == null || recovery == null) null else {
                        val routes = fixing?.takeIf { i == chat.entries.lastIndex }  // the newest answer only (D8)
                        val fixes = routes?.let { r ->
                            val plan = RecoveryPlan.of(failure, failure.provider?.let(r.auth))
                            listOfNotNull(plan.main, plan.second).mapNotNull { a -> fix(a, failure, r)?.let { run -> label(a) to run } }
                        }.orEmpty()
                        RecoveryUi(fixes, details = failure.details((entry as Entry.Reply).at, recovery.app))
                    }
                    // Only an answer the core stored, outside a job's run (D2, D10), and only while the switch is on (D3).
                    val react = onReact?.let { r -> chat.reactable(entry.stableId)?.let { { emoji: String? -> r(entry.stableId, emoji) } } }
                    EntryRow(entry, onCopy, edit, retry.takeIf { i == chat.entries.lastIndex && entry is Entry.Reply }, onGateway, now, h24,
                             card, react)
                }
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
    // Cancel keeps the editor and its words; Restore & rerun sends the edit with the user's consent to the deep cut.
    restoring?.let { r ->
        RestoreDialog(onConfirm = { admit(r.copy(deep = true)); restoring = null }, onDismiss = { restoring = null })
    }
}

/** An edit on its way: its [rewind] and the prompt [id] it was typed on. Equal only to itself: each send is its own. */
private class PendingEdit(val rewind: Rewind, val id: Long)

/** A refused edit's editor to show: on prompt [id], with the typed [words] and [why] it was refused. */
private data class EditRefusal(val id: Long, val words: String, val why: String)

/**
 * A sent prompt being edited, in its bubble's place: [start]'s words in a focused field, Cancel (or Back) and the send
 * "Send edited message", off while the words are empty or unchanged (D7) and while the edit is on its way ([waiting]).
 * [why] a refused edit was not taken, beneath the field (Q7).
 */
@Composable
private fun EditBubble(user: Entry.User, start: String, why: String?, waiting: Boolean, onSend: (String) -> Unit, onCancel: () -> Unit) {
    var draft by rememberSaveable(user.stableId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(start, TextRange(start.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(user.stableId) { focus.requestFocus() }
    // The keyboard opens after the focus and shrinks the transcript: the editor and its send are brought back into view
    // as it does, so a prompt near the end is not edited under the keyboard.
    val shown = remember { BringIntoViewRequester() }
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(keyboard) { if (keyboard > 0) shown.bringIntoView() }
    BackHandler(onBack = onCancel)
    val ok = draft.text.isNotBlank() && draft.text.trim() != user.text.trim()
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(shown), horizontalAlignment = Alignment.End) {
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag("edit-field"), maxLines = 6)
        why?.let {
            Text(it, Modifier.fillMaxWidth().padding(top = 4.dp).testTag("edit-refused"), color = MaterialTheme.colorScheme.error,
                 style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onCancel, Modifier.testTag("edit-cancel")) { Text(stringResource(R.string.chat_edit_cancel)) }
            Spacer(Modifier.width(8.dp))
            FilledIconButton({ onSend(draft.text.trim()) }, Modifier.size(36.dp).testTag("edit-send"), enabled = ok && !waiting) {
                Icon(HermesIcons.Send, stringResource(R.string.chat_send_edited), Modifier.size(18.dp))
            }
        }
    }
}

/** Asks before an edit cuts the turns after its prompt, in the desktop's words (`rewind.ts:546-555`). */
@Composable
private fun RestoreDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("restore-dialog"),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                title = { Text(stringResource(R.string.chat_restore_title)) },
                text = { Text(stringResource(R.string.chat_restore_body)) },
                confirmButton = { PrimaryButton(stringResource(R.string.chat_restore_confirm), onConfirm, Modifier.testTag("restore-confirm")) },
                dismissButton = { SecondaryButton(stringResource(R.string.chat_edit_cancel), onDismiss, Modifier.testTag("restore-cancel")) })
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

/**
 * Under a finished answer: Copy (its Markdown, DC-B13) and Retry when it is the last (the desktop's action bar), the
 * user's reaction and React ([react], chat-14), then its time when a tap showed it and how long its turn took when this
 * app measured it (chat-15).
 */
@Composable
private fun ReplyActions(reply: Entry.Reply, onCopy: ((String) -> Unit)?, onRetry: (() -> Unit)?, time: String?,
                         react: ((String?) -> Unit)?) {
    Row(Modifier.fillMaxWidth().testTag("reply-actions"), verticalAlignment = Alignment.CenterVertically) {
        onCopy?.let { CopyButton(reply.text, it, stringResource(R.string.chat_copy), Modifier.testTag("copy-reply")) }
        onRetry?.let { RetryAction(failed = reply.status != TurnStatus.COMPLETE, it) }
        val mine = reply.reactions.firstOrNull { it.author == Author.USER }?.emoji
        mine?.let { ReactionChip(it, onRemove = react?.let { r -> { r(null) } }) }
        react?.let { ReactButton(mine, onPick = it) }
        Spacer(Modifier.weight(1f))
        time?.let {
            Text(it, Modifier.padding(horizontal = 4.dp).testTag("message-time"), style = MaterialTheme.typography.labelSmall,
                 color = Hermes.colors.textTertiary)
        }
        // Only a turn this app saw start is timed (invariant 7): no length is ever derived from the entries' times.
        reply.tookMs?.let { TurnDuration(it) }
    }
}

/**
 * How long a turn this app saw start ran, [tookMs] on its clock: "⏱ 1:04", read by TalkBack as "This turn took 1:04".
 * The one presentation for every timed turn, an answer's and a bare `error`'s notice's (chat-15).
 */
@Composable
private fun TurnDuration(tookMs: Long) {
    val took = turnLength(tookMs)
    val said = stringResource(R.string.chat_turn_took, took)
    Text(stringResource(R.string.chat_turn_length, took),
         Modifier.padding(horizontal = 4.dp).testTag("turn-duration").semantics { contentDescription = said },
         style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The words a tap on a message shows for [at] (epoch ms) while [shown], read against [now] once at the tap (D8) in the
 * phone's own clock ([h24]); null while hidden.
 */
@Composable
private fun timeText(at: Long, shown: Boolean, now: () -> Long, h24: Boolean): String? {
    if (!shown) return null
    val nowMs = remember(shown) { now() }
    return when (val time = MessageTime.of(at, ShownAt(nowMs, h24))) {
        is MessageTime.Today -> time.clock
        is MessageTime.Yesterday -> stringResource(R.string.chat_time_yesterday, time.clock)
        is MessageTime.Day -> time.text
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

/**
 * The user's own reaction under an answer, flat as Desktop's badge: no pill, no border, no fill. With [onRemove] a tap
 * takes it back ("Remove {emoji} reaction"); without it (the switch off, D3) it is words only.
 */
@Composable
private fun ReactionChip(emoji: String, onRemove: (() -> Unit)?) {
    val label = stringResource(R.string.chat_reaction_remove, emoji)
    val tap = onRemove?.let {
        Modifier.clip(CircleShape).clickable(onClickLabel = label, onClick = it).semantics { contentDescription = label }
            .heightIn(min = 48.dp).widthIn(min = 48.dp)
    } ?: Modifier
    Box(Modifier.testTag("reaction-chip").then(tap).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(emoji, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * React, built like Copy: one tap opens one row of the six quick reactions ([QUICK], DC-P16), the user's current one
 * ([mine]) marked; a pick closes the row and goes to [onPick]. The core applies it: the marked one again retracts.
 */
@Composable
private fun ReactButton(mine: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }  // the row's open flag: transient UI
    val label = stringResource(R.string.chat_react)
    Box {
        Row(Modifier.clip(CircleShape).clickable(onClickLabel = label) { open = true }.testTag("react").heightIn(min = 48.dp)
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HermesIcons.MoodPlus, label, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row {  // 6 × 48 dp = 288 dp: one row at a phone's width
                QUICK.forEachIndexed { i, emoji ->
                    val selected = emoji == mine
                    val tint = if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape) else Modifier
                    Box(Modifier.size(48.dp).then(tint).clip(CircleShape)
                            .selectable(selected = selected, onClick = { open = false; onPick(emoji) })
                            .testTag("quick-reaction-$i").semantics { contentDescription = emoji }, contentAlignment = Alignment.Center) {
                        Text(emoji, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
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
private fun EntryRow(entry: Entry, onCopy: ((String) -> Unit)?, onEdit: (() -> Unit)?, retry: (() -> Unit)?, onGateway: (() -> Unit)?,
                     now: () -> Long, h24: Boolean, card: RecoveryUi?, react: ((String?) -> Unit)?) {
    when (entry) {
        is Entry.User -> UserBubble(entry, onCopy, onEdit, now, h24)
        is Entry.Reply -> {
            var timeShown by rememberSaveable { mutableStateOf(false) }  // the time a tap showed: this view's own state
            val label = stringResource(if (timeShown) R.string.chat_hide_time else R.string.chat_show_time)
            // A tap on its words shows when it came (D1, chat-15): a bare tap, not a click, so its text and code blocks stay
            // their own nodes and no ripple spans it (D2); a tap a child takes (a link, a code block's Copy, the fold, an
            // action) never reaches it. A quick second press is claimed before SelectionContainer's word selection (Compose
            // foundation 1.12.1 takes it as `touchSelectionSubsequentPress`): a heart with reactions on, as Desktop's
            // double-click, else the time again.
            val toggle by rememberUpdatedState(entry.at?.let { { timeShown = !timeShown } })
            val heart by rememberUpdatedState(react?.let { r -> { r(entry.reactions.tapback()) } })
            val tap = (entry.at?.let { Modifier.semantics { onClick(label = label) { timeShown = !timeShown; true } } } ?: Modifier)
                .then(if (entry.at != null || react != null)
                          Modifier.answerTaps(onTap = entry.at?.let { { toggle?.invoke() } }, onDoubleTap = react?.let { { heart?.invoke() } })
                      else Modifier)
            Column(Modifier.fillMaxWidth().then(tap).testTag("entry-reply")) {
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
                if (card != null) RecoveryCard(entry, card, onCopy) else entry.error?.takeUnless { it in entry.text }?.let { Problem(it) }
                val copy = onCopy?.takeIf { entry.text.isNotBlank() }
                val time = entry.at?.let { timeText(it, timeShown, now, h24) }
                val again = retry.takeIf { card == null }  // the card owns Retry: it never shows twice
                if (copy != null || again != null || entry.tookMs != null || time != null || react != null || entry.reactions.isNotEmpty())
                    ReplyActions(entry, copy, again, time, react)
            }
        }
        // Commentary reads like the agent's words, without the reply's reasoning fold (and, later, its actions).
        is Entry.Interim -> Column(Modifier.fillMaxWidth().testTag("entry-interim")) { Reply(entry.text, onCopy) }
        is Entry.Tool -> ToolRow(entry, onCopy)
        is Entry.Notice -> Column(Modifier.fillMaxWidth().testTag("entry-notice"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Problem(entry.text)
            // How long the turn a bare `error` ended ran, at the row's end as an answer's (invariant 7: null when unseen).
            entry.tookMs?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TurnDuration(it) } }
            // The way to turn the gateway on (D39 F11), when the chat has one; the words name the drawer path either way.
            if (entry.opens == NoticeShortcut.GATEWAY) onGateway?.let {
                SecondaryButton(stringResource(R.string.jobs_turn_on), it, Modifier.testTag("notice-open-gateway"))
            }
        }
    }
}

/**
 * An answer's own taps, read beside SelectionContainer without taking its gestures: a single tap on the words or between
 * them is [onTap] (the time); a quick second press near the first is this row's, never the selection's (its down is
 * consumed in [PointerEventPass.Initial], before the selection reads it): [onDoubleTap] (the heart), or with none [onTap]
 * again. A long press and a drag are the selection's and the list's (the first down is never consumed, and a press held
 * past the long-press timeout or moved is let go); a press a child takes (a link, a Copy, the fold, React, the chip) is the
 * child's (an up consumed below is no tap). With [onDoubleTap], a single tap waits out the double-tap timeout first, as
 * `detectTapGestures` does.
 */
private fun Modifier.answerTaps(onTap: (() -> Unit)?, onDoubleTap: (() -> Unit)?): Modifier =
    pointerInput(onTap != null, onDoubleTap != null) {
        val slop = DOUBLE_TAP_SLOP.toPx()
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial)
            while (true) {
                val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitOwnUp() }
                    ?: return@awaitEachGesture  // a long press, a drag or a child's tap
                if (onDoubleTap == null) onTap?.invoke()
                val next = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                }
                if (next == null || (next.position - up.position).getDistance() > slop) {
                    if (onDoubleTap != null) onTap?.invoke()
                    if (next == null) return@awaitEachGesture
                    continue  // a press elsewhere (a child, another line) starts afresh
                }
                next.consume()  // the row's second press: the selection skips a consumed down
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitOwnUp() } ?: return@awaitEachGesture
                (onDoubleTap ?: onTap)?.invoke()
                return@awaitEachGesture
            }
        }
    }

/**
 * The up of the press under way, read in [PointerEventPass.Final] after every child had it: null when a child consumed it
 * (its tap), when the press moved and a scroller consumed it, or when it left the row. The press's own down, met again in
 * this pass, is skipped (foundation's `waitForUpOrCancellation(Final)` reads one event ahead there and loses the up).
 */
private suspend fun AwaitPointerEventScope.awaitOwnUp(): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Final)
        if (event.changes.all { it.changedToDownIgnoreConsumed() }) continue
        if (event.changes.all { it.changedToUp() }) return event.changes[0]
        if (event.changes.any { it.isConsumed || it.isOutOfBounds(size, extendedTouchPadding) }) return null
    }
}

/** How far a second press may land from the first and still be the same double tap (a reader's finger, not a Copy below). */
private val DOUBLE_TAP_SLOP = 24.dp

/** A failed answer's card as drawn: its fixes ([fixes]: each label's string and its run, the main first) and its [details]. */
private class RecoveryUi(val fixes: List<Pair<Int, () -> Unit>>, val details: String)

/** Each fix's words, Desktop main's (D12): one label per action. */
@StringRes
private fun label(action: RecoveryAction): Int = when (action) {
    RecoveryAction.RETRY -> R.string.chat_retry
    RecoveryAction.CONTINUE -> R.string.recovery_continue
    RecoveryAction.CHOOSE_MODEL -> R.string.recovery_choose_model
    RecoveryAction.SWITCH_PROVIDER -> R.string.recovery_switch_provider
    RecoveryAction.UPDATE_KEY -> R.string.recovery_update_key
    RecoveryAction.SIGN_IN_AGAIN -> R.string.recovery_sign_in_nous
    RecoveryAction.NEW_CHAT -> R.string.recovery_new_chat
    RecoveryAction.COMPRESS -> R.string.recovery_compress
    RecoveryAction.EDIT_MESSAGE -> R.string.chat_edit_message
    RecoveryAction.OPEN_BILLING -> R.string.recovery_open_billing
}

/**
 * A failed answer's recovery card, as Desktop main's error card (bet 8, DC-P9): the core's words when the reply did not
 * already say them (D11), then its fixes, the first as the main button, and View error details, which opens the details
 * text with Copy details.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecoveryCard(reply: Entry.Reply, card: RecoveryUi, onCopy: ((String) -> Unit)?) {
    val shape = RoundedCornerShape(14.dp)
    var open by rememberSaveable { mutableStateOf(false) }  // the view's own state, kept by the row's key
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surfaceContainer, shape)
               .border(1.dp, Hermes.colors.stroke, shape).padding(12.dp).testTag("recovery-card"),
           verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reply.error?.takeUnless { it in reply.text }?.let { Problem(it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            card.fixes.getOrNull(0)?.let { (label, run) ->
                SecondaryButton(stringResource(label), run, Modifier.testTag("recovery-main"))  // Desktop: "the one action is secondary"
            }
            card.fixes.getOrNull(1)?.let { (label, run) ->
                TextButton(run, Modifier.testTag("recovery-second")) { Text(stringResource(label)) }  // Desktop's ghost link
            }
            TextButton({ open = !open }, Modifier.testTag("recovery-details-toggle")) {
                Text(stringResource(if (open) R.string.recovery_hide_details else R.string.recovery_view_details))
            }
        }
        if (open) {
            CodeBox(card.details, Modifier.testTag("recovery-details"))
            onCopy?.let {
                TextButton({ it(card.details) }, Modifier.testTag("recovery-copy-details")) { Text(stringResource(R.string.recovery_copy_details)) }
            }
        }
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

/**
 * The user's words in a bubble; a tap shows when it was sent (chat-15, DC-P18), a long press offers Copy and, with
 * [onEdit], Edit message (V6, DC-P8: the drawer row's menu, a phone has no hover bar). A correction sent while a turn ran says beneath it what the core did with it,
 * once the core answered.
 */
@Composable
private fun UserBubble(user: Entry.User, onCopy: ((String) -> Unit)?, onEdit: (() -> Unit)?, now: () -> Long, h24: Boolean) {
    val text = user.text
    val shape = RoundedCornerShape(14.dp)
    var menu by remember { mutableStateOf(false) }
    var shown by rememberSaveable { mutableStateOf(false) }  // the time a tap showed: this view's own state
    val timeLabel = user.at?.let { stringResource(if (shown) R.string.chat_hide_time else R.string.chat_show_time) }
    val hasMenu = onCopy != null || onEdit != null
    val menuLabel = if (hasMenu) stringResource(R.string.chat_message_actions) else null
    val press = if (hasMenu || user.at != null) {
        Modifier.combinedClickable(onClick = { if (user.at != null) shown = !shown }, onClickLabel = timeLabel,
                                   onLongClick = if (hasMenu) ({ menu = true }) else null, onLongClickLabel = menuLabel)
    } else Modifier
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Box {
            Text(text, Modifier.widthIn(max = 320.dp).clip(shape).then(press).background(Hermes.colors.userBubble, shape)
                     .border(1.dp, Hermes.colors.userBubbleBorder, shape).padding(horizontal = 14.dp, vertical = 10.dp),
                 style = MaterialTheme.typography.bodyLarge)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onCopy?.let {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_copy)) }, onClick = { menu = false; it(text) },
                                     Modifier.testTag("copy-message"), leadingIcon = { Icon(HermesIcons.Copy, null, Modifier.size(16.dp)) })
                }
                onEdit?.let {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_edit_message)) }, onClick = { menu = false; it() },
                                     Modifier.testTag("edit-message"), leadingIcon = { Icon(HermesIcons.Pencil, null, Modifier.size(16.dp)) })
                }
            }
        }
        user.at?.let { timeText(it, shown, now, h24) }?.let {
            Text(it, Modifier.padding(top = 4.dp).testTag("message-time"), style = MaterialTheme.typography.labelSmall,
                 color = Hermes.colors.textTertiary)
        }
        outcome(user.correction)?.let {
            Text(stringResource(it), Modifier.padding(top = 4.dp).testTag("correction"), style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the core did with a correction, in words; none before it answered, or when it turned the words away. */
private fun outcome(correction: Correction?): Int? = when (correction) {
    Correction.REDIRECTED -> R.string.chat_redirected
    Correction.STEERED -> R.string.chat_steered
    Correction.QUEUED_BY_CORE -> R.string.chat_runs_next
    Correction.REFUSED, null -> null
}

/** Send's name while a turn runs, by the core's busy mode. */
private fun label(mode: BusyMode): Int = when (mode) {
    BusyMode.INTERRUPT -> R.string.chat_redirect
    BusyMode.STEER -> R.string.chat_steer
    BusyMode.QUEUE -> error("queue mode shows Stop alone")  // Chat.busySend leaves it out (D3)
}

@Composable
private fun Problem(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(HermesIcons.Alert, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.error)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * The slash menu above the composer's field (chat-20): sections Commands, Skills and Options, a header only over rows
 * that exist; a tap on a row hands it to [onPick], which fills the line and sends nothing. No row: "No matching commands".
 * It takes at most 264 dp and only the room [modifier] leaves it: with the keyboard up, the field and Send keep theirs.
 */
@Composable
private fun SlashMenu(items: List<SlashItem>, modifier: Modifier, onPick: (SlashItem) -> Unit) {
    val words = stringResource(R.string.chat_slash_menu)
    Column(modifier.fillMaxWidth().testTag("slash-menu").semantics { contentDescription = words }) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 264.dp).verticalScroll(rememberScrollState())
                   .padding(top = 6.dp)) {
            if (items.isEmpty()) {
                Text(stringResource(R.string.chat_slash_none), Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("slash-empty")
                         .padding(horizontal = 16.dp, vertical = 14.dp),
                     style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOf(Triple(SlashKind.COMMAND, R.string.chat_slash_commands, "slash-section-commands"),
                   Triple(SlashKind.SKILL, R.string.chat_slash_skills, "slash-section-skills"),
                   Triple(SlashKind.OPTION, R.string.chat_slash_options, "slash-section-options")).forEach { (kind, header, tag) ->
                val rows = items.filter { it.kind == kind }
                if (rows.isEmpty()) return@forEach
                Text(stringResource(header), Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp).testTag(tag),
                     style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEach { item ->
                    val rowTag = if (kind == SlashKind.OPTION) "slash-option-${item.name}" else "slash-item-${item.name.removePrefix("/")}"
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(item) }.testTag(rowTag).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        if (item.description.isNotBlank()) {
                            Text(item.description, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hermes.colors.stroke))
    }
}

/**
 * The desktop's composer: a rounded field, the model it will ask beneath, and Send — or Stop while a turn runs or is
 * about to; Send waits while the session compresses, or while it is a job's run the gateway still runs. While a turn
 * runs, Send stands beside Stop, named by the core's busy mode (redirect or steer the run; the field says the same),
 * and Stop stays the only interrupt; Send waits in queue mode, before the turn starts, or while a question waits.
 * [onQueue] adds the draft to the chat's queue: its Queue button stands first, whenever a turn runs or is about to in a
 * session the chat may write to (D12); in queue mode it is the only send control, and the field says "Queue message".
 * An [offer] another app shared joins the draft once, after the user's own words (D6), and goes back to [onOfferTaken];
 * a strip says nothing is sent until Send, with [onNewChat] beside it, while those words are in the field (bet 8).
 * The model pill carries [readiness]'s mark (none while null); a ready pill opens [onModels], this chat's model picker,
 * else setup; while it is not ready a tap opens the core's words, setup and [onCheckReadiness] (D5, D6).
 * With [dictation], a mic before Send starts the phone's own speech recognizer, and what it heard joins the draft on a
 * new line, never sent (chat-30, D48); nothing heard leaves the draft as it was and says so above the field.
 */
@Composable
private fun Composer(chat: Chat, onSend: (String) -> Unit, onStop: () -> Unit, onSetup: (() -> Unit)?, onContext: (() -> Unit)?,
                     onQueue: ((String) -> Unit)?, offer: IncomingText?, onOfferTaken: (IncomingText) -> Unit, onNewChat: (() -> Unit)?,
                     readiness: Readiness?, onCheckReadiness: (() -> Unit)?, onModels: (() -> Unit)?,
                     onSlash: (suspend (String) -> List<SlashItem>)?, onPrefillTaken: (String) -> Unit, dictation: Intent?,
                     kept: String?, onKeptTaken: () -> Unit) {
    // A value, not a string: words set from outside (a pick, a prefill, an offer) leave the cursor at their end (D8).
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var shared by rememberSaveable { mutableStateOf(false) }  // the draft holds words another app shared
    var cut by rememberSaveable { mutableStateOf(false) }
    // Keyed on the value: once per offer; the cleared null runs nothing.
    LaunchedEffect(offer) {
        offer?.let {
            val joined = if (draft.text.isBlank()) it.draft else draft.text.trimEnd() + "\n\n" + it.draft
            draft = TextFieldValue(joined, TextRange(joined.length))
            shared = true
            cut = it.cut
            onOfferTaken(it)
        }
    }
    // A refused edit's words with no prompt left to edit (Q7): after the user's own, never sent, taken once as the offer is.
    LaunchedEffect(kept) {
        kept?.let {
            val joined = if (draft.text.isBlank()) it else draft.text.trimEnd() + "\n\n" + it
            draft = TextFieldValue(joined, TextRange(joined.length))
            onKeptTaken()
        }
    }
    // The core's words for the composer (`/undo`), taken once, as the offer is.
    LaunchedEffect(chat.prefill) {
        chat.prefill?.let {
            draft = TextFieldValue(it, TextRange(it.length))
            shared = false
            onPrefillTaken(it)
        }
    }
    // The slash menu (chat-20): only a leading `/` opens it; typing narrows it after a pause, so only the last keystroke
    // asks the core (DC-P15); a failed read shows none. Back closes it until the draft opens it again.
    var menu by remember { mutableStateOf<List<SlashItem>?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(draft.text, onSlash) {
        val ask = onSlash
        if (ask == null || !opensMenu(draft.text)) {
            menu = null
            dismissed = false
            return@LaunchedEffect
        }
        if (draft.text != "/") delay(SLASH_DEBOUNCE_MS)
        else dismissed = false
        menu = try {
            ask(draft.text)
        } catch (failed: Exception) {
            if (failed is CancellationException) throw failed
            null
        }
    }
    // Dictation (chat-30, D48): the recognizer answers text or nothing (EJ-55); words join the draft once, the cursor at
    // their end, and nothing is sent. [said] is the last dictation's words when it brought none; they go with a new draft.
    var said by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(draft.text) { said = null }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = heardOf(result.resultCode, result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
        if (heard == null) said = R.string.chat_dictation_none
        else {
            val joined = dictated(draft.text, heard)
            draft = TextFieldValue(joined, TextRange(joined.length))
        }
    }
    val listed = menu
    val shown = listed != null && !dismissed && (listed.isNotEmpty() || ' ' !in draft.text)
    BackHandler(enabled = shown) { dismissed = true }
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
               .background(MaterialTheme.colorScheme.surfaceContainer, shape).border(1.dp, Hermes.colors.stroke, shape)) {
        // A job's run the gateway still runs is read here, not written (/architect #4): the draft stays, Send waits.
        // Another app's words are a draft, never a prompt: the user reads them before anything goes out (DC-M7).
        if (shown && listed != null) SlashMenu(listed, Modifier.weight(1f, fill = false)) { draft = TextFieldValue(it.fill, TextRange(it.fill.length)) }
        if (shared && draft.text.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp).testTag("offer"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (cut) R.string.offer_cut else R.string.offer_shared), Modifier.weight(1f),
                     style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onNewChat != null) TextButton(onNewChat, Modifier.testTag("offer-new-chat")) { Text(stringResource(R.string.chat_new)) }
            }
        }
        if (chat.runOwned) Text(stringResource(R.string.chat_run_owned),
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("run-owned"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        said?.let {
            Text(stringResource(it), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("dictation-said"),
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextField(draft, { draft = it }, Modifier.fillMaxWidth().testTag("draft"), maxLines = 6,
                  textStyle = MaterialTheme.typography.bodyLarge,
                  placeholder = {
                      Text(stringResource(chat.busySend?.let(::label)
                                              ?: if (chat.busy && chat.queueable && onQueue != null) R.string.chat_queue else R.string.chat_hint))
                  },
                  colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                                    disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                                    unfocusedIndicatorColor = Color.Transparent))
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // The model takes the room the button leaves; a long name is cut at its end.
            Box(Modifier.weight(1f)) {
                if (onSetup != null) {
                    // Not ready: the tap shows why first (a phone has no tooltip; Desktop's gateway menu shows the reason).
                    val asks = readiness is Readiness.NeedsSetup || readiness is Readiness.Unavailable || readiness == Readiness.Unknown
                    var words by remember { mutableStateOf(false) }
                    val said = readiness?.let { stringResource(stateWord(it)) }
                    Row(Modifier.clip(CircleShape).clickable { if (asks) words = true else (onModels ?: onSetup)() }.testTag("open-setup")
                            .then(said?.let { Modifier.semantics { stateDescription = it } } ?: Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(HermesIcons.Model, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(chat.model ?: stringResource(R.string.chat_model), Modifier.weight(1f, fill = false).padding(start = 6.dp),
                             style = MaterialTheme.typography.labelLarge,
                             color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        readiness?.let { ReadinessMark(it) }
                    }
                    if (readiness != null) DropdownMenu(expanded = words && asks, onDismissRequest = { words = false }) {
                        Text(wordsOf(readiness), Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp).testTag("readiness-words"),
                             style = MaterialTheme.typography.bodySmall)
                        DropdownMenuItem(text = { Text(stringResource(R.string.setup_title)) }, onClick = { words = false; onSetup() },
                                         Modifier.testTag("readiness-setup"))
                        onCheckReadiness?.let { check ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_ready_check)) }, onClick = { words = false; check() },
                                             Modifier.testTag("readiness-check"))
                        }
                    }
                }
            }
            chat.meter?.let { ContextChip(it, onContext) }
            // Desktop's ghost mic, offered in every state the draft can be typed in (D6): it only fills the draft.
            dictation?.let { intent ->
                IconButton({
                    said = null
                    try { listen.launch(intent) } catch (_: ActivityNotFoundException) { said = R.string.chat_dictation_unavailable }
                }, Modifier.size(36.dp).testTag("dictate")) {
                    Icon(HermesIcons.Microphone, stringResource(R.string.chat_dictate), Modifier.size(18.dp),
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
            }
            if (chat.busy) {
                if (onQueue != null && chat.queueable) {
                    FilledTonalIconButton({ onQueue(draft.text.trim()); draft = TextFieldValue(""); shared = false }, Modifier.size(36.dp).testTag("queue"), enabled = draft.text.isNotBlank()) {
                        Icon(HermesIcons.Queue, stringResource(R.string.chat_queue), Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                chat.busySend?.let { mode ->
                    FilledIconButton({ onSend(draft.text.trim()); draft = TextFieldValue(""); shared = false }, Modifier.size(36.dp).testTag("send"), enabled = draft.text.isNotBlank()) {
                        Icon(HermesIcons.Send, stringResource(label(mode)), Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                FilledIconButton(onStop, Modifier.size(36.dp).testTag("stop"),
                                 colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                                                                                    contentColor = MaterialTheme.colorScheme.surface)) {
                    Icon(HermesIcons.Stop, stringResource(R.string.chat_stop), Modifier.size(14.dp))
                }
            } else {
                FilledIconButton({ onSend(draft.text.trim()); draft = TextFieldValue(""); shared = false }, Modifier.size(36.dp).testTag("send"), enabled = draft.text.isNotBlank() && !chat.compressing && !chat.runOwned) {
                    Icon(HermesIcons.Send, stringResource(R.string.chat_send), Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * The model pill's mark: a dot, and for a pill that is not ready its word. Unknown looks neutral, never like a "no"
 * (DC-M11); the `when` is exhaustive, so a new state cannot fall to "ready" (EJ-34).
 */
@Composable
private fun ReadinessMark(readiness: Readiness) {
    val muted = Hermes.colors.textTertiary
    val (tint, labelled) = when (readiness) {
        Readiness.Checking -> muted to false
        is Readiness.Ready -> MaterialTheme.colorScheme.primary to false
        is Readiness.NeedsSetup -> MaterialTheme.colorScheme.error to true
        is Readiness.Unavailable -> MaterialTheme.colorScheme.error to true
        Readiness.Unknown -> muted to true
    }
    Box(Modifier.padding(start = 6.dp).size(7.dp).clip(CircleShape).background(tint).testTag("readiness-dot"))
    if (labelled) {
        Text(stringResource(stateWord(readiness)), Modifier.padding(start = 4.dp).testTag("readiness-label"),
             style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1)
    }
}

/** The pill's state in one word: its label when not ready, and what TalkBack reads with the model. */
@StringRes
private fun stateWord(readiness: Readiness): Int = when (readiness) {
    Readiness.Checking -> R.string.chat_ready_checking
    is Readiness.Ready -> R.string.chat_ready_ready
    is Readiness.NeedsSetup -> R.string.chat_ready_needs_setup
    is Readiness.Unavailable -> R.string.chat_ready_unavailable
    Readiness.Unknown -> R.string.chat_ready_unknown
}

/** What a tap on a pill that is not ready says: the core's own words, or that the check did not finish. */
@Composable
private fun wordsOf(readiness: Readiness): String = when (readiness) {
    is Readiness.NeedsSetup -> readiness.words
    is Readiness.Unavailable -> readiness.words
    Readiness.Checking, is Readiness.Ready, Readiness.Unknown -> stringResource(R.string.chat_ready_unknown_words)
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
