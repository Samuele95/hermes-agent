package com.nousresearch.hermes.android.turn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nousresearch.hermes.android.MainActivity
import com.nousresearch.hermes.android.R

/** The foreground service a running turn keeps up (type dataSync: a reply is a transfer in progress). */
class TurnService : Service() {
    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL = "turns"
        val turns = RunningTurns()

        @Volatile
        var running: TurnService? = null
            private set
    }

    @Volatile
    var inForeground = false
        private set
    private var lastStart = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground first, every time, even if the turn already ended: Android versions differ on a
        // service started with startForegroundService that stops before calling startForeground
        // (some crash the app; Android 16 tolerates it), and this order is safe on all of them.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        inForeground = true
        lastStart = startId
        stopIfIdle()
        return START_NOT_STICKY
    }

    /** Stops when no turn runs; a turn begun meanwhile sent a newer start, which keeps the service. */
    fun stopIfIdle() {
        if (turns.isIdle && stopSelfResult(lastStart)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
    }

    // Android 15 caps dataSync at 6 h a day. The turn itself goes on in-process; only the service ends.
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        stopSelf()
    }

    override fun onDestroy() {
        running = null
        inForeground = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.turn_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.turn_running))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}
