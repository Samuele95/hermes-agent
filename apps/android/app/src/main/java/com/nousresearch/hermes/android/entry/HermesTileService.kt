package com.nousresearch.hermes.android.entry

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nousresearch.hermes.android.MainActivity

/**
 * The "Ask Hermes" quick-settings tile: a tap opens the app on a new chat ([NEW_CHAT]), after the unlock when the phone
 * is locked. A thin platform entry that only hands off, never a path into the core (§3.1): binding it starts no core.
 * The system holds `BIND_QUICK_SETTINGS_TILE`; the app requests nothing (DC-M8).
 */
class HermesTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE  // an action, not a toggle
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::openNewChat) else openNewChat()
    }

    private fun openNewChat() {
        val intent = Intent(NEW_CHAT).setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            // The Intent overload throws on API 34+ for an app that targets 34+; below 34 it is the only one.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
