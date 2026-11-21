package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.CorrectionStatus

/**
 * What Send does while a turn runs, as the core's `display.busy_input_mode` says (`config.get busy`, read once per
 * turn): [INTERRUPT] redirects the running turn (`session.redirect`), [STEER] steers it (`session.steer`), [QUEUE]
 * holds the words for the next turn. Only Stop interrupts, whatever the mode (batch-3 F1, EJ-34).
 */
enum class BusyMode {
    INTERRUPT, STEER, QUEUE;

    companion object {
        /**
         * The mode the core's word names. The core answers it normalised (`tui_gateway/server.py:382-384`), so the
         * words are compared exactly; none, or one it does not know, is [INTERRUPT], the core's own fallback.
         */
        fun of(word: String?): BusyMode = when (word) {
            "steer" -> STEER
            "queue" -> QUEUE
            else -> INTERRUPT
        }
    }
}

/**
 * The outcome of one busy send, as the core answered it (`SessionCorrectionResult.status`): [REDIRECTED] the turn
 * resumes with the words, [STEERED] they reach the model with the next tool result, [QUEUED_BY_CORE] the core runs
 * them next, [REFUSED] the turn ended meanwhile, an ordinary answer and no error (EJ-69).
 */
enum class Correction {
    REDIRECTED, STEERED, QUEUED_BY_CORE, REFUSED;

    companion object {
        /**
         * The core's [status] read by the [mode] the words went out in, mapped once (§5): `queued` from a steer is
         * the words injected; from a redirect, queued for the next turn while the agent builds or compresses
         * (`methods_session_interrupt.py:147-159`). A redirect that degraded to a steer still answers `redirected`
         * (`agent/interrupt_control.py:280-290`).
         */
        fun of(mode: BusyMode, status: CorrectionStatus): Correction = when (status) {
            CorrectionStatus.REJECTED -> REFUSED
            CorrectionStatus.REDIRECTED -> REDIRECTED
            CorrectionStatus.QUEUED -> if (mode == BusyMode.STEER) STEERED else QUEUED_BY_CORE
        }
    }
}

/**
 * A message the user queued for a later turn: it goes to the core only when it is sent. [id] is the queue's own, given
 * once and kept through an edit, so a row is keyed by it as a transcript row is by `Entry.stableId`.
 */
data class Queued(val id: Long, val text: String)

/**
 * The chat's queue of unsent messages, owned by `ChatSession` (batch-3 F2): [items] in the order they go out, one per
 * finished turn; [paused] after Stop or a turn that did not complete, until Resume; [editing] the item being edited,
 * which waits. A queue with no items is never paused (D5): every step below keeps that.
 */
data class QueueUi(val items: List<Queued> = emptyList(), val paused: Boolean = false, val editing: Long? = null)

/** This queue as every step leaves it: an empty one is not paused (D5). */
private fun QueueUi.kept(): QueueUi = if (paused && items.isEmpty()) copy(paused = false) else this

/** [text] at the tail, as item [id]. */
fun QueueUi.added(text: String, id: Long): QueueUi = copy(items = items + Queued(id, text)).kept()

/**
 * The item that goes out next and the queue without it; null while paused, or when nothing but the edited item is
 * left. The edited item is skipped, never lost (`use-composer-queue.ts:379-386`).
 */
fun QueueUi.next(): Pair<Queued, QueueUi>? {
    if (paused) return null
    val head = items.firstOrNull { it.id != editing } ?: return null
    return head to copy(items = items.filterNot { it.id == head.id }).kept()
}

/** Item [id] at the front; one not listed leaves the queue as it is (inv. 4). */
fun QueueUi.moved(id: Long): QueueUi {
    val item = items.firstOrNull { it.id == id } ?: return this
    return copy(items = listOf(item) + items.filterNot { it.id == id }).kept()
}

/** Item [id]'s words replaced by [text]; its edit ends. One not listed keeps the items and ends the edit only if it was that one. */
fun QueueUi.edited(id: Long, text: String): QueueUi =
    copy(items = items.map { if (it.id == id) it.copy(text = text) else it }, editing = editing.takeIf { it != id }).kept()

/** Item [id] gone, its edit ended if it was being edited; one not listed leaves the queue as it is (inv. 4). */
fun QueueUi.removed(id: Long): QueueUi =
    if (items.none { it.id == id }) this else copy(items = items.filterNot { it.id == id }, editing = editing.takeIf { it != id }).kept()

/**
 * The queue as loaded for a session: no edit, and paused when it has items and the session is not [inTurn]: the turn
 * it waited for did not end in this chat, so nothing runs unseen (D6, DC-M6).
 */
fun QueueUi.resumed(inTurn: Boolean): QueueUi = copy(paused = paused || (items.isNotEmpty() && !inTurn), editing = null).kept()
