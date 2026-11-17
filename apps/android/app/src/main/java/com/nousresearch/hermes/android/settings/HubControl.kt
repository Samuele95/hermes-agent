package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreError
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.HubScanParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SkillInspectInfo
import com.nousresearch.hermes.bridge.protocol.SkillsAction
import com.nousresearch.hermes.bridge.protocol.SkillsManageParams
import com.nousresearch.hermes.bridge.protocol.SkillsReloadParams
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/** One step admitted: busy, or null when not (one already running); the last refusal stays shown (D4). */
private fun HubUi.writing(): HubUi? = if (busy) null else copy(busy = true, error = null, notice = null)

/**
 * The process's one Skills Hub page (batch-4 bet 11, F9; capabilities-08); [state] is null while it is closed. The hub
 * runs through the contracted `skills.manage` (DC-P21: search and browse fan out on the core's own pool, so the chat's
 * frames are not held); the bridge is asked only where the protocol has nothing, the official list read locally
 * (`hub_official`). One read at a time: a search while one runs sends nothing (EJ-78). A read's answer lands only on
 * the page it was asked from: Back stays open while the core fans out, and a page closed or reopened meanwhile is left
 * alone (D11). An offline hub is the core's ordinary answer, empty or official only, and claims nothing else (D13).
 *
 * A preview (`skills.manage inspect`) runs the security scan at once (`hub_scan`, the bridge's blocking call), and
 * Install waits for it (D1); a skill the scan's policy refuses is not installed, the core's rule (D3), and whether
 * Install is offered is [mayInstall] alone. One install at a time; Back waits for it. After an install, the core reloads
 * its skills for the open chat's [session] (`skills.reload`, §2.4, DC-P23: its `/` menu follows at once), then
 * `skills_list` is re-read and handed to the Skills page beneath ([listed], D9). A refusal keeps the preview and says
 * why in the core's words, its console markup removed (D10).
 */
