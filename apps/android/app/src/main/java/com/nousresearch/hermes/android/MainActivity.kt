package com.nousresearch.hermes.android

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.nousresearch.hermes.bridge.RuntimeInstaller
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val status = mutableStateOf("Installing the runtime…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("Hermes\n${status.value}") }
            }
        }
        thread(name = "runtime-install") {
            val started = SystemClock.elapsedRealtime()
            status.value = runCatching {
                RuntimeInstaller(File(noBackupFilesDir, "runtime")).install(AssetPayload(assets))
            }.fold(
                { "Runtime ${it.name} ready in ${SystemClock.elapsedRealtime() - started} ms" },
                { "Runtime install failed: $it" },
            )
        }
    }
}
