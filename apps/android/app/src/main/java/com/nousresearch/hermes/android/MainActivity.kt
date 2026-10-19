package com.nousresearch.hermes.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.android.chat.ChatScreen
import com.nousresearch.hermes.android.chat.PromptAnswers
import com.nousresearch.hermes.android.setup.SetupScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HermesApplication
        setContent {
            MaterialTheme {
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
                            setup?.let {
                                SetupScreen(it, onPick = { slug -> app.setup { pick(slug) } }, onKey = { key -> app.setup { submitKey(key) } },
                                            onModel = { model, confirmed -> app.setup { choose(model, confirmed) } },
                                            onBack = state.setup::back, onClose = state.setup::close,
                                            onAddEndpoint = state.setup::addEndpoint, onOpenUrl = ::openInBrowser,
                                            onEndpoint = { name, url, model, key -> app.setup { saveEndpoint(name, url, model, key) } })
                                return@Surface
                            }
                            val answers = PromptAnswers(approve = { p, choice -> app.chat { approve(p, choice) } },
                                                        clarify = { p, given -> app.chat { clarify(p, given) } },
                                                        secret = { p, value -> app.chat { secret(p, value) } })
                            ChatScreen(chat, about = state.about, onSetup = { app.setup { open() } }, answers = answers, onSend = { text ->
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

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()  // no browser: the URL itself, to open elsewhere
        }
    }
}
