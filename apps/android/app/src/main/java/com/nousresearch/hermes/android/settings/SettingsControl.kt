package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.android.gateway.GatewaySeen
import com.nousresearch.hermes.android.gateway.GatewayStatus
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.protocol.ConfigGetParams
import com.nousresearch.hermes.bridge.protocol.ConfigSetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/** One write admitted: busy, or null when not (the page closed, or a write already running). */
private fun SettingsUi.writing(): SettingsUi? = if (busy) null else copy(busy = true, error = null, outcome = null)

/** The asked removal admitted: busy with the question gone, or null when not (busy, or nothing asked). */
private fun SettingsUi.removing(): SettingsUi? = if (busy || confirmRemove == null) null else copy(confirmRemove = null, busy = true, error = null, outcome = null)

/** The asked provider removal admitted: busy with the question gone, or null when not (busy, or nothing asked). */
private fun SettingsUi.removingProvider(): SettingsUi? =
    if (busy || confirmProvider == null) null else copy(confirmProvider = null, busy = true, error = null, outcome = null)

/**
 * What the page may do with provider auth, which `ProviderSetup` owns alone (DC-M20, EJ-5): list the connected
 * providers, and remove one.
 */
class ProviderAccess(val connected: suspend () -> List<ConnectedProvider>, val remove: suspend (slug: String) -> Unit)

/**
 * The app's settings that live in the core's config (Dv22): read and written over the protocol, like the
 * desktop's, so the core stays the one owner. [state] is null while the screen is closed.
 * The process's one settings page (batch-3 F12), beside the other pages' controllers; `setup/` keeps provider auth.
 *
 * Two channels, each key by exactly one (§0 coherence 2): `approvals.mode` and `personality` through the core's
 * `config.set`; the approval timeout, the allowlist and the four `memory.*` keys through the app's `settings_write`
 * ([bridge], under the app's config lock). The invariants (§2.4): the page shows only what the core last answered, so
 * every accepted write is followed by a re-read that replaces what is shown ([approvals] keeps today's rule, D4: the
 * mode `config.set` accepted); a re-read that fails after an accepted write keeps the snapshot and says it was saved
 * but not confirmed, apart from a refused write ([WriteOutcome], architect #4); a write is one [SettingsEdit] or one `config.set`, one at a time (a second tap while one
 * runs sends nothing, EJ-78); a removal asks first ([askRemove]) and removes only the pattern asked about; the
 * personality is written without a session (DC-M17: Desktop's "for new sessions"; with a session id the core would
 * splice a pivot into the open chat). The page repeats none of the bridge's bounds (D5): a refusal shows its words.
 *
 * A removal reaches this process at once (the bridge reconciles it) and the always-on gateway at its next start
 * (Dv31, DC-M16): [seen] is the app's one observation of it (D2), and [SettingsUi.nextStart] holds while the gateway
 * that ran at the removal still runs (D3, as `TelegramSetup` marks its own).
 *
 * Providers ([providers]): listed when the page opens; a removal asks first, goes through provider setup's one forget
 * path, and the page then shows the core's re-read (F10).
 *
 * About ([about]) is read at each open from the APK, the payload's manifest and the start-up facts (F13); no write
 * re-reads it.
 */
