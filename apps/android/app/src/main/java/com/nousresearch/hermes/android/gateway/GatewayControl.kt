package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.bridge.contract.GatewayStateEvent
import java.util.concurrent.atomic.AtomicBoolean
import com.nousresearch.hermes.bridge.bridgeJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

/** What the gateway is doing, as seen from the app. */
enum class GatewayStatus { STOPPED, STARTING, RUNNING, RESTARTING, STOPPING, FAILED }

/**
 * The gateway's own account of itself, written by its service in the `:gateway` process on every change and
 * read by the app's controls (the core's `gateway_state.json` is the core's business, not the app's).
 */
@Serializable
data class GatewayRecord(val state: State, val pid: Int, val reason: String? = null) {
    @Serializable
    enum class State { STARTING, RUNNING, RESTARTING, STOPPED, FAILED }

    fun json(): String = bridgeJson.encodeToString(serializer(), this)

    companion object {
        fun parse(text: String): GatewayRecord? = runCatching { bridgeJson.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * The status (and, after a failure, its reason) from the gateway's [record] and the pid of the `:gateway` process
 * alive now: a record whose writer is gone says nothing about now (the process was killed; Android starts it
 * again, and the new one writes its own record), except a failure, which stays until the next start.
 */
fun statusOf(record: GatewayRecord?, alivePid: Int?): Pair<GatewayStatus, String?> = when {
    // The record is written before the process ends: while its writer lives, the gateway is still stopping.
    record != null && record.pid == alivePid &&
        record.state in setOf(GatewayRecord.State.STOPPED, GatewayRecord.State.FAILED) -> GatewayStatus.STOPPING to null
    record?.state == GatewayRecord.State.FAILED -> GatewayStatus.FAILED to record.reason
    record == null || record.pid != alivePid -> (if (alivePid != null) GatewayStatus.STARTING else GatewayStatus.STOPPED) to null
    else -> when (record.state) {
        GatewayRecord.State.STARTING -> GatewayStatus.STARTING
        GatewayRecord.State.RUNNING -> GatewayStatus.RUNNING
        GatewayRecord.State.RESTARTING -> GatewayStatus.RESTARTING
        GatewayRecord.State.STOPPED, GatewayRecord.State.FAILED -> GatewayStatus.STOPPED
    } to null
}

/** Starts and stops the `:gateway` process's service. */
interface GatewayLauncher {
    fun start()

    fun stop()
}

/** The one gateway setting kept by the app: start the gateway when the phone boots (off by default). */
interface GatewaySettings {
    var startAtBoot: Boolean
}

/** The gateway screen: what runs, the boot setting, and whether to ask for the battery exemption now. */
data class GatewayUi(val status: GatewayStatus, val startAtBoot: Boolean, val askBatteryExemption: Boolean = false,
                     val reason: String? = null)

/**
 * The always-on gateway's controls (D23). Its switch shows what is observed, not a stored wish: the gateway
 * can also be stopped from its notification, by its own failure, or by Android. Turning it on asks once
 * for the battery-optimization exemption (declinable), which keeps its network up while the phone dozes.
 * [state] is null while the screen is closed.
 */
class GatewayControl(
    private val settings: GatewaySettings,
    private val launcher: GatewayLauncher,
    private val observe: () -> Pair<GatewayStatus, String?>,
    private val batteryExempt: () -> Boolean,
) {
    private val screen = MutableStateFlow<GatewayUi?>(null)
    val state: StateFlow<GatewayUi?> = screen.asStateFlow()

    /** A restart asked for: the gateway starts again once the stopping one is observed gone; turning it off cancels it. */
    private val restartPending = AtomicBoolean(false)

    /** A restart still waits for the old gateway to end: someone must keep calling [refresh] until it is false. */
    val restarting: Boolean get() = restartPending.get()

    fun open() {
        val (status, reason) = observe()
        screen.value = GatewayUi(status, settings.startAtBoot, reason = reason)
    }

    fun close() {
        screen.value = null
    }

    /** Re-reads what runs, for a screen that is showing; finishes a restart once the old gateway has stopped. */
    fun refresh() {
        val (status, reason) = observe()
        // STOPPED and FAILED both mean the old process is gone (a live writer reads as STOPPING).
        if (status in setOf(GatewayStatus.STOPPED, GatewayStatus.FAILED) && restartPending.compareAndSet(true, false)) {
            launcher.start()
            screen.update { it?.copy(status = GatewayStatus.STARTING, reason = null) }
            return
        }
        // A start waits for the old gateway to end: until then the screen says it is restarting, not what stops.
        val shown = if (restartPending.get()) GatewayStatus.RESTARTING else status
        screen.update { it?.copy(status = shown, reason = reason.takeUnless { restartPending.get() }) }
    }

    /**
     * Restarts the gateway, which reads its bot token, allowlist and settings only when it starts: a running one
     * drains and stops first (its process ends), then [refresh] starts the new one; one that does not run starts.
     */
    fun restart() {
        if (observe().first in setOf(GatewayStatus.STOPPED, GatewayStatus.FAILED)) {
            turnOn()
            return
        }
        restartPending.set(true)
        launcher.stop()
        screen.update { it?.copy(status = GatewayStatus.RESTARTING) }
    }

    fun turnOn() {
        // The old gateway is still ending (its stop recorded, its process alive): a start now would land in it.
        if (observe().first == GatewayStatus.STOPPING) {
            restartPending.set(true)
            screen.update { it?.copy(status = GatewayStatus.RESTARTING, askBatteryExemption = !batteryExempt()) }
            return
        }
        launcher.start()
        screen.update { it?.copy(status = GatewayStatus.STARTING, askBatteryExemption = !batteryExempt()) }
    }

    /** A pending start waited too long for the old gateway to end: it is dropped, so no later refresh starts it. */
    fun abandonPendingStart() {
        restartPending.set(false)
        refresh()
    }

    fun turnOff() {
        restartPending.set(false)
        launcher.stop()
        refresh()
    }

    /** The battery dialog was shown (whatever the user chose there). */
    fun batteryAsked() {
        screen.update { it?.copy(askBatteryExemption = false) }
    }

    fun setStartAtBoot(on: Boolean) {
        settings.startAtBoot = on
        screen.update { it?.copy(startAtBoot = on) }
    }

    /** The phone booted: start the gateway if the user asked for it. */
    fun onBoot() {
        if (settings.startAtBoot) launcher.start()
    }
}

/**
 * One state change of the gateway host (`hermes_android.gateway_host`), as its JSON line reaches the service: the
 * bridge contract's `gateway_state` event, or null for a line that is no such event.
 */
fun gatewayStateEvent(line: String): GatewayStateEvent? =
    runCatching { bridgeJson.decodeFromString(GatewayStateEvent.serializer(), line) }.getOrNull()
