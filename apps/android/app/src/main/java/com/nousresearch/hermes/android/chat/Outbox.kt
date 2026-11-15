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
