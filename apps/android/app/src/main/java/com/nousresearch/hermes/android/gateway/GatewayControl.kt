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
enum class GatewayStatus {
    STOPPED, STARTING, RUNNING, RESTARTING, STOPPING, FAILED,

    /** Its process ended without a recorded stop or failure (killed, or ended by its own watchdog) and nothing restarted it. */
    DIED,
}

/**
 * The gateway's own account of itself, written by its service in the `:gateway` process on every change and
 * read by the app's controls (the core's `gateway_state.json` is the core's business, not the app's). [boot] is
 * the phone's boot count when it was written (null in a record from an older build).
 */
@Serializable
data class GatewayRecord(val state: State, val pid: Int, val reason: String? = null, val boot: Int? = null) {
    @Serializable
    enum class State { STARTING, RUNNING, RESTARTING, STOPPED, FAILED }

    fun json(): String = bridgeJson.encodeToString(serializer(), this)

    companion object {
        fun parse(text: String): GatewayRecord? = runCatching { bridgeJson.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** The states a gateway records while it runs: one of them with no live writer means the process died. */
private val LIVE = setOf(GatewayRecord.State.STARTING, GatewayRecord.State.RUNNING, GatewayRecord.State.RESTARTING)

/** A gone gateway's record was ended on purpose: the user's Force stop, or the phone restarted since it was written. */
fun endedOnPurpose(record: GatewayRecord, bootNow: Int?, userRequested: Boolean): Boolean =
    userRequested || (record.boot != null && bootNow != null && record.boot != bootNow)

/**
 * The status (and, after a failure, its reason) from the gateway's [record] and the pid of the `:gateway` process
 * alive now. A failure stays until the next start. A record that says the gateway runs while no process lives means
 * it died without a planned stop ([GatewayStatus.DIED]: killed, or ended by its own watchdog; Samsung can keep
 * Android from restarting it, L21), unless [plannedEnd] says Android ended it on the user's behalf (Force stop) or
 * the phone restarted since, which reads as stopped. A new process that has not written its own record is starting.
 */
fun statusOf(record: GatewayRecord?, alivePid: Int?, plannedEnd: (GatewayRecord) -> Boolean = { false }): Pair<GatewayStatus, String?> = when {
    // The record is written before the process ends: while its writer lives, the gateway is still stopping.
    record != null && record.pid == alivePid &&
        record.state in setOf(GatewayRecord.State.STOPPED, GatewayRecord.State.FAILED) -> GatewayStatus.STOPPING to null
    record?.state == GatewayRecord.State.FAILED -> GatewayStatus.FAILED to record.reason
    // Its writer is gone without recording a stop or a failure: killed, or ended by a watchdog (D38, L21).
    record != null && alivePid == null && record.state in LIVE ->
        (if (plannedEnd(record)) GatewayStatus.STOPPED else GatewayStatus.DIED) to null
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

    /** A died gateway was started again and its process is not up yet; turning it off or giving up clears it. */
    private val reviving = AtomicBoolean(false)

    /**
     * A start asked for is not yet seen through (a restart waiting for the old gateway to end, or a revived gateway
     * whose process is not up yet): someone must keep calling [refresh] until it is false.
     */
    val restarting: Boolean get() = restartPending.get() || reviving.get()

    fun open() {
        val (status, reason) = observe()
        screen.value = GatewayUi(status, settings.startAtBoot, reason = reason)
        if (status == GatewayStatus.DIED) revive()
    }

    /**
     * Starts again a gateway that died without a planned stop and that nothing brought back (D38: Samsung can hold
     * the sticky restart, L21). Called when the app comes on screen and when this screen opens; any other status
     * is left alone. Returns whether a start was sent; one the system refuses leaves the gateway shown as died.
     */
    fun revive(): Boolean {
        if (observe().first != GatewayStatus.DIED) return false
        try {
            launcher.start()
        } catch (_: IllegalStateException) {  // incl. ForegroundServiceStartNotAllowedException
            return false
        } catch (_: SecurityException) {
            return false
        }
        reviving.set(true)
        screen.update { it?.copy(status = GatewayStatus.STARTING, reason = null) }
        return true
    }

    fun close() {
        screen.value = null
    }

    /** Re-reads what runs, for a screen that is showing; finishes a restart once the old gateway has stopped. */
    fun refresh() {
        val (status, reason) = observe()
        // A revived gateway's process is up (or ended again on its own): from now on the screen shows what is.
        if (status != GatewayStatus.DIED) reviving.set(false)
        // STOPPED, FAILED and DIED all mean the old process is gone (a live writer reads as STOPPING).
        if (status in ENDED && restartPending.compareAndSet(true, false)) {
            launcher.start()
            screen.update { it?.copy(status = GatewayStatus.STARTING, reason = null) }
            return
        }
        // A start waits for the old gateway to end (or for the revived one to come up): until then the screen says so.
        val shown = when {
            restartPending.get() -> GatewayStatus.RESTARTING
            reviving.get() -> GatewayStatus.STARTING
            else -> status
        }
        screen.update { it?.copy(status = shown, reason = reason.takeUnless { restartPending.get() || reviving.get() }) }
    }

    /**
     * Restarts the gateway, which reads its bot token, allowlist and settings only when it starts: a running one
     * drains and stops first (its process ends), then [refresh] starts the new one; one that does not run starts.
     */
    fun restart() {
        if (observe().first in ENDED) {
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
        reviving.set(false)
        refresh()
    }

    fun turnOff() {
        restartPending.set(false)
        reviving.set(false)
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

    private companion object {
        /** The gateway's process is gone: a pending start may go ahead. */
        val ENDED = setOf(GatewayStatus.STOPPED, GatewayStatus.FAILED, GatewayStatus.DIED)
    }
}

/**
 * One state change of the gateway host (`hermes_android.gateway_host`), as its JSON line reaches the service: the
 * bridge contract's `gateway_state` event, or null for a line that is no such event.
 */
fun gatewayStateEvent(line: String): GatewayStateEvent? =
    runCatching { bridgeJson.decodeFromString(GatewayStateEvent.serializer(), line) }.getOrNull()
