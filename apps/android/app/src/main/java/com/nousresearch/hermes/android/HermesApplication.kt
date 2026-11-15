package com.nousresearch.hermes.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import com.nousresearch.hermes.android.turn.SystemNotifier
import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.ContextPanel
import com.nousresearch.hermes.android.chat.SessionExport
import com.nousresearch.hermes.android.chat.SessionHistory
import com.nousresearch.hermes.android.chat.StatusPanel
import com.nousresearch.hermes.android.chat.queueFiles
import com.nousresearch.hermes.android.entry.IncomingText
import com.nousresearch.hermes.android.gateway.GatewayControl
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.PrefsGatewaySettings
import com.nousresearch.hermes.android.gateway.ServiceGatewayLauncher
import com.nousresearch.hermes.android.gateway.TelegramSetup
import com.nousresearch.hermes.android.gateway.batteryExempt
import com.nousresearch.hermes.android.gateway.observeGateway
import com.nousresearch.hermes.android.gateway.watchGateway
import com.nousresearch.hermes.android.jobs.JobsControl
import com.nousresearch.hermes.android.settings.AppPackage
import com.nousresearch.hermes.android.settings.SettingsControl
import com.nousresearch.hermes.android.settings.SkillsControl
import com.nousresearch.hermes.android.setup.PrefsCustomModels
import com.nousresearch.hermes.android.setup.PrefsKeyNames
import com.nousresearch.hermes.android.setup.ProviderSetup
import com.nousresearch.hermes.android.setup.SshSetup
import com.nousresearch.hermes.android.setup.wifiSubnet
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the process's core is: starting, ready with its chat, or failed to start. */
sealed interface CoreState {
    data object Starting : CoreState

    data class Ready(val gateway: CoreGateway, val chat: ChatSession, val setup: ProviderSetup, val pages: Pages,
                     val about: String) : CoreState

    data class Failed(val reason: String) : CoreState
}

