package com.nousresearch.hermes.android

import android.content.Context
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.Notifier
import com.nousresearch.hermes.android.chat.SessionHistory
import com.nousresearch.hermes.android.gateway.TelegramSetup
import com.nousresearch.hermes.android.setup.ApprovalSettings
import com.nousresearch.hermes.android.setup.ProviderSetup
import com.nousresearch.hermes.android.setup.SshSetup
import com.nousresearch.hermes.android.turn.TurnHost
import com.nousresearch.hermes.bridge.CoreRuntime
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.RuntimeInstaller
import com.nousresearch.hermes.bridge.SecretStore
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * Unpacks the APK's runtime payload, once per build (both of the app's processes may ask at once), and lays out
 * the directories the core runs in (D8): `HOME` = filesDir, `HERMES_HOME` = filesDir/.hermes.
 */
fun installRuntime(context: Context): HermesPaths {
    val runtime = RuntimeInstaller(File(context.noBackupFilesDir, "runtime")).install(AssetPayload(context.assets))
    return HermesPaths(runtime, context.filesDir, File(context.filesDir, ".hermes"), context.cacheDir)
}

/**
 * The chat process's composition root: starts [runtime]'s core and builds what the screens drive over it. The
 * chat's session is open on return, and setup is showing already when the core has no provider yet.
 */
suspend fun composeCore(runtime: CoreRuntime, paths: HermesPaths, secrets: SecretStore, turns: TurnHost,
                        notifier: Notifier, scope: CoroutineScope): CoreState.Ready {
    val gateway = runtime.connect(paths, secrets)
    val bridge = runtime.bridge
    val keepSecret: suspend (String, ByteArray) -> Unit = { name, value ->
        secrets.put(name, value)
        bridge.secretsChanged()
    }
    val chat = ChatSession(gateway, turns, scope, keepSecret, notifier).apply { open() }
    val setup = ProviderSetup(gateway, secrets, bridge, session = { chat.sessionId }, reopenChat = chat::open)
    val history = SessionHistory(list = chat::pastSessions, resume = chat::resume)
    // Undispatched: setup is showing before the core is Ready, so the chat never flashes first.
    if (setup.needed()) scope.launch(start = CoroutineStart.UNDISPATCHED) { setup.open() }
    val facts = bridge.ask(BridgeCalls.RUNTIME_FACTS)
    val fts = if (facts.fts5) "with FTS5" else "without FTS5"
    return CoreState.Ready(gateway, chat, setup, SshSetup(secrets, bridge), TelegramSetup(secrets, bridge), history,
                           ApprovalSettings(gateway), "Python ${facts.python} · SQLite ${facts.sqlite} $fts")
}
