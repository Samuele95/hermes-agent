package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SkillsAction
import com.nousresearch.hermes.bridge.protocol.SkillsManageParams
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/** One read admitted: busy, or null when not (one already running). */
private fun HubUi.writing(): HubUi? = if (busy) null else copy(busy = true, error = null)

/**
 * The process's one Skills Hub page (batch-4 bet 11, F9; capabilities-08); [state] is null while it is closed. The hub
 * runs through the contracted `skills.manage` (DC-P21: search and browse fan out on the core's own pool, so the chat's
 * frames are not held); the bridge is asked only where the protocol has nothing, the official list read locally
 * (`hub_official`). One read at a time: a search while one runs sends nothing (EJ-78). A read's answer lands only on
 * the page it was asked from: Back stays open while the core fans out, and a page closed or reopened meanwhile is left
 * alone (D11). An offline hub is the core's ordinary answer, empty or official only, and claims nothing else (D13).
 */
class HubControl(private val core: CoreGateway, private val bridge: AppBridge, private val session: () -> String?) {
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

    fun close() {
        views.incrementAndGet()
        screen.value = null
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
        val before = screen.getAndUpdate { ui -> ui?.writing() ?: ui }
        return view.takeIf { before?.writing() != null }
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
