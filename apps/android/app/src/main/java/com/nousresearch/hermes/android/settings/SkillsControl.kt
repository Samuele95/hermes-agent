package com.nousresearch.hermes.android.settings

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.AppBridge
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.ask
import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.SkillsEnableParams
import com.nousresearch.hermes.bridge.contract.SkillsRestoreParams
import com.nousresearch.hermes.bridge.protocol.LearningEditParams
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
 * core's last answer, a skill's text included (`learning.detail`, F10). Edit and archive are offered only when the
 * skill's [Provenance.editable] (DC-P19); an `ok: false` answer keeps the draft and shows the core's words (EJ-69).
 * Every accepted write reloads for the open chat (D7).
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

    /** Opens the editor on the open skill's text as the core holds it, for an editable skill on an idle page (DC-P19). */
    fun edit() {
        screen.update { ui ->
            val open = ui?.open
            if (ui == null || ui.busy || open == null || open.editing != null || !ui.editable(open.name)) ui
            else ui.copy(open = open.copy(editing = open.content), error = null)
        }
    }

    /** The editor's [text], while it is open and nothing is being saved. */
    fun draft(text: String) {
        screen.update { ui ->
            val open = ui?.open
            if (ui == null || ui.busy || open?.editing == null) ui else ui.copy(open = open.copy(editing = text))
        }
    }

    /** Closes the editor and drops its draft; nothing while a change runs (D3). */
    fun cancelEdit() {
        screen.update { ui -> if (ui == null || ui.busy) ui else ui.copy(open = ui.open?.copy(editing = null), error = null) }
    }

    /**
     * Saves the draft through `learning.edit` (the core validates, scans and writes it atomically). Saved: the skill shows
     * the text sent, which the core now holds (D6), says so, and the page reloads (D7). A refusal (`ok: false`) or a
     * failure keeps the editor open with the draft as sent and the core's words (EJ-69), and reloads nothing.
     */
    suspend fun save() {
        val open = admit { ui -> ui.open?.let { it.editing != null && ui.editable(it.name) } == true }?.open ?: return
        val sent = open.editing ?: return
        finish {
            val answer = core.call(Methods.LEARNING_EDIT, LearningEditParams(id = open.name, content = sent))
            if (!answer.ok) {
                val words = answer.message ?: "The skill could not be saved."
                return@finish { it.copy(error = words) }
            }
            then({ it.copy(open = SkillText(open.name, sent), notice = SkillsNotice(SkillsNotice.Kind.UPDATED, open.name)) }) {
                val rows = reloaded()
                ({ it.copy(rows = rows) })
            }
        }
    }

    /**
     * Asks to archive [name] (null stops asking): only the open, listed, editable skill on an idle page is asked for;
     * anything else asks nothing. Nothing is sent.
     */
    fun askArchive(name: String?) {
        screen.update { ui ->
            if (ui == null || ui.busy) ui
            else ui.copy(confirmArchive = name?.takeIf { ui.open?.name == it && ui.editable(it) }, error = null)
        }
    }

    /**
     * Archives the skill [askArchive] named through `learning.delete` (into `skills/.archive/`), taken in one atomic
     * step with the admission so a double tap archives once. Archived: the skill closes, the page says so and reloads
     * (D7). A refusal (pinned, external, the core's own rule) keeps the skill open with the core's words.
     */
    suspend fun confirmArchive() {
        val before = screen.getAndUpdate { ui -> ui?.takeIf { it.confirmArchive != null }?.writing()?.copy(confirmArchive = null) ?: ui }
        val name = before?.takeIf { it.writing() != null }?.confirmArchive ?: return
        finish {
            val answer = core.call(Methods.LEARNING_DELETE, LearningNodeParams(id = name))
            if (!answer.ok) {
                val words = answer.message ?: "The skill could not be archived."
                return@finish { it.copy(error = words) }
            }
            then({ it.copy(open = null, notice = SkillsNotice(SkillsNotice.Kind.ARCHIVED, name)) }) {
                val rows = reloaded()
                ({ it.copy(rows = rows) })
            }
        }
    }

    /** Opens the Archived view on the core's archive (`skills_archived`; DC-P20: the phone's `hermes curator restore`). */
    suspend fun showArchived() {
        if (!admitted()) return
        finish {
            val names = bridge.ask(BridgeCalls.SKILLS_ARCHIVED).names
            ({ it.copy(archived = names) })
        }
    }

    /** Closes the Archived view; nothing while a change runs (D3). */
    fun hideArchived() {
        screen.update { if (it == null || it.busy) it else it.copy(archived = null) }
    }

    /**
     * Restores the archived skill [name] (`skills_restore`, as the user): the page shows the core's list, says so,
     * reloads for the open chat (D7) and re-reads the archive. A refusal (`{error}`) keeps the archive and the rows,
     * with the core's words. A name the archive does not list sends nothing.
     */
    suspend fun restore(name: String) {
        if (!admitted { name in it.archived.orEmpty() }) return
        finish {
            val rows = bridge.ask(BridgeCalls.SKILLS_RESTORE, SkillsRestoreParams(name)).skills.map(SkillRow::of)
            then({ it.copy(rows = rows, notice = SkillsNotice(SkillsNotice.Kind.RESTORED, name)) }) {
                reload()
                val names = bridge.ask(BridgeCalls.SKILLS_ARCHIVED).names
                ({ it.copy(archived = names) })
            }
        }
    }

    /** Whether the listed skill [name] may be edited or archived: its [Provenance.editable], the one rule (EJ-34). */
    private fun SkillsUi.editable(name: String): Boolean = rows.orEmpty().any { it.name == name && it.provenance.editable }

    /** The core rescans its skills for the open chat (`skills.reload`, DC-P23): the chat's `/` menu follows at once. */
    private suspend fun reload() {
        core.call(Methods.SKILLS_RELOAD, SkillsReloadParams(sessionId = session()))
    }

    /** The core rescans its skills for the open chat (`skills.reload`), then lists them (`skills_list`). */
    private suspend fun reloaded(): List<SkillRow> {
        reload()
        return bridge.ask(BridgeCalls.SKILLS_LIST).skills.map(SkillRow::of)
    }

    /**
     * A write the core accepted, shown by [written], then [follow] (the reload and re-read, D7): a failure of the
     * follow-up shows its words, and the write's result stays.
     */
    private suspend inline fun then(noinline written: (SkillsUi) -> SkillsUi,
                                    follow: () -> (SkillsUi) -> SkillsUi): (SkillsUi) -> SkillsUi {
        val after: (SkillsUi) -> SkillsUi = try {
            follow()
        } catch (cancelled: CancellationException) {
            screen.update { it?.let(written)?.copy(busy = false) }
            throw cancelled
        } catch (failed: Exception) {
            val words = forUser(failed)
            ({ it.copy(error = words) })
        }
        return { after(written(it)) }
    }

    /** Admits one change into an open, idle page that is [allowed] in one atomic step. */
    private fun admitted(allowed: (SkillsUi) -> Boolean = { true }): Boolean = admit(allowed) != null

    /** [admitted], answering the page as it was when admitted (null: not admitted). */
    private fun admit(allowed: (SkillsUi) -> Boolean): SkillsUi? =
        screen.getAndUpdate { ui -> ui?.takeIf(allowed)?.writing() ?: ui }?.takeIf { allowed(it) && it.writing() != null }

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
