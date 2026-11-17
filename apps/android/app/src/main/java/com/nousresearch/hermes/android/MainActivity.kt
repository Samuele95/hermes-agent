package com.nousresearch.hermes.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.nousresearch.hermes.android.ui.Wordmark
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nousresearch.hermes.android.chat.ChatPlaces
import com.nousresearch.hermes.android.chat.ChatScreen
import com.nousresearch.hermes.android.chat.ContextActions
import com.nousresearch.hermes.android.chat.ExportActions
import com.nousresearch.hermes.android.chat.ModelActions
import com.nousresearch.hermes.android.chat.StatusActions
import com.nousresearch.hermes.android.chat.PromptAnswers
import com.nousresearch.hermes.android.chat.QueueActions
import com.nousresearch.hermes.android.chat.RecoveryRoutes
import com.nousresearch.hermes.android.chat.SessionActions
import com.nousresearch.hermes.android.chat.dictationIntent
import com.nousresearch.hermes.android.chat.recognizerAnswers
import com.nousresearch.hermes.android.entry.IncomingText
import com.nousresearch.hermes.android.entry.NEW_CHAT
import com.nousresearch.hermes.android.gateway.GatewayScreen
import com.nousresearch.hermes.android.gateway.GatewayUi
import com.nousresearch.hermes.android.gateway.TelegramActions
import com.nousresearch.hermes.android.gateway.TelegramSetup
import com.nousresearch.hermes.android.jobs.JobActions
import com.nousresearch.hermes.android.jobs.JobFormActions
import com.nousresearch.hermes.android.jobs.JobRowActions
import com.nousresearch.hermes.android.jobs.JobRunsActions
import com.nousresearch.hermes.android.jobs.JobsControl
import com.nousresearch.hermes.android.jobs.JobsScreen
import com.nousresearch.hermes.android.jobs.JobsUi
import com.nousresearch.hermes.bridge.CoreEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.nousresearch.hermes.android.settings.HubActions
import com.nousresearch.hermes.android.settings.HubItemActions
import com.nousresearch.hermes.android.settings.HubScreen
import com.nousresearch.hermes.android.settings.SettingsActions
import com.nousresearch.hermes.android.settings.SettingsControl
import com.nousresearch.hermes.android.settings.SettingsScreen
import com.nousresearch.hermes.android.settings.SettingsUi
import com.nousresearch.hermes.android.settings.SkillWriteActions
import com.nousresearch.hermes.android.settings.SkillsActions
import com.nousresearch.hermes.android.settings.SkillsScreen
import com.nousresearch.hermes.android.setup.SetupScreen
import com.nousresearch.hermes.android.setup.SshScreen
import com.nousresearch.hermes.android.ui.Hermes
import com.nousresearch.hermes.android.ui.HermesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HermesApplication
        setContent {
            HermesTheme {
                Surface(Modifier.fillMaxSize()) {
                    val core by app.core.collectAsState()
                    // Asked at the first message, not at launch: the notification only matters while a turn runs.
                    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                    when (val state = core) {
                        CoreState.Starting -> Splash(stringResource(R.string.core_starting))
                        is CoreState.Failed -> Splash(state.reason, failed = true)
                        is CoreState.Ready -> {
                            val chat by state.chat.state.collectAsState()
                            val setup by state.setup.state.collectAsState()
                            val readiness by state.setup.readiness.collectAsState()
                            val ssh by state.pages.ssh.state.collectAsState()
                            val history by state.pages.history.state.collectAsState()
                            val context by state.pages.context.state.collectAsState()
                            val queue by state.chat.queueState.collectAsState()
                            val skills by state.pages.skills.state.collectAsState()
                            val hub by state.pages.hub.state.collectAsState()
                            val exported by state.pages.export.state.collectAsState()
                            val status by state.pages.status.state.collectAsState()
                            val models by state.pages.models.state.collectAsState()
                            // The core's Message Reactions switch, known before Settings opens (D4): React shows only while it is on.
                            val reactionsOn by state.pages.settings.reactionsOn.collectAsState()
                            // The phone's own speech recognizer, when one answers (chat-30, D48): the mic shows only then, and the app holds no
                            // microphone permission — the recognizer's activity does.
                            val dictation = remember { dictationIntent().takeIf { recognizerAnswers(packageManager, it) } }
                            // Whichever page shows: a finished export goes to the share sheet each time the activity is started, until one
                            // opened it (D8, architect #2); one no app can take waits no longer (N15); no key changes at a stop, so the
                            // lifecycle, not a key, brings the retry.
                            val waiting = exported.file
                            LaunchedEffect(waiting) {
                                waiting ?: return@LaunchedEffect
                                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                                    state.pages.export.handOff(waiting) { shareExport(it, failed = state.pages.export::unshareable) }
                                }
                            }
                            hub?.let {  // above Skills, which stays open beneath: Back returns to it
                                HubScreen(it, HubActions(query = state.pages.hub::query, search = { q -> app.hub { search(q) } },
                                                         browse = { p -> app.hub { browse(p) } }, close = state.pages.hub::close,
                                                         item = state.pages.hub.let { h ->
                                                             HubItemActions(preview = { k -> app.hub { preview(k) } }, closePreview = h::closePreview,
                                                                            scan = { id -> app.hub { scan(id) } },
                                                                            install = { id -> app.hub { install(id) } })
                                                         }))
                                return@Surface
                            }
                            skills?.let {  // above Settings, which stays open beneath: Back returns to it (D4)
                                SkillsScreen(it, onEnable = { names, on -> app.skills { enable(names, on) } }, onClose = state.pages.skills::close,
                                             actions = SkillsActions(query = state.pages.skills::query, refresh = { app.skills { refresh() } },
                                                                     read = { name -> app.skills { read(name) } },
                                                                     closeSkill = state.pages.skills::closeSkill,
                                                                     write = state.pages.skills.let { s ->
                                                                         SkillWriteActions(edit = s::edit, draft = s::draft, cancelEdit = s::cancelEdit,
                                                                                           save = { app.skills { save() } }, askArchive = s::askArchive,
                                                                                           confirmArchive = { app.skills { confirmArchive() } },
                                                                                           showArchived = { app.skills { showArchived() } },
                                                                                           hideArchived = s::hideArchived,
                                                                                           restore = { name -> app.skills { restore(name) } })
                                                                     },
                                                                     hub = { app.hub { open() } }))
                                return@Surface
                            }
                            val settings by state.pages.settings.state.collectAsState()
                            settings?.let {
                                SettingsPanel(it, app, state.pages.settings)
                                return@Surface
                            }
                            val gateway by app.gateway.state.collectAsState()
                            gateway?.let {
                                GatewayPanel(it, app, state.pages.telegram, state.gateway.events)
                                return@Surface
                            }
                            val jobs by state.pages.jobs.state.collectAsState()
                            jobs?.let {
                                JobsPanel(it, app, state.pages.jobs)
                                return@Surface
                            }
                            ssh?.let {
                                SshScreen(it, onLook = { target -> app.ssh { look(target) } }, onTrust = { app.ssh { trust() } },
                                          onCheck = { app.ssh { checkLogin() } }, onCopy = { copy("SSH public key", it) }, onClose = state.pages.ssh::close,
                                          onRunOnPhone = { app.ssh { runOnPhone() } })
                                return@Surface
                            }
                            setup?.let {
                                SetupScreen(it, onPick = { slug -> app.setup { pick(slug) } }, onKey = { key -> app.setup { submitKey(key) } },
                                            onModel = { model, confirmed -> app.setup { choose(model, confirmed) } },
                                            onBack = state.setup::back, onClose = state.setup::close,
                                            onAddEndpoint = state.setup::addEndpoint, onOpenUrl = ::openInBrowser,
                                            onEndpoint = { name, url, model, key -> app.setup { saveEndpoint(name, url, model, key) } },
                                            onForget = { app.setup { forgetKey() } },
                                            onFindServer = { address, key, model -> app.setup { findServer(address, key, model) } },
                                            onSearchNetwork = { app.setup { searchNetwork() } },
                                            onEditEndpoint = state.setup::editEndpoint,
                                            onDeleteEndpoint = { id -> app.setup { deleteEndpoint(id) } },
                                            onCustomModel = { id -> app.setup { chooseCustom(id) } })
                                return@Surface
                            }
                            val answers = PromptAnswers(approve = { p, choice -> app.chat { approve(p, choice) } },
                                                        clarify = { p, given -> app.chat { clarify(p, given) } },
                                                        secret = { p, value -> app.chat { secret(p, value) } })
                            val places = ChatPlaces(setup = { app.setup { open() } }, terminal = { app.ssh { open() } }, gateway = app::openGateway,
                                                    settings = { app.settings { open() } }, newChat = { app.chat { open() } },
                                                    jobs = { app.jobs { open() } })
                            val contextActions = ContextActions(open = { app.context { open() } }, compress = { focus -> app.context { compress(focus) } },
                                                                close = state.pages.context::close)
                            val queueActions = QueueActions(add = { text -> app.chat { queue(text) } }, edit = { id -> app.chat { beginEdit(id) } },
                                                            save = { id, text -> app.chat { editQueued(id, text) } },
                                                            steer = { id -> app.chat { steerNow(id) } }, next = { id -> app.chat { sendNext(id) } },
                                                            delete = { id -> app.chat { deleteQueued(id) } }, resume = { app.chat { resumeQueue() } })
                            ChatScreen(chat, about = state.about, answers = answers, places = places, onStop = { app.chat { cancel() } },
                                       onRetry = { app.chat { retry() } }, onCopy = { copy("Hermes", it) },
                                       onEdit = { rewind -> app.chat { resend(rewind) } },
                                       onReact = { id: Long, emoji: String? -> app.chat { react(id, emoji) } }.takeIf { reactionsOn == true },
                                       // A failed answer's fixes run the controls the app already has (chat-16, D7); Compress
                                       // opens the context sheet first, so its outcome or refusal shows there.
                                       recovery = RecoveryRoutes(retry = { app.chat { retry() } }, send = app::send,
                                                                 pickModel = { app.models { open() } }, newChat = { app.chat { open() } },
                                                                 setup = { app.setup { open() } }, reauth = { slug -> app.setup { reauth(slug) } },
                                                                 compress = { app.context { open(); compress(null) } },
                                                                 openUrl = ::openInBrowser, auth = state.setup::authOf, app = appVersion),
                                       // The drawer's sessions load as it opens; a tap continues one.
                                       sessions = history, onSessions = { open -> if (open) app.history { open() } else state.pages.history.close() },
                                       onPickSession = { id -> app.history { pick(id) } },
                                       sessionActions = SessionActions(edit = state.pages.history::edit, saveTitle = { title -> app.history { saveTitle(title) } },
                                                                       askDelete = state.pages.history::askDelete, confirmDelete = { app.history { confirmDelete() } },
                                                                       export = { id -> app.export { export(id) } }),
                                       context = context, contextActions = contextActions, queue = queue, queueActions = queueActions,
                                       onOfferTaken = state.chat::offerTaken, onOpenUrl = ::openInBrowser, onDismissWall = state.chat::dismissWall,
                                       onSlash = state.chat::slashItems, onPrefillTaken = state.chat::prefillTaken, dictation = dictation,
                                       readiness = readiness, onCheckReadiness = { app.setup { checkReadiness() } },
                                       exportUi = exported,
                                       exportActions = ExportActions(export = { app.export { export(null) } }, dismiss = state.pages.export::dismiss),
                                       status = status,
                                       statusActions = StatusActions(open = { app.status { open() } }, close = state.pages.status::close),
                                       models = models,
                                       modelActions = ModelActions(open = { app.models { open() } }, close = state.pages.models::close,
                                                                   query = state.pages.models::query, addCustom = state.pages.models::addCustom,
                                                                   pick = { row -> app.models { pick(row) } }, confirm = { app.models { confirm() } },
                                                                   keep = state.pages.models::keep,
                                                                   addProvider = { state.pages.models.close(); app.setup { open() } }),
                                       onSend = { text ->
                                if (Build.VERSION.SDK_INT >= 33 &&
                                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                app.send(text)
                            })
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) take(intent)  // a recreated activity offers nothing again (D10)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    /**
     * An intent in: shared text becomes the chat's offer (F9); the shortcut's and the tile's [NEW_CHAT] opens a new chat
     * (D2). One replayed from Recents does neither, or the same words would land twice (D10).
     */
    private fun take(intent: Intent?) {
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == NEW_CHAT) return (application as HermesApplication).newChat()
        IncomingText.of(intent.action, intent.type, intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                        intent.getStringExtra(Intent.EXTRA_SUBJECT))?.let((application as HermesApplication)::offer)
    }

    override fun onStart() {
        super.onStart()
        // Samsung can keep a killed gateway from coming back by itself (L21): the app brings it back when it shows (D38).
        (application as HermesApplication).reviveGateway()
    }

    /** While the core starts (the first run unpacks it), or when it could not: the brand, and what is happening. */
    @Composable
    private fun Splash(text: String, failed: Boolean = false) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center,
               horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(if (Hermes.colors.dark) R.drawable.brand_mark_dark else R.drawable.brand_mark), null,
                  Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
            Wordmark("HERMES AGENT", if (Hermes.colors.dark) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.primary,
                     Modifier.padding(top = 20.dp).width(220.dp))
            if (!failed) CircularProgressIndicator(Modifier.padding(top = 24.dp).size(20.dp), strokeWidth = 2.dp)
            Text(text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                 color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    /**
     * The gateway screen, kept current while it shows, and the battery dialog when the gateway was just turned on;
     * and the bot's live state, read again on each `platforms.changed` and each status change.
     */
    @Composable
    private fun GatewayPanel(ui: GatewayUi, app: HermesApplication, telegram: TelegramSetup, events: Flow<CoreEvent>) {
        val bot by telegram.state.collectAsState()
        LaunchedEffect(Unit) { app.telegram { open() } }
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { telegram.follow(events) } }
        LaunchedEffect(ui.status) { app.telegram { refreshLive() } }
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) { app.gateway.refresh() }
                delay(1_000)
            }
        }
        LaunchedEffect(ui.askBatteryExemption) {
            if (!ui.askBatteryExemption) return@LaunchedEffect
            app.gateway.batteryAsked()
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                // No such screen on this build of Android: the gateway runs anyway, Doze may pause it.
            }
        }
        GatewayScreen(ui, samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true), onOn = app::turnGatewayOn,
                      onOff = app.gateway::turnOff, onStartAtBoot = app.gateway::setStartAtBoot,
                      onClose = { telegram.close(); app.gateway.close() },
                      telegram = bot, onTelegramSave = { token, user -> app.telegram { save(token, user) } },
                      onRestart = app::restartGateway,
                      // Jobs sits under the Gateway page in the chain: this page closes for it to show (V8).
                      onJobs = { telegram.close(); app.gateway.close(); app.jobs { open() } },
                      onTelegram = TelegramActions(setEnabled = { on -> app.telegram { setEnabled(on) } }, askRemove = telegram::askRemove,
                                                   confirmRemove = { app.telegram { confirmRemove() } }))
    }

    /**
     * The jobs page, kept current while it shows: each `cron.changed`, the gateway coming up (and its ticker a few
     * seconds later), and every 30 s for the ticker's age (DC-J16).
     */
    @Composable
    private fun JobsPanel(ui: JobsUi, app: HermesApplication, jobs: JobsControl) {
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { jobs.follow() } }
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { jobs.followGateway(app.gatewayChanges) } }
        LaunchedEffect(Unit) {
            while (true) {
                withContext(Dispatchers.IO) { jobs.refresh() }  // first on entry: back from the Gateway page (V9)
                delay(30_000)
            }
        }
        JobsScreen(ui, onOpenGateway = app::openGateway, onClose = jobs::close,
                   actions = JobActions(newJob = { app.jobs { newJob() } }, edit = { id -> app.jobs { edit(id) } },
                                        form = JobFormActions(save = { f -> app.jobs { save(f) } }, cancel = jobs::cancelForm),
                                        row = JobRowActions(pause = { id -> app.jobs { pause(id) } }, resume = { id -> app.jobs { resume(id) } },
                                                            runNow = { id -> app.jobs { runNow(id) } }, askDelete = jobs::askDelete,
                                                            confirmDelete = { app.jobs { confirmDelete() } }),
                                        runs = JobRunsActions(show = { id -> app.jobs { showRuns(id) } }, open = { run -> app.jobs { openRun(run) } })))
    }

    /** The settings page, following the always-on gateway while it shows (a removal waits for its next start, F4). */
    @Composable
    private fun SettingsPanel(ui: SettingsUi, app: HermesApplication, settings: SettingsControl) {
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { settings.followGateway(app.gatewayChanges) } }
        SettingsScreen(ui, SettingsActions(approvals = { m -> app.settings { approvals(m) } }, personality = { n -> app.settings { personality(n) } },
                                           edit = { e -> app.settings { edit(e) } }, askRemove = settings::askRemove,
                                           confirmRemove = { app.settings { confirmRemove() } }, restartGateway = app::restartGateway,
                                           askRemoveProvider = settings::askRemoveProvider,
                                           confirmRemoveProvider = { app.settings { confirmRemoveProvider() } },
                                           openSkills = { app.skills { open() } }, copy = { text -> copy("Hermes version", text) },
                                           reactions = { on -> app.settings { reactions(on) } }),
                       onClose = settings::close)
    }

    /**
     * Text onto the clipboard: the SSH public key for the host's authorized_keys, what the user copied in the chat (F10),
     * or Settings' About block (F13).
     */
    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** The app's version, for a failed answer's details (D6), read as the application reads it. */
    private val appVersion by lazy {
        @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()  // no browser: the URL itself, to open elsewhere
        }
    }
}
