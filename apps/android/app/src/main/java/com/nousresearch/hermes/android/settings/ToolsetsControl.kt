package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ToolsAction
import com.nousresearch.hermes.bridge.protocol.ToolsConfigureParams
import com.nousresearch.hermes.bridge.protocol._SessionScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/** One call admitted: busy, or null when not (one already running). */
private fun ToolsetsUi.writing(): ToolsetsUi? = if (busy) null else copy(busy = true, error = null)

/**
 * The process's one Toolsets page (batch-4 bet 12, F2; capabilities-10); [state] is null while it is closed. It lists
 * the config's toolsets (`toolsets.list`) with which need keys and which the user can switch (`toolsets_keys`), and
 * switches one through `tools.configure`, the core's writer of `platform_toolsets.cli` (Dv16).
 *
 * The call that would reset the open chat is not expressible (DC-P24, EJ-51): `tools.configure` given a session rebuilds
 * that chat's agent and empties its history, so this class holds, reads and sends no session, and both lists are the
 * config's, the set a switch changes (D5). A switch shows the core's re-read set, never the ask (D6). One call at a
 * time: a tap while one runs sends nothing (EJ-78). Changes reach new chats only (§8); the open chat keeps its tools.
 * A toolset's tools are read through `tools.list`, since `toolsets.list` names none (P1 D3), also without a session.
 */
class ToolsetsControl(private val core: CoreGateway, private val bridge: AppBridge) {
    private val screen = MutableStateFlow<ToolsetsUi?>(null)
    val state: StateFlow<ToolsetsUi?> = screen.asStateFlow()

    /**
     * Shows the page at once, busy with no rows, then the toolsets the user can switch (D2). Either read failing shows
     * its words and no rows: without `switchable` nothing can be listed truthfully. A page closed meanwhile stays closed.
     * `toolsets_keys` runs apart from the core's frames (`blocking`); the two reads are ordered by awaiting the first,
     * as `AppBridge` asks of dependent calls.
     */
    suspend fun open() {
        screen.value = ToolsetsUi(busy = true)
        try {
            val rows = core.call(Methods.TOOLSETS_LIST, _SessionScoped()).toolsets
            val listed = ToolsetRow.listed(rows, bridge.ask(BridgeCalls.TOOLSETS_KEYS))
            screen.update { it?.copy(rows = listed, busy = false) }
        } catch (cancelled: CancellationException) {
            screen.update { it?.copy(busy = false) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            screen.update { it?.copy(busy = false, error = words) }
        }
    }

    fun close() {
        screen.value = null
    }

    /** Narrows the shown rows to [text] ([ToolsetsUi.shown]); the core is not asked. */
    fun query(text: String) {
        screen.update { it?.copy(query = text) }
    }

    /**
     * Turns the listed toolsets [names] on or off for new chats, in one `tools.configure {action, names}`, and shows the
     * core's answer: every row on exactly when its `enabled_toolsets` names it. A name the core answers `unknown` stays
     * as shown, with words. No listed name, a closed page or a call already running sends nothing. A refusal keeps the
     * rows the core last answered and shows its words.
     */
    suspend fun set(names: List<String>, on: Boolean) {
        val page = admit { ui -> names.any { name -> ui.rows.orEmpty().any { it.name == name } } } ?: return
        val rows = page.rows.orEmpty()
        val kept = names.distinct().filter { name -> rows.any { it.name == name } }
        finish {
            val answer = core.call(Methods.TOOLS_CONFIGURE,
                                   ToolsConfigureParams(action = if (on) ToolsAction.ENABLE else ToolsAction.DISABLE, names = kept))
            val refused = answer.unknown.firstOrNull()?.let { name -> rows.find { it.name == name }?.label ?: name }
            ({ it.copy(rows = it.rows?.switched(answer.enabledToolsets), error = refused?.let { label -> "“$label” can't be switched here." }) })
        }
    }

    /**
     * Opens the listed toolset [name] at once, on an idle page with nothing open. Its tools come from `tools.list`,
     * because `toolsets.list` names none (P1 D3), read without a session id (D5) once per page open for every toolset:
     * a later tap shows them at once. Tools the core cannot list show its words, stay unread, and the next tap reads
     * again.
     */
    suspend fun show(name: String) {
        val opens: (ToolsetsUi) -> Boolean = { ui -> !ui.busy && ui.open == null && ui.rows.orEmpty().any { it.name == name } }
        val before = screen.getAndUpdate { ui ->
            ui?.takeIf(opens)?.copy(open = name, error = null)?.let { if (it.opened()?.tools == null) it.copy(busy = true) else it } ?: ui
        }
        if (before == null || !opens(before) || before.copy(open = name).opened()?.tools != null) return
        finish {
            val tools = core.call(Methods.TOOLS_LIST, _SessionScoped()).toolsets
            ({ it.copy(rows = it.rows?.withTools(tools)) })
        }
    }

    /** Back from a toolset's tools to the list; nothing while a read runs. */
    fun back() {
        screen.update { if (it == null || it.busy) it else it.copy(open = null, error = null) }
    }

    /** Admits one call into an open, idle page that is [allowed] in one atomic step, answering the page as it was (null: not admitted). */
    private fun admit(allowed: (ToolsetsUi) -> Boolean): ToolsetsUi? =
        screen.getAndUpdate { ui -> ui?.takeIf(allowed)?.writing() ?: ui }?.takeIf { allowed(it) && it.writing() != null }

    /**
     * The one tail of an admitted call: [act] calls and returns how the page shows the core's answer. A refusal (or any
     * failure) shows its words and changes nothing else; either way the page is idle again.
     */
    private suspend inline fun finish(act: () -> (ToolsetsUi) -> ToolsetsUi) {
        val shown: (ToolsetsUi) -> ToolsetsUi = try {
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
}
