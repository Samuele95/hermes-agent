package com.nousresearch.hermes.android.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nousresearch.hermes.android.AssetPayload
import com.nousresearch.hermes.android.HermesApplication
import com.nousresearch.hermes.android.MainActivity
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.bridge.EmbeddedPython
import com.nousresearch.hermes.bridge.HermesPaths
import com.nousresearch.hermes.bridge.RuntimeInstaller
import java.io.File
import java.util.concurrent.Executors

/**
 * The optional always-on messaging gateway (D12, D23), in its own `:gateway` process: its own interpreter
 * over the app's home and keystore, the gateway on a thread of it (`hermes_android.gateway_host`). A
 * specialUse foreground service keeps the process up; it is sticky, so a process the gateway's own
 * watchdogs end (or Android kills) comes back with a fresh interpreter. A planned stop drains the gateway
 * and then ends the process, so the next start gets a fresh interpreter too.
 */
class GatewayService : Service() {
    companion object {
        const val NOTIFICATION_ID = 2
        private const val FAILURE_ID = 3
        private const val CHANNEL = "gateway"
        private const val ACTION_STOP = "com.nousresearch.hermes.gateway.STOP"

        fun start(context: Context): Intent = Intent(context, GatewayService::class.java)

        fun stop(context: Context): Intent = Intent(context, GatewayService::class.java).setAction(ACTION_STOP)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gateway-control") }
    private var booted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (booted) worker.execute { EmbeddedPython.call("gateway_stop", "") } else end()
            return START_STICKY
        }
        // A sticky restart comes back with no intent: it starts the gateway again like the first start.
        // specialUse exists from Android 14; earlier versions need no type.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(R.string.gateway_starting), type)
        if (!booted) {
            booted = true
            worker.execute(::boot)
        }
        return START_STICKY
    }

    private fun boot() {
        try {
            val runtime = RuntimeInstaller(File(noBackupFilesDir, "runtime")).install(AssetPayload(assets))
            val paths = HermesPaths(runtime, filesDir, File(filesDir, ".hermes"), cacheDir)
            EmbeddedPython.startGateway(paths, (application as HermesApplication).secrets) { line -> main.post { onEvent(line) } }
            EmbeddedPython.call("gateway_start", "")
        } catch (e: Exception) {
            main.post { failed(e.toString()) }
        }
    }

    /** One state change of the gateway host: `running`, `restarting`, `stopped` or `failed`. */
    private fun onEvent(line: String) {
        val event = GatewayEvent.parse(line) ?: return
        when (event.gateway) {
            GatewayEvent.State.RUNNING -> show(R.string.gateway_running)
            GatewayEvent.State.RESTARTING -> show(R.string.gateway_restarting)
            GatewayEvent.State.STOPPED -> end()
            GatewayEvent.State.FAILED -> failed(event.reason.orEmpty())
        }
    }

    private fun failed(reason: String) {
        getSystemService(NotificationManager::class.java).notify(FAILURE_ID, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.gateway_failed))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(openApp())
            .build())
        end()
    }

    /** Stops the service, then the process: its interpreter cannot be started twice. */
    private fun end() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private fun show(text: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: Int): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.gateway_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 0, stop(this), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.gateway_title))
            .setContentText(getString(text))
            .setOngoing(true)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.gateway_stop), stop)
            .build()
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
