package com.nousresearch.hermes.android

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.SessionHistory
import com.nousresearch.hermes.android.gateway.GatewayControl
import com.nousresearch.hermes.android.gateway.PrefsGatewaySettings
import com.nousresearch.hermes.android.gateway.ServiceGatewayLauncher
import com.nousresearch.hermes.android.gateway.TelegramSetup
import com.nousresearch.hermes.android.gateway.batteryExempt
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.android.setup.ProviderSetup
import com.nousresearch.hermes.android.setup.SshSetup
import com.nousresearch.hermes.android.turn.ForegroundTurnHost
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.EmbeddedBridge
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.KeystoreSecretStore
import com.nousresearch.hermes.bridge.RuntimeInstaller
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the process's core is: starting, ready with its chat, or failed to start. */
sealed interface CoreState {
    data object Starting : CoreState

    data class Ready(val gateway: CoreGateway, val chat: ChatSession, val setup: ProviderSetup, val ssh: SshSetup,
                     val telegram: TelegramSetup, val history: SessionHistory, val about: String) : CoreState

    data class Failed(val reason: String) : CoreState
}

/**
 * Owns the embedded core for the whole process. Activities come and go (rotation, the task being
 * reopened), but the interpreter starts once per process, so the core and its chat live here.
 */
class HermesApplication : Application() {
    private companion object {
        const val TAG = "Hermes"
    }

    // The flows turn their own failures into words on the screen; this only keeps a missed one from ending the app.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
                                       CoroutineExceptionHandler { _, failed -> Log.w(TAG, "uncaught ${failed.javaClass.name}") })
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

    /** Runs one history action on the app's scope, so it outlives the activity that asked for it. */
    fun history(action: suspend SessionHistory.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.history?.action() }
    }

    /** Runs one Telegram setup action on the app's scope, so it outlives the activity that asked for it. */
    fun telegram(action: suspend TelegramSetup.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.telegram?.action() }
    }

    private suspend fun start(): CoreState.Ready {
        val began = SystemClock.elapsedRealtime()
        val runtime = withContext(Dispatchers.IO) { RuntimeInstaller(File(noBackupFilesDir, "runtime")).install(AssetPayload(assets)) }
        val gateway = EmbeddedPython.connect(HermesPaths(runtime, filesDir, File(filesDir, ".hermes"), cacheDir), secrets)
        val bridge = EmbeddedBridge
        val keepSecret: suspend (String, ByteArray) -> Unit = { name, value ->
            secrets.put(name, value)
            bridge.secretsChanged()
        }
        val chat = ChatSession(gateway, ForegroundTurnHost(this), scope, keepSecret).apply { open() }
        val setup = ProviderSetup(gateway, secrets, bridge, session = { chat.sessionId }, reopenChat = chat::open)
        val ssh = SshSetup(secrets, bridge)
        val telegram = TelegramSetup(secrets, bridge)
        val history = SessionHistory(list = chat::pastSessions, resume = chat::resume)
        // Undispatched: setup is showing before the core is Ready, so the chat never flashes first.
        if (setup.needed()) scope.launch(start = CoroutineStart.UNDISPATCHED) { setup.open() }
        val facts = bridge.ask(BridgeCalls.RUNTIME_FACTS)
        val fts = if (facts.fts5) "with FTS5" else "without FTS5"
        return CoreState.Ready(gateway, chat, setup, ssh, telegram, history, "Python ${facts.python} · SQLite ${facts.sqlite} $fts · " +
            "ready in ${SystemClock.elapsedRealtime() - began} ms")
    }
}
