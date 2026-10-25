package com.nousresearch.hermes.android

import android.app.Activity
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import com.nousresearch.hermes.android.turn.SystemNotifier
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
import com.nousresearch.hermes.android.setup.ApprovalSettings
import com.nousresearch.hermes.android.setup.PrefsKeyNames
import com.nousresearch.hermes.android.setup.ProviderSetup
import com.nousresearch.hermes.android.setup.SshSetup
import com.nousresearch.hermes.android.turn.ForegroundTurnHost
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.CoreRuntime
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.KeystoreSecretStore
import com.nousresearch.hermes.bridge.SecretStore
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the process's core is: starting, ready with its chat, or failed to start. */
sealed interface CoreState {
    data object Starting : CoreState

    data class Ready(val gateway: CoreGateway, val chat: ChatSession, val setup: ProviderSetup, val ssh: SshSetup,
                     val telegram: TelegramSetup, val history: SessionHistory, val settings: ApprovalSettings,
                     val about: String) : CoreState

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

    /** Activities of the app on screen (started, not yet stopped): while there is one, the chat shows what it has to say. */
    private val onScreen = AtomicInteger()

    /** Tells the user of a waiting question or a finished reply while the app is not on screen. */
    val notifier: SystemNotifier by lazy { SystemNotifier(this) { onScreen.get() > 0 } }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                onScreen.incrementAndGet()
                notifier.shown()
            }
            override fun onActivityStopped(activity: Activity) { onScreen.decrementAndGet() }
            override fun onActivityCreated(activity: Activity, saved: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Provider keys, wrapped by the app's AndroidKeyStore key; the core reads them as environment variables. */
    val secrets: SecretStore by lazy { KeystoreSecretStore(File(noBackupFilesDir, "secrets")) { KeystoreSecretStore.androidKey() } }

    /** The optional always-on gateway's controls; they need no core (the gateway runs in its own process). */
    val gateway: GatewayControl by lazy {
        GatewayControl(PrefsGatewaySettings(getSharedPreferences("gateway", MODE_PRIVATE)), ServiceGatewayLauncher(this),
                       observe = { observeGateway(this) }, batteryExempt = { batteryExempt(this) })
    }

    /**
     * Restarts the gateway and sees the restart through on the app's scope, so it finishes even if the screen that
     * asked closes before the old gateway has ended (up to a minute; its drain is bounded well below that).
     */
    fun restartGateway() {
        gateway.restart()
        seeThrough()
    }

    /** Turns the gateway on; one still stopping is started once it has ended, even if the screen closes meanwhile. */
    fun turnGatewayOn() {
        gateway.turnOn()
        seeThrough()
    }

    private fun seeThrough() {
        scope.launch(Dispatchers.IO) {
            val until = SystemClock.elapsedRealtime() + 60_000
            while (gateway.restarting && SystemClock.elapsedRealtime() < until) {
                delay(500)
                gateway.refresh()
            }
            if (gateway.restarting) gateway.abandonPendingStart()  // the screen shows what is, not a start long after
        }
    }

    /** Starts the core on first use. */
    val core: StateFlow<CoreState>
        get() {
            synchronized(this) {
                if (!started) {
                    started = true
                    scope.launch {
                        state.value = try {
                            start()
                        } catch (failed: Exception) {  // the trace goes to the log; the screen gets the failure rule's words
                            Log.w(TAG, "the core did not start", failed)
                            CoreState.Failed(forUser(failed))
                        }
                    }
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

    /** Runs one settings action on the app's scope, so it outlives the activity that asked for it. */
    fun settings(action: suspend ApprovalSettings.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.settings?.action() }
    }

    /** Runs one Telegram setup action on the app's scope, so it outlives the activity that asked for it. */
    fun telegram(action: suspend TelegramSetup.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.telegram?.action() }
    }

    /** The core this process embeds; the one adapter is [EmbeddedPython]. */
    private val runtime: CoreRuntime = EmbeddedPython

    private suspend fun start(): CoreState.Ready {
        val began = SystemClock.elapsedRealtime()
        val paths = withContext(Dispatchers.IO) { installRuntime(this@HermesApplication) }
        val ready = composeCore(runtime, paths, secrets, ForegroundTurnHost(this), notifier, scope,
                                PrefsKeyNames(getSharedPreferences("provider_keys", MODE_PRIVATE)))
        return ready.copy(about = "${ready.about} · ready in ${SystemClock.elapsedRealtime() - began} ms")
    }
}
