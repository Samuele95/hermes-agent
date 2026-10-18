package com.nousresearch.hermes.android

import android.app.Application
import android.os.SystemClock
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.turn.ForegroundTurnHost
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.KeystoreSecretStore
import com.nousresearch.hermes.bridge.RuntimeInstaller
import com.nousresearch.hermes.bridge.SecretStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where the process's core is: starting, ready with its chat, or failed to start. */
sealed interface CoreState {
    data object Starting : CoreState

    data class Ready(val gateway: CoreGateway, val chat: ChatSession, val about: String) : CoreState

    data class Failed(val reason: String) : CoreState
}

/**
 * Owns the embedded core for the whole process. Activities come and go (rotation, the task being
 * reopened), but the interpreter starts once per process, so the core and its chat live here.
 */
class HermesApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow<CoreState>(CoreState.Starting)
    private var started = false

    /** Provider keys, wrapped by the app's AndroidKeyStore key; the core reads them as environment variables. */
    val secrets: SecretStore by lazy { KeystoreSecretStore(File(noBackupFilesDir, "secrets")) { KeystoreSecretStore.androidKey() } }

    /** Starts the core on first use. */
    val core: StateFlow<CoreState>
        get() {
            synchronized(this) {
                if (!started) {
                    started = true
                    scope.launch { state.value = runCatching { start() }.getOrElse { CoreState.Failed(it.toString()) } }
                }
            }
            return state.asStateFlow()
        }

    fun send(text: String) {
        scope.launch { (state.value as? CoreState.Ready)?.chat?.send(text) }
    }

    private suspend fun start(): CoreState.Ready {
        val began = SystemClock.elapsedRealtime()
        val runtime = withContext(Dispatchers.IO) { RuntimeInstaller(File(noBackupFilesDir, "runtime")).install(AssetPayload(assets)) }
        val gateway = EmbeddedPython.connect(HermesPaths(runtime, filesDir, File(filesDir, ".hermes"), cacheDir), secrets)
        val chat = ChatSession(gateway, ForegroundTurnHost(this), scope).apply { open() }
        val facts = withContext(Dispatchers.IO) { JSONObject(EmbeddedPython.call("runtime_facts", "")) }
        val fts = if (facts.getBoolean("fts5")) "with FTS5" else "without FTS5"
        return CoreState.Ready(gateway, chat, "Python ${facts.getString("python")} · SQLite ${facts.getString("sqlite")} $fts · " +
            "ready in ${SystemClock.elapsedRealtime() - began} ms")
    }
}
