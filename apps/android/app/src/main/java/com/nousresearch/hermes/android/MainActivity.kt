package com.nousresearch.hermes.android

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.RuntimeInstaller
import java.io.File
import kotlin.concurrent.thread
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val status = mutableStateOf("Starting the core…")

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
                val runtime = RuntimeInstaller(File(noBackupFilesDir, "runtime")).install(AssetPayload(assets))
                // The chat screen consumes frames; until it exists they are dropped.
                EmbeddedPython.start(HermesPaths(runtime, filesDir, File(filesDir, ".hermes"), cacheDir)) {}
                val facts = JSONObject(EmbeddedPython.call("runtime_facts", ""))
                "Core ready in ${SystemClock.elapsedRealtime() - started} ms (runtime ${runtime.name})\n" +
                    "Python ${facts.getString("python")} · SQLite ${facts.getString("sqlite")}" +
                    if (facts.getBoolean("fts5")) " with FTS5" else " without FTS5"
            }.getOrElse { "Core start failed: $it" }
        }
    }
}
