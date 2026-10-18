package com.nousresearch.hermes.android.turn

/** The turns running now. [begin] says when the first one starts, [end] when the last one ends. */
class RunningTurns {
    private val turns = mutableSetOf<String>()

    val isIdle: Boolean
        @Synchronized get() = turns.isEmpty()

    /** True when [turn] is the only one running: the turn service must start. */
    @Synchronized
    fun begin(turn: String): Boolean = turns.isEmpty().also { turns += turn }

    /** True when [turn] was the last one running: the turn service may stop. */
    @Synchronized
    fun end(turn: String): Boolean = turns.remove(turn) && turns.isEmpty()
}
