package com.nousresearch.hermes.android.turn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Keeps the app alive while a turn runs, so switching away does not freeze a reply mid-stream. */
interface TurnHost {
    fun begin(turn: String)

    fun end(turn: String)
}

/** [TurnHost] as [TurnService], a dataSync foreground service from the first running turn to the last. */
class ForegroundTurnHost(private val context: Context) : TurnHost {
    override fun begin(turn: String) {
        if (TurnService.turns.begin(turn)) {
            ContextCompat.startForegroundService(context, Intent(context, TurnService::class.java))
        }
    }

    override fun end(turn: String) {
        if (TurnService.turns.end(turn)) {
            // A service not started yet sees no turns in onStartCommand and stops itself there.
            ContextCompat.getMainExecutor(context).execute { TurnService.running?.stopIfIdle() }
        }
    }
}
