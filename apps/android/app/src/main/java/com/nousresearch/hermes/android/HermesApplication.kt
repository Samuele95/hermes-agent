package com.nousresearch.hermes.android

import android.app.Application
import android.os.SystemClock
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.gateway.GatewayControl
import com.nousresearch.hermes.android.gateway.PrefsGatewaySettings
import com.nousresearch.hermes.android.gateway.ServiceGatewayLauncher
import com.nousresearch.hermes.android.gateway.batteryExempt
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.android.setup.ProviderSetup
import com.nousresearch.hermes.android.setup.SshSetup
import com.nousresearch.hermes.android.turn.ForegroundTurnHost
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.KeystoreSecretStore
import com.nousresearch.hermes.bridge.RuntimeInstaller
import com.nousresearch.hermes.bridge.SecretStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    data class Ready(val gateway: CoreGateway, val chat: ChatSession, val setup: ProviderSetup, val ssh: SshSetup, val about: String) : CoreState

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

    /** The optional always-on gateway's controls; they need no core (the gateway runs in its own process). */
    val gateway: GatewayControl by lazy {
        GatewayControl(PrefsGatewaySettings(getSharedPreferences("gateway", MODE_PRIVATE)), ServiceGatewayLauncher(this),
                       observe = { observeGateway(this) }, batteryExempt = { batteryExempt(this) })
    }

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
        chat { send(text) }
    }

    /** Runs one chat action on the app's scope, so it outlives the activity that asked for it. */
    fun chat(action: suspend ChatSession.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.chat?.action() }
    }

    /** Runs one setup action on the app's scope, so it outlives the activity that asked for it. */
    fun setup(action: suspend ProviderSetup.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.setup?.action() }
    }

    /** Runs one SSH setup action on the app's scope, so it outlives the activity that asked for it. */
    fun ssh(action: suspend SshSetup.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.ssh?.action() }
    }

    private suspend fun start(): CoreState.Ready {
        val began = SystemClock.elapsedRealtime()
        val runtime = withContext(Dispatchers.IO) { RuntimeInstaller(File(noBackupFilesDir, "runtime")).install(AssetPayload(assets)) }
        val gateway = EmbeddedPython.connect(HermesPaths(runtime, filesDir, File(filesDir, ".hermes"), cacheDir), secrets)
        val keepSecret: suspend (String, ByteArray) -> Unit = { name, value ->
            secrets.put(name, value)
            withContext(Dispatchers.IO) { EmbeddedPython.call("secrets_changed", "") }
        }
        val chat = ChatSession(gateway, ForegroundTurnHost(this), scope, keepSecret).apply { open() }
        val setup = ProviderSetup(gateway, secrets, secretsChanged = { withContext(Dispatchers.IO) { EmbeddedPython.call("secrets_changed", "") } },
                                  session = { chat.sessionId }, reopenChat = chat::open,
                                  saveEndpoint = { request -> withContext(Dispatchers.IO) { EmbeddedPython.call("save_custom_endpoint", request) } })
        val ssh = SshSetup(secrets, secretsChanged = { withContext(Dispatchers.IO) { EmbeddedPython.call("secrets_changed", "") } },
                           bridge = { function, request -> withContext(Dispatchers.IO) { EmbeddedPython.call(function, request) } })
        // Undispatched: setup is showing before the core is Ready, so the chat never flashes first.
        if (setup.needed()) scope.launch(start = CoroutineStart.UNDISPATCHED) { setup.open() }
        val facts = withContext(Dispatchers.IO) { JSONObject(EmbeddedPython.call("runtime_facts", "")) }
        val fts = if (facts.getBoolean("fts5")) "with FTS5" else "without FTS5"
        return CoreState.Ready(gateway, chat, setup, ssh, "Python ${facts.getString("python")} · SQLite ${facts.getString("sqlite")} $fts · " +
            "ready in ${SystemClock.elapsedRealtime() - began} ms")
    }
}