class SettingsControl(private val core: CoreGateway, private val bridge: AppBridge, private val seen: () -> GatewaySeen,
                      private val providers: ProviderAccess, private val about: suspend () -> About) {
    private companion object {
        const val KEY = "approvals.mode"
        const val PERSONALITY = "personality"
    }

    private val screen = MutableStateFlow<SettingsUi?>(null)
    val state: StateFlow<SettingsUi?> = screen.asStateFlow()

    /** The pid of the gateway that was running when a removal last applied (null: nothing waits for a start). */
    private val nextStartUnder = AtomicReference<Int?>(null)

    /**
     * Reads the mode, the personality, the bridge's settings, the connected providers and About, each on its own: a
     * part that fails is null, and the first failure's words show.
     */
    suspend fun open() {
        var error: String? = null
        val keep: (String) -> Unit = { if (error == null) error = it }
        val mode = orNull(keep) { ApprovalMode.of(core.call(Methods.CONFIG_GET, ConfigGetParams(key = KEY)).value) }
        val personality = orNull(keep) { core.call(Methods.CONFIG_GET, ConfigGetParams(key = PERSONALITY)).value }
        val config = orNull(keep) { ConfigSettings.of(bridge.ask(BridgeCalls.SETTINGS_READ)) }
        val connected = orNull(keep) { providers.connected() }
        val shown = orNull(keep) { about() }
        screen.value = SettingsUi(mode, personality, config, connected, shown, nextStart = mark(seen()) != null, error = error)
    }

    fun close() {
        screen.value = null
        nextStartUnder.set(null)
    }

    /** Writes [mode] for every session from now on; a refusal keeps the shown mode and says why. */
    suspend fun approvals(mode: ApprovalMode) {
        if (!admitted()) return
        finish {
            core.call(Methods.CONFIG_SET, ConfigSetParams(key = KEY, value = JsonPrimitive(mode.wire)))
            ({ it.copy(approvals = mode) })
        }
    }

    /**
     * Writes the personality [name] for new sessions only: `config.set` with no session id (the class has none to
     * give), then shows `config.get`'s effective name (D4). A refusal (an unknown name) keeps the shown one, in the core's words.
     */
    suspend fun personality(name: String) {
        if (!admitted()) return
        finish {
            core.call(Methods.CONFIG_SET, ConfigSetParams(key = PERSONALITY, value = JsonPrimitive(name)))
            val now = core.call(Methods.CONFIG_GET, ConfigGetParams(key = PERSONALITY)).value
            ({ it.copy(personality = now) })
        }
    }

    /**
     * Writes [change] through `settings_write`, then shows the re-read whole (EJ-17); a refusal re-reads nothing, and a
     * re-read that fails keeps what is shown and says the write was saved ([WriteOutcome]).
     */
    suspend fun edit(change: SettingsEdit) {
        if (!admitted()) return
        finish { reread(change) }
    }

    /** Asks to remove the allowed [key] (null stops asking); a pattern not listed, or a page busy, asks nothing. Nothing is sent. */
    fun askRemove(key: String?) {
        screen.update { ui ->
            if (ui == null || ui.busy) ui
            else ui.copy(confirmRemove = key?.takeIf { it in ui.config?.allowlist.orEmpty() }, confirmProvider = null, error = null, outcome = null)
        }
    }

    /** Removes the pattern [askRemove] named, once: the gateway seen running now is marked to follow at its next start. */
    suspend fun confirmRemove() {
        val before = screen.getAndUpdate { it?.removing() ?: it }  // one atomic step: a double tap removes once
        val pattern = before?.takeIf { it.removing() != null }?.confirmRemove ?: return
        finish {
            val shown = reread(SettingsEdit(removeAllowed = listOf(pattern))) { now ->
                if (now.status == GatewayStatus.RUNNING && now.pid != null) nextStartUnder.set(now.pid)
            }
            val waiting = mark(seen()) != null
            ({ shown(it).copy(nextStart = waiting) })
        }
    }

    /** Asks to remove the provider [slug] (null stops asking); one not listed as removable, or a page busy, asks nothing. Nothing is sent. */
    fun askRemoveProvider(slug: String?) {
        screen.update { ui ->
            if (ui == null || ui.busy) ui
            else ui.copy(confirmProvider = slug?.takeIf { s -> ui.connected.orEmpty().any { it.slug == s && it.removable } },
                         confirmRemove = null, error = null, outcome = null)
        }
    }

    /**
     * Removes the provider [askRemoveProvider] named, once, through provider setup (keystore when held,
     * `model.disconnect`, a new chat and setup when it was in use, readiness again); then shows the re-read. One still
     * listed is managed outside the app (D6). A refusal shows its words and keeps the list (D10).
     */
    suspend fun confirmRemoveProvider() {
        val before = screen.getAndUpdate { it?.removingProvider() ?: it }  // one atomic step: a double tap removes once
        val slug = before?.takeIf { it.removingProvider() != null }?.confirmProvider ?: return
        val name = before.connected?.firstOrNull { it.slug == slug }?.name ?: slug
        finish {
            providers.remove(slug)
            val now = providers.connected()  // the page shows the core's re-read (§2.4)
            val still = if (now.any { it.slug == slug }) "$name is still connected: its credential is managed outside the app." else null
            ({ it.copy(connected = now, error = still) })
        }
    }

    /** Follows the gateway as observed ([changes]) while the page shows: the note clears once the marked pid is gone (a new process holds the new list). */
    suspend fun followGateway(changes: Flow<GatewaySeen>) {
        changes.collect { now ->
            val waiting = mark(now) != null
            screen.update { it?.copy(nextStart = waiting) }
        }
    }

    /** The marked pid while [now] is still that process; forgotten once it is not. */
    private fun mark(now: GatewaySeen): Int? = nextStartUnder.updateAndGet { under -> under?.takeIf { it == now.pid } }

    /** Admits one write into an open, idle page in one atomic step. */
    private fun admitted(): Boolean = screen.getAndUpdate { it?.writing() ?: it }?.writing() != null

    /**
     * `settings_write` [change], then the re-read, each in its own try (architect #4, EJ-77): a write that fails is
     * [WriteOutcome.Refused] and nothing else runs; once it is accepted, [applied] runs with the gateway seen now, and
     * the re-read replaces the shown settings, or, when it fails, the snapshot stays with [WriteOutcome.AppliedUnconfirmed].
     * A cancellation is rethrown ([forUser]).
     */
    private suspend inline fun reread(change: SettingsEdit, applied: (GatewaySeen) -> Unit = {}): (SettingsUi) -> SettingsUi {
        try {
            bridge.ask(BridgeCalls.SETTINGS_WRITE, change.params())
        } catch (failed: Exception) {
            val words = forUser(failed)
            return { it.copy(outcome = WriteOutcome.Refused(words)) }
        }
        applied(seen())
        val config = try {
            ConfigSettings.of(bridge.ask(BridgeCalls.SETTINGS_READ))
        } catch (failed: Exception) {
            val words = forUser(failed)
            return { it.copy(outcome = WriteOutcome.AppliedUnconfirmed(words)) }
        }
        return { it.copy(config = config) }
    }

    /**
     * The one tail of an admitted write: [act] writes and returns how the page shows the core's answer. A failure [act]
     * did not handle itself shows its words in [SettingsUi.error] (`reread` reports its own, as [SettingsUi.outcome]) and
     * changes nothing else; either way the page is idle again.
     */
    private suspend inline fun finish(act: () -> (SettingsUi) -> SettingsUi) {
        val shown: (SettingsUi) -> SettingsUi = try {
            act()
        } catch (cancelled: CancellationException) {
            screen.update { it?.copy(busy = false) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            ({ it.copy(error = words) })
        }
        screen.update { it?.let(shown)?.copy(busy = false) }
    }

    /** One read of [open]: its value, or null with its words handed to [failed]. */
    private inline fun <T> orNull(failed: (String) -> Unit, read: () -> T): T? =
        try {
            read()
        } catch (problem: Exception) {
            failed(forUser(problem))
            null
        }
}