/** The pages the chat opens, each a controller with nullable state (F12): one holder, not ten positional fields. */
data class Pages(val settings: SettingsControl, val skills: SkillsControl, val jobs: JobsControl, val telegram: TelegramSetup,
                 val ssh: SshSetup, val history: SessionHistory, val context: ContextPanel, val export: SessionExport,
                 val status: StatusPanel)

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
    /** Words shared before the core was Ready (guarded by `this`): delivered once it is (F9). */
    private var pendingOffer: IncomingText? = null
    /** A New chat asked for (the shortcut, the tile) before the core was Ready (guarded by `this`; D12). */
    private var pendingNewChat = false

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

    /** The always-on gateway as observed now, one value shared by its controls and the jobs page (D38, D39). */
    private val seen: () -> GatewaySeen = { observeGateway(this) }

    /** The always-on gateway, observed again each time its service rewrites its record, while collected (the jobs page). */
    val gatewayChanges: Flow<GatewaySeen> get() = watchGateway(this)

    /** The optional always-on gateway's controls; they need no core (the gateway runs in its own process). */
    val gateway: GatewayControl by lazy {
        GatewayControl(PrefsGatewaySettings(getSharedPreferences("gateway", MODE_PRIVATE)), ServiceGatewayLauncher(this),
                       observe = seen, batteryExempt = { batteryExempt(this) })
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

    /** The app came on screen: a gateway that died without a planned stop, and that nothing brought back, starts again (D38). */
    fun reviveGateway() {
        scope.launch(Dispatchers.IO) { if (gateway.revive()) seeThrough() }
    }

    /** Opens the gateway screen, which starts again a gateway that died unplanned; that start is seen through like a restart. */
    fun openGateway() {
        gateway.open()
        if (gateway.restarting) seeThrough()
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
                        val next = try {
                            start()
                        } catch (failed: Exception) {  // the trace goes to the log; the screen gets the failure rule's words
                            Log.w(TAG, "the core did not start", failed)
                            CoreState.Failed(forUser(failed))
                        }
                        // The state flips and what waits (a New chat, an offer) is taken under one lock: neither falls between them.
                        val (fresh, offer) = synchronized(this@HermesApplication) {
                            state.value = next
                            (pendingNewChat to pendingOffer).also { pendingNewChat = false; pendingOffer = null }
                        }
                        if (fresh) chat { open() }  // the offer lands in it or follows it into it (D7)
                        offer?.let { (next as? CoreState.Ready)?.chat?.offer(it) }
                    }
                }
            }
            return state.asStateFlow()
        }

    fun send(text: String) {
        chat { send(text) }
    }

    /** The shortcut's and the tile's New chat: a new conversation, once the core is Ready if it is not yet (D12). */
    fun newChat() {
        val ready = synchronized(this) { (state.value as? CoreState.Ready).also { if (it == null) pendingNewChat = true } }
        if (ready != null) chat { open() }
    }

    /**
     * Words another app shared become the chat's offer, a draft it never sends (bet 8). A core not yet ready keeps the
     * offer pending (F9); the newest wins.
     */
    fun offer(text: IncomingText) {
        val chat = synchronized(this) { (state.value as? CoreState.Ready)?.chat.also { if (it == null) pendingOffer = text } }
        chat?.offer(text)
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
        scope.launch { (state.value as? CoreState.Ready)?.pages?.ssh?.action() }
    }

    /** Runs one history action on the app's scope, so it outlives the activity that asked for it. */
    fun history(action: suspend SessionHistory.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.pages?.history?.action() }
    }

    /** Runs one context-sheet action on the app's scope, so a compression outlives the activity that asked for it. */
    fun context(action: suspend ContextPanel.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.pages?.context?.action() }
    }

    /** Runs one export action on the app's scope, so it outlives the activity that asked for it. */
    fun export(action: suspend SessionExport.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.pages?.export?.action() }
    }

    /** Runs one Status-sheet action on the app's scope, so it outlives the activity that asked for it. */
    fun status(action: suspend StatusPanel.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.pages?.status?.action() }
    }

    /** Runs one settings action on the app's scope, so it outlives the activity that asked for it. */
    fun settings(action: suspend SettingsControl.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.pages?.settings?.action() }
    }

    /** Runs one skills-page action on the app's scope, so it outlives the activity that asked for it. */
    fun skills(action: suspend SkillsControl.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.pages?.skills?.action() }
    }

    /** Runs one Telegram setup action on the app's scope, so it outlives the activity that asked for it. */
    fun telegram(action: suspend TelegramSetup.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.pages?.telegram?.action() }
    }

    /** Runs one jobs-page action on the app's scope, so it outlives the activity that asked for it. */
    fun jobs(action: suspend JobsControl.() -> Unit) {
        scope.launch { (state.value as? CoreState.Ready)?.pages?.jobs?.action() }
    }

    /** The core this process embeds; the one adapter is [EmbeddedPython]. */
    private val runtime: CoreRuntime = EmbeddedPython

    private suspend fun start(): CoreState.Ready {
        val began = SystemClock.elapsedRealtime()
        val paths = withContext(Dispatchers.IO) { installRuntime(this@HermesApplication) }
        @Suppress("DEPRECATION") val info = packageManager.getPackageInfo(packageName, 0)
        val app = AppPackage(info.versionName.orEmpty(),
                             if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
        val deps = CoreDeps(secrets, ForegroundTurnHost(this), notifier,
                            keyNames = PrefsKeyNames(getSharedPreferences("provider_keys", MODE_PRIVATE)),
                            customModels = PrefsCustomModels(getSharedPreferences("custom_models", MODE_PRIVATE)),
                            subnet = { wifiSubnet(this@HermesApplication) }, seen = seen,
                            queues = queueFiles(File(noBackupFilesDir, "queue")),  // app-private, never backed up
                            packageInfo = app, payloadJson = { assets.payloadManifest() })
        val ready = composeCore(runtime, paths, deps, scope)
        return ready.copy(about = "${ready.about} · ready in ${SystemClock.elapsedRealtime() - began} ms")
    }
}
