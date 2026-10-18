package com.nousresearch.hermes.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
                            ChatScreen(chat, about = state.about, onSend = { text ->
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
}
