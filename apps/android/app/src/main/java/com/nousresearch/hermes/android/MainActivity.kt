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
import com.nousresearch.hermes.android.chat.ChatScreen
import com.nousresearch.hermes.android.chat.HistoryScreen
import com.nousresearch.hermes.android.chat.PromptAnswers
import com.nousresearch.hermes.android.gateway.GatewayScreen
import com.nousresearch.hermes.android.gateway.GatewayUi
import com.nousresearch.hermes.android.gateway.TelegramSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.nousresearch.hermes.android.setup.SettingsScreen
import com.nousresearch.hermes.android.setup.SetupScreen
import com.nousresearch.hermes.android.setup.SshScreen
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
                        CoreState.Starting -> Text(stringResource(R.string.core_starting), Modifier.safeDrawingPadding().padding(16.dp))
                        is CoreState.Failed -> Text(state.reason, Modifier.safeDrawingPadding().padding(16.dp),
                                                    color = MaterialTheme.colorScheme.error)
                        is CoreState.Ready -> {
                            val chat by state.chat.state.collectAsState()
                            val setup by state.setup.state.collectAsState()
                            val ssh by state.ssh.state.collectAsState()
                            val history by state.history.state.collectAsState()
                            val settings by state.settings.state.collectAsState()
                            settings?.let {
                                SettingsScreen(it, onApprovals = { mode -> app.settings { choose(mode) } }, onClose = state.settings::close)
                                return@Surface
                            }
                            history?.let {
                                HistoryScreen(it, onPick = { id -> app.history { pick(id) } }, onClose = state.history::close)
                                return@Surface
                            }
                            val gateway by app.gateway.state.collectAsState()
                            gateway?.let {
                                GatewayPanel(it, app, state.telegram)
                                return@Surface
                            }
                            ssh?.let {
                                SshScreen(it, onLook = { target -> app.ssh { look(target) } }, onTrust = { app.ssh { trust() } },
                                          onCheck = { app.ssh { checkLogin() } }, onCopy = ::copyPublicKey, onClose = state.ssh::close)
                                return@Surface
                            }
                            setup?.let {
                                SetupScreen(it, onPick = { slug -> app.setup { pick(slug) } }, onKey = { key -> app.setup { submitKey(key) } },
                                            onModel = { model, confirmed -> app.setup { choose(model, confirmed) } },
                                            onBack = state.setup::back, onClose = state.setup::close,
                                            onAddEndpoint = state.setup::addEndpoint, onOpenUrl = ::openInBrowser,
                                            onEndpoint = { name, url, model, key -> app.setup { saveEndpoint(name, url, model, key) } },
                                            onForget = { app.setup { forgetKey() } })
                                return@Surface
                            }
                            val answers = PromptAnswers(approve = { p, choice -> app.chat { approve(p, choice) } },
                                                        clarify = { p, given -> app.chat { clarify(p, given) } },
                                                        secret = { p, value -> app.chat { secret(p, value) } })
                            ChatScreen(chat, about = state.about, onSetup = { app.setup { open() } }, answers = answers,
                                       onTerminal = state.ssh::open, onGateway = app.gateway::open, onStop = { app.chat { cancel() } }, onHistory = { app.history { open() } },
                                       onSettings = { app.settings { open() } }, onSend = { text ->
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

    /** The public key goes on the clipboard so the user can paste it into the host's authorized_keys. */
    private fun copyPublicKey(publicKey: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SSH public key", publicKey))
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()  // no browser: the URL itself, to open elsewhere
        }
    }
}