class HubControl(private val core: CoreGateway, private val bridge: AppBridge, private val session: () -> String?,
                 private val listed: (List<SkillRow>) -> Unit = {}) {
    private val screen = MutableStateFlow<HubUi?>(null)
    val state: StateFlow<HubUi?> = screen.asStateFlow()

    /** The page showing now: bumped at each open and close, so an older page's answer lands nowhere (D11). */
    private val views = AtomicLong()

    /**
     * Shows the page at once, busy, then the official skills (`hub_official`, no network) and the installed names
     * (`skills_list`, D8); a read that fails shows its words and keeps what the other read.
     */
    suspend fun open() {
        val view = views.incrementAndGet()
        screen.value = HubUi(busy = true)
        var failure: String? = null
        val official = read({ failure = failure ?: it }) { bridge.ask(BridgeCalls.HUB_OFFICIAL).skills.map(HubItem::of) }
        val installed = read({ failure = failure ?: it }) { bridge.ask(BridgeCalls.SKILLS_LIST).skills.map { it.name }.toSet() }
        land(view) { it.copy(official = official, installed = installed.orEmpty(), busy = false, error = failure) }
    }

    /** Closes the page; nothing while an install runs. */
    fun close() {
        if (screen.value?.installing != null) return
        views.incrementAndGet()
        screen.update { if (it?.installing != null) it else null }
    }

    /** The field's [text]: it narrows "Available to install" ([available]); nothing is sent. */
    fun query(text: String) {
        screen.update { it?.copy(query = text) }
    }

    /** Searches the whole hub for the trimmed [query] (`skills.manage search`, ≤ 30 s on the core's pool); blank sends nothing. */
    suspend fun search(query: String) {
        val wanted = query.trim()
        if (wanted.isEmpty()) return
        val view = admitted() ?: return
        finish(view) {
            val hits = core.call(Methods.SKILLS_MANAGE, SkillsManageParams(action = SkillsAction.SEARCH, query = wanted)).results
            val results = HubResults(hits.orEmpty().map(HubItem::of), query = wanted)
            ({ it.copy(items = results) })
        }
    }

    /** Browses the hub's [page] (from 1) of twenty (`skills.manage browse`), with the core's paging. */
    suspend fun browse(page: Long) {
        if (page < 1) return
        val view = admitted() ?: return
        finish(view) {
            val answer = core.call(Methods.SKILLS_MANAGE, SkillsManageParams(action = SkillsAction.BROWSE, page = page, pageSize = PAGE_SIZE))
            val results = HubResults(answer.items.orEmpty().map(HubItem::of), null, answer.page ?: page, answer.totalPages ?: 1)
            ({ it.copy(items = results) })
        }
    }

    /**
     * Opens the preview of the shown skill [key] (`skills.manage inspect`) on an idle page with none open, then scans the
     * identifier the core resolved (`hub_scan`, D1, D6). A preview the core cannot answer (its `{}`) stays closed and
     * says so (D14); every step lands only on the page it was asked from (D11).
     */
    suspend fun preview(key: String) {
        val view = views.get()
        val before = admit { ui -> ui.open == null && ui.shown(key) != null } ?: return
        val listed = before.shown(key) ?: return
        val info = try {
            core.call(Methods.SKILLS_MANAGE, SkillsManageParams(action = SkillsAction.INSPECT, query = key)).info
        } catch (cancelled: CancellationException) {
            land(view) { it.copy(busy = false) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            land(view) { it.copy(busy = false, error = words) }
            return
        }
        val preview = HubPreview.of(info ?: SkillInspectInfo(), listed)
        if (preview == null) {
            land(view) { it.copy(busy = false, error = "The hub has no preview of “${listed.name}”.") }
            return
        }
        land(view) { it.copy(open = preview) }
        scanned(view, preview.item.key)
    }

    /** Back from the preview to the list, its scan dropped (D11); nothing while an install runs. */
    fun closePreview() {
        if (screen.value?.installing != null) return
        views.incrementAndGet()
        screen.update { ui -> if (ui == null || ui.installing != null) ui else ui.copy(open = null, blocked = null, busy = false) }
    }

    /** Scans the open preview's skill again ("Scan again", "View scan"); only its [identifier], on an idle page. */
    suspend fun scan(identifier: String) {
        val view = views.get()
        admit { it.open?.item?.key == identifier } ?: return
        scanned(view, identifier)
    }

    /**
     * Installs the open preview's [identifier] (`skills.manage install`: the core fetches, quarantines, scans and applies
     * its policy), admitted only when [mayInstall], in one atomic step, so a double tap installs once. Installed: the
     * name is installed and said, then `skills.reload` for the open chat and `skills_list`, handed to the Skills page
     * ([listed]); a failure of that follow-up shows its words and the install stays shown. Refused: [HubUi.blocked] in
     * the core's words, nothing reloaded.
     */
    suspend fun install(identifier: String) {
        val allowed = { ui: HubUi -> ui.mayInstall() && ui.open?.item?.key == identifier }
        val before = screen.getAndUpdate { ui -> ui?.takeIf(allowed)?.writing()?.copy(installing = identifier, blocked = null) ?: ui }
        val name = before?.takeIf(allowed)?.open?.item?.name ?: return
        val outcome: (HubUi) -> HubUi = try {
            val answer = core.call(Methods.SKILLS_MANAGE, SkillsManageParams(action = SkillsAction.INSTALL, query = identifier))
            if (answer.installed == true) followed(name)
            else ({ it.copy(blocked = HubBlock(name, "skill install failed", false)) })  // the core's own fallback words
        } catch (cancelled: CancellationException) {
            screen.update { ui -> if (ui?.installing != identifier) ui else ui.copy(installing = null, busy = false) }
            throw cancelled
        } catch (refused: CoreError) {
            val block = HubBlock.of(name, refused.message.orEmpty())
            ({ it.copy(blocked = block) })
        } catch (failed: Exception) {
            val block = HubBlock(name, forUser(failed), false)
            ({ it.copy(blocked = block) })
        }
        screen.update { ui -> if (ui?.installing != identifier) ui else outcome(ui).copy(installing = null, busy = false) }
    }

    /**
     * An install the core accepted for [name]: shown at once, then the core reloads its skills for the open chat and the
     * page shows `skills_list`'s names, handed to the Skills page beneath; a failed follow-up shows its words.
     */
    private suspend fun followed(name: String): (HubUi) -> HubUi {
        val installed: (HubUi) -> HubUi = { it.copy(installed = it.installed + name, notice = name) }
        val after: (HubUi) -> HubUi = try {
            core.call(Methods.SKILLS_RELOAD, SkillsReloadParams(sessionId = session()))
            val rows = bridge.ask(BridgeCalls.SKILLS_LIST).skills.map(SkillRow::of)
            listed(rows)
            val names = rows.map { it.name }.toSet()
            ({ it.copy(installed = names) })
        } catch (cancelled: CancellationException) {
            screen.update { ui -> if (ui?.installing == null) ui else installed(ui).copy(installing = null, busy = false) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            ({ it.copy(error = words) })
        }
        return { after(installed(it)) }
    }

    /** The open preview's scan of [identifier] (`hub_scan`), or the words it failed with; the page is idle after (D11). */
    private suspend fun scanned(view: Long, identifier: String) {
        val (scan, failure) = try {
            HubScan.of(bridge.ask(BridgeCalls.HUB_SCAN, HubScanParams(identifier))) to null
        } catch (cancelled: CancellationException) {
            land(view) { it.copy(busy = false) }
            throw cancelled
        } catch (failed: Exception) {
            null to forUser(failed)
        }
        land(view) { ui -> ui.copy(open = ui.open?.copy(scan = scan, scanFailed = failure), busy = false) }
    }

    /** The shown skill [key]: in "Available to install" or the last search or browse. */
    private fun HubUi.shown(key: String): HubItem? = (available().orEmpty() + items?.items.orEmpty()).firstOrNull { it.key == key }

    /** Admits one step into an open, idle page that is [allowed] in one atomic step: the page as it was, or null. */
    private fun admit(allowed: (HubUi) -> Boolean): HubUi? =
        screen.getAndUpdate { ui -> ui?.takeIf(allowed)?.writing() ?: ui }?.takeIf { allowed(it) && it.writing() != null }

    /** [act]'s answer, or null with its words handed to [failed]. */
    private suspend inline fun <T> read(failed: (String) -> Unit, act: () -> T): T? = try {
        act()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failed(forUser(failure))
        null
    }

    /** Changes the page by [change] only while [view] is the page showing (D11). */
    private fun land(view: Long, change: (HubUi) -> HubUi) {
        screen.update { ui -> if (ui == null || views.get() != view) ui else change(ui) }
    }

    /** Admits one read into an open, idle page in one atomic step: the page's view, or null when not admitted. */
    private fun admitted(): Long? {
        val view = views.get()
        return view.takeIf { admit { true } != null }
    }

    /**
     * The one tail of an admitted read: [act] asks and returns how the page shows the core's answer. A failure shows its
     * words and changes nothing else; either way the page is idle again, if it is still the one showing.
     */
    private suspend inline fun finish(view: Long, act: () -> (HubUi) -> HubUi) {
        val shown: (HubUi) -> HubUi = try {
            act()
        } catch (cancelled: CancellationException) {
            land(view) { it.copy(busy = false) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            ({ it.copy(error = words) })
        }
        land(view) { shown(it).copy(busy = false) }
    }

    private companion object {
        /** Desktop's and the core's page size. */
        const val PAGE_SIZE = 20L
    }
}
