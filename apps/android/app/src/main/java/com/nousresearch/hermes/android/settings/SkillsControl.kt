package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.SkillsEnableParams
import com.nousresearch.hermes.bridge.protocol.LearningNodeParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.SkillsReloadParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/** One change admitted: busy, or null when not (a change already running). */
private fun SkillsUi.writing(): SkillsUi? = if (busy) null else copy(busy = true, error = null, notice = null)

/**
 * The process's one skills page (F11, §2.2): the core's skills over the app's `skills_list` / `skills_enable`;
 * [state] is null while the page is closed. Invariant (§2.4): a switch shows the core's re-read state. Every change
 * answers the list as the core now holds it, which replaces what is shown, never a prediction (EJ-51). An essential
 * skill the core refuses to turn off stays on, with the core's words (DDD: which skills those are is the core's rule;
 * this class names none). One change at a time: a tap while one runs sends nothing (EJ-78). Changes reach new chats
 * only (§8). Refresh is `skills.reload` for the open chat's [session] then `skills_list` (§2.4); the page shows the
 * core's last answer, a skill's text included (`learning.detail`, F10).
 */
class SkillsControl(private val core: CoreGateway, private val bridge: AppBridge, private val session: () -> String?) {
    private val screen = MutableStateFlow<SkillsUi?>(null)
    val state: StateFlow<SkillsUi?> = screen.asStateFlow()

    /** Shows the page at once, busy with no rows, then the core's list (D10); a page closed meanwhile stays closed. */
    suspend fun open() {
        screen.value = SkillsUi(busy = true)
        try {
            val rows = bridge.ask(BridgeCalls.SKILLS_LIST).skills.map(SkillRow::of)
            screen.update { it?.copy(rows = rows, busy = false) }
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

    /**
     * Turns [names] on or off in one call (a switch and a category alike, DC-M18) and shows the core's answer. Nothing
     * to send, a closed page or a change already running sends nothing. A refusal keeps the rows the core last
     * answered and shows its words.
     */
    suspend fun enable(names: List<String>, on: Boolean) {
        if (names.isEmpty() || !admitted()) return
        finish {
            val rows = bridge.ask(BridgeCalls.SKILLS_ENABLE, SkillsEnableParams(names, on)).skills.map(SkillRow::of)
            ({ it.copy(rows = rows) })
        }
    }

    /** Narrows the shown rows to [text] ([SkillsUi.shown]); the core is not asked. */
    fun query(text: String) {
        screen.update { it?.copy(query = text) }
    }

    /**
     * Refresh (§2.4): the core rescans the skill folders for the open chat's session (`skills.reload`, DC-P23: its `/`
     * menu follows at once), then the page shows `skills_list`'s answer. A failed reload keeps the rows with the core's
     * words and reads no list.
     */
    suspend fun refresh() {
        if (!admitted()) return
        finish {
            val rows = reloaded()
            ({ it.copy(rows = rows) })
        }
    }

    /**
     * Opens the listed skill [name] for reading: its SKILL.md as the core holds it (`learning.detail`). A refusal
     * (`ok: false`, e.g. not found) is an answer, not a failure (EJ-69): the skill stays closed with the core's words.
     * Only on an idle page with no skill open.
     */
    suspend fun read(name: String) {
        if (!admitted { ui -> ui.open == null && ui.rows.orEmpty().any { it.name == name } }) return
        finish {
            val answer = core.call(Methods.LEARNING_DETAIL, LearningNodeParams(id = name))
            val content = answer.content
            if (answer.ok && content != null) ({ it.copy(open = SkillText(name, content)) })
            else {
                val words = answer.message ?: "The skill could not be read."
                ({ it.copy(error = words) })
            }
        }
    }

    /** Back from the open skill to the list, its draft dropped; nothing while a change runs (D3). */
    fun closeSkill() {
        screen.update { if (it == null || it.busy) it else it.copy(open = null) }
    }

    /** The core rescans its skills for the open chat (`skills.reload`), then lists them (`skills_list`). */
    private suspend fun reloaded(): List<SkillRow> {
        core.call(Methods.SKILLS_RELOAD, SkillsReloadParams(sessionId = session()))
        return bridge.ask(BridgeCalls.SKILLS_LIST).skills.map(SkillRow::of)
    }

    /** Admits one change into an open, idle page that is [allowed] in one atomic step. */
    private fun admitted(allowed: (SkillsUi) -> Boolean = { true }): Boolean =
        screen.getAndUpdate { ui -> ui?.takeIf(allowed)?.writing() ?: ui }?.takeIf(allowed)?.writing() != null

    /**
     * The one tail of an admitted change: [act] writes and returns how the page shows the core's answer. A refusal (or
     * any failure) shows its words and changes nothing else; either way the page is idle again.
     */
    private suspend inline fun finish(act: () -> (SkillsUi) -> SkillsUi) {
        val shown: (SkillsUi) -> SkillsUi = try {
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
