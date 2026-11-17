package com.nousresearch.hermes.android

import android.content.Context
import com.nousresearch.hermes.android.chat.ChatSession
import com.nousresearch.hermes.android.chat.ContextPanel
import com.nousresearch.hermes.android.chat.Notifier
import com.nousresearch.hermes.android.chat.QueueStore
import com.nousresearch.hermes.android.chat.SessionExport
import com.nousresearch.hermes.android.chat.SessionHistory
import com.nousresearch.hermes.android.chat.ModelSwitch
import com.nousresearch.hermes.android.chat.StatusPanel
import com.nousresearch.hermes.android.chat.exportSession
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.android.gateway.TelegramSetup
import com.nousresearch.hermes.android.jobs.JobsControl
import com.nousresearch.hermes.android.jobs.runOwnership
import com.nousresearch.hermes.android.settings.About
import com.nousresearch.hermes.android.settings.AppPackage
import com.nousresearch.hermes.android.settings.HubControl
import com.nousresearch.hermes.android.settings.ProviderAccess
import com.nousresearch.hermes.android.settings.SettingsControl
import com.nousresearch.hermes.android.settings.SkillsControl
import com.nousresearch.hermes.android.settings.aboutOf
import com.nousresearch.hermes.android.setup.CustomModels
import com.nousresearch.hermes.android.setup.KeyNames
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
import kotlinx.coroutines.CompletableDeferred
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
 * What the platform hands the chat process's core (D39 F12): the keystore [secrets], the [turns] host that keeps a
 * running turn alive, the [notifier] for a question or reply while the app is off screen, the provider [keyNames],
 * the provider [customModels]: ids the user typed, kept once the core took one,
 * the phone's Wi-Fi [subnet] for the endpoint search, [seen], the app's own observation of the always-on gateway:
 * the one value the chat, the jobs page and the bot's live state all judge it by (§3.1), and [queues], where the chat
 * keeps each session's queue of unsent messages (batch-3 F2); [packageInfo] the APK's version and [payloadJson] the
 * payload's manifest, which About reads at each open (F13), and [log], the platform's log for what the chat notes for
 * diagnosis. A field left out is today's default: names and model ids kept in memory, no subnet, a stopped gateway,
 * queues kept in memory, nothing logged. Its lambdas compare by identity, so two values are
 * never compared.
 */
data class CoreDeps(val secrets: SecretStore, val turns: TurnHost, val notifier: Notifier,
                    val keyNames: KeyNames = KeyNames.InMemory(),
                    val customModels: CustomModels = CustomModels.InMemory(),
                    val subnet: suspend () -> String? = { null },
                    val seen: () -> GatewaySeen = { GatewaySeen(GatewayStatus.STOPPED) },
                    val queues: QueueStore = QueueStore.inMemory(),
                    val packageInfo: AppPackage = AppPackage("", 0),
                    val payloadJson: () -> String = { "{}" },
                    val log: (String) -> Unit = {})

/**
 * The chat process's composition root: starts [runtime]'s core and builds what the screens drive over it. The
 * chat is in the core's most recent conversation on return (a new one when there is none, or the core's setting says
 * not to reopen), and setup is showing already when the core has no provider yet; the job form's delivery targets
 * and the skills listing start loading once these startup calls are done, and the provider's readiness is checked
 * once these calls and the warm are done, and again each time setup closes. [deps] are the platform's collaborators;
 * its [CoreDeps.seen] reaches every reader of the gateway's state.
 */
suspend fun composeCore(runtime: CoreRuntime, paths: HermesPaths, deps: CoreDeps, scope: CoroutineScope): CoreState.Ready {
    val gateway = runtime.connect(paths, deps.secrets)
    val bridge = runtime.bridge
    val keepSecret: suspend (String, ByteArray) -> Unit = { name, value ->
        deps.secrets.put(name, value)
        bridge.secretsChanged()
    }
    val readiness = CompletableDeferred<ProviderSetup>()  // the chat is built first; a failure it hears waits for setup (D9)
    val picker = CompletableDeferred<ModelSwitch>()  // the chat is built first; /model waits for the picker (D13)
    val chat = ChatSession(gateway, deps.turns, scope, keepSecret, deps.notifier, gatewaySeen = deps.seen,
                           runOwned = { bridge.runOwnership(it).owned }, queues = deps.queues,
                           providerFailed = { readiness.await().checkReadiness() },
                           exporter = { bridge.exportSession(it) }, log = deps.log,
                           openModels = { search -> picker.await().run { open(); if (search.isNotBlank()) query(search) } }).apply { start() }
    val setup = ProviderSetup(gateway, deps.secrets, bridge, session = { chat.sessionId.value }, reopenChat = chat::open,
                              keyNames = deps.keyNames, subnet = deps.subnet, customModels = deps.customModels)
    readiness.complete(setup)
    val history = SessionHistory(list = chat::pastSessions, resume = chat::resume, rename = chat::rename, delete = chat::delete)
    val context = ContextPanel(meter = { chat.state.value.meter }, breakdown = chat::breakdown, compress = chat::compress, compressing = { chat.state.value.compressing })
    val export = SessionExport(export = chat::export)
    val status = StatusPanel(status = chat::status)
    val models = ModelSwitch(gateway, session = { chat.openSessionId?.value }, custom = deps.customModels)
    picker.complete(models)
    // Undispatched: setup is showing before the core is Ready, so the chat never flashes first.
    if (setup.needed()) scope.launch(start = CoroutineStart.UNDISPATCHED) { setup.open() }
    val facts = bridge.ask(BridgeCalls.RUNTIME_FACTS)
    // The start-up facts: they cannot change in this process; the manifest at each open (D4).
    val about: suspend () -> About = { aboutOf(deps.packageInfo, deps.payloadJson(), facts) }
    val settings = SettingsControl(gateway, bridge, deps.seen, ProviderAccess(setup::connected, setup::remove), about)
    // D4: the chat knows the switch before Settings opens; Ready never waits. Launched before the warm, which stays the
    // composition's last call (K1).
    scope.launch { settings.readReactions() }
    // Last: the warm's plugin loads (the delivery targets, then the skills listing) share the GIL with every call above
    // (+650 ms to ready, K1); the chat is open now.
    bridge.ask(BridgeCalls.CRON_WARM_DELIVERY_TARGETS)
    val fts = if (facts.fts5) "with FTS5" else "without FTS5"
    // Last, and launched: the probe shares the GIL with every start-up call (K1b) and may take its 4 s budget; Ready never waits (F6).
    scope.launch { setup.followReadiness() }
    scope.launch { setup.checkReadiness() }
    val skills = SkillsControl(gateway, bridge, session = { chat.openSessionId?.value })
    return CoreState.Ready(gateway, chat, setup,
                           Pages(settings,
                                 skills, JobsControl(gateway, bridge, deps.seen, continueInChat = chat::resume),
                                 TelegramSetup(deps.secrets, bridge, deps.seen), SshSetup(deps.secrets, bridge), history, context,
                                 export, status, models, HubControl(gateway, bridge, session = { chat.openSessionId?.value }, listed = skills::listed)),
                           "Python ${facts.python} · SQLite ${facts.sqlite} $fts")
}
