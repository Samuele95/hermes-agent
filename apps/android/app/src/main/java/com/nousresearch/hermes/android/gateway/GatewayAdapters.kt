package com.nousresearch.hermes.android.gateway

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.nousresearch.hermes.android.HermesApplication
import java.io.File
import org.json.JSONObject

/** Starts and stops [GatewayService]; a stop is sent only to a running gateway (it would start the process to end it). */
class ServiceGatewayLauncher(private val context: Context) : GatewayLauncher {
    override fun start() {
        ContextCompat.startForegroundService(context, GatewayService.start(context))
    }

    override fun stop() {
        if (gatewayPid(context) != null) context.startService(GatewayService.stop(context))
    }
}

class PrefsGatewaySettings(private val prefs: SharedPreferences) : GatewaySettings {
    override var startAtBoot: Boolean
        get() = prefs.getBoolean("gateway_start_at_boot", false)
        set(value) = prefs.edit().putBoolean("gateway_start_at_boot", value).apply()
}

private fun gatewayPid(context: Context): Int? =
    context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == "${context.packageName}:gateway" }?.pid

/** What runs: the `:gateway` process, and whether the gateway's own record says it serves from it. */
fun observeGateway(context: Context): GatewayStatus {
    val pid = gatewayPid(context) ?: return GatewayStatus.STOPPED
    val record = runCatching { JSONObject(File(context.filesDir, ".hermes/gateway_state.json").readText()) }.getOrNull()
    val serving = record?.optString("gateway_state") == "running" && record.optInt("pid") == pid
    return if (serving) GatewayStatus.RUNNING else GatewayStatus.STARTING
}

fun batteryExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** Starts the gateway after a reboot when the user turned on "start at boot" (specialUse may start from here). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        (context.applicationContext as HermesApplication).gateway.onBoot()
    }
}
