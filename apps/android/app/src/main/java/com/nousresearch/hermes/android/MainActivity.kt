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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.chat.ChatPlaces
import com.nousresearch.hermes.android.chat.ChatScreen
import com.nousresearch.hermes.android.chat.ContextActions
import com.nousresearch.hermes.android.chat.PromptAnswers
import com.nousresearch.hermes.android.chat.SessionActions
import com.nousresearch.hermes.android.gateway.GatewayScreen
import com.nousresearch.hermes.android.gateway.GatewayUi
import com.nousresearch.hermes.android.gateway.TelegramSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.nousresearch.hermes.android.setup.SettingsScreen
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
                            val ssh by state.ssh.state.collectAsState()
                            val history by state.history.state.collectAsState()
                            val context by state.context.state.collectAsState()
                            val settings by state.settings.state.collectAsState()
                            settings?.let {
                                SettingsScreen(it, onApprovals = { mode -> app.settings { choose(mode) } }, onClose = state.settings::close)
                                return@Surface
                            }
                            val gateway by app.gateway.state.collectAsState()
                            gateway?.let {
                                GatewayPanel(it, app, state.telegram)
                                return@Surface
                            }
                            ssh?.let {
                                SshScreen(it, onLook = { target -> app.ssh { look(target) } }, onTrust = { app.ssh { trust() } },
                                          onCheck = { app.ssh { checkLogin() } }, onCopy = { copy("SSH public key", it) }, onClose = state.ssh::close,
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
                                            onDeleteEndpoint = { id -> app.setup { deleteEndpoint(id) } })
                                return@Surface
                            }
                            val answers = PromptAnswers(approve = { p, choice -> app.chat { approve(p, choice) } },
                                                        clarify = { p, given -> app.chat { clarify(p, given) } },
                                                        secret = { p, value -> app.chat { secret(p, value) } })
                            val places = ChatPlaces(setup = { app.setup { open() } }, terminal = { app.ssh { open() } }, gateway = app::openGateway,
                                                    settings = { app.settings { open() } }, newChat = { app.chat { open() } })
                            val contextActions = ContextActions(open = { app.context { open() } }, compress = { focus -> app.context { compress(focus) } },
                                                                close = state.context::close)
                            ChatScreen(chat, about = state.about, answers = answers, places = places, onStop = { app.chat { cancel() } },
                                       onRetry = { app.chat { retry() } }, onCopy = { copy("Hermes", it) },
                                       // The drawer's sessions load as it opens; a tap continues one.
                                       sessions = history, onSessions = { open -> if (open) app.history { open() } else state.history.close() },
                                       onPickSession = { id -> app.history { pick(id) } },
                                       sessionActions = SessionActions(edit = state.history::edit, saveTitle = { title -> app.history { saveTitle(title) } },
                                                                       askDelete = state.history::askDelete, confirmDelete = { app.history { confirmDelete() } }),
                                       context = context, contextActions = contextActions,
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

    /** The gateway screen, kept current while it shows, and the battery dialog when the gateway was just turned on. */
    @Composable
    private fun GatewayPanel(ui: GatewayUi, app: HermesApplication, telegram: TelegramSetup) {
        val bot by telegram.state.collectAsState()
        LaunchedEffect(Unit) { app.telegram { open() } }
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
                      onRestart = app::restartGateway)
    }

    /** Text onto the clipboard: the SSH public key for the host's authorized_keys, or what the user copied in the chat (F10). */
    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()  // no browser: the URL itself, to open elsewhere
        }
    }
}
