package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.bridge.bridgeJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the gateway is doing, as seen from the app. */
enum class GatewayStatus { STOPPED, STARTING, RUNNING, RESTARTING, FAILED }

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

    /** A restart asked for: the gateway starts again once the stopping one is observed gone. */
    @Volatile
    private var restartPending = false

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
        if (restartPending && status in setOf(GatewayStatus.STOPPED, GatewayStatus.FAILED)) {
            restartPending = false
            launcher.start()
            screen.update { it?.copy(status = GatewayStatus.STARTING, reason = null) }
            return
        }
        screen.update { it?.copy(status = status, reason = reason) }
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
        restartPending = true
        launcher.stop()
        screen.update { it?.copy(status = GatewayStatus.RESTARTING) }
    }

    fun turnOn() {
        launcher.start()
        screen.update { it?.copy(status = GatewayStatus.STARTING, askBatteryExemption = !batteryExempt()) }
    }

    fun turnOff() {
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

/** One state change of the gateway host (`hermes_android.gateway_host`), as its JSON line reaches the service. */
@Serializable
data class GatewayEvent(val gateway: State, val reason: String? = null) {
    @Serializable
    enum class State {
        @SerialName("running") RUNNING,
        @SerialName("restarting") RESTARTING,
        @SerialName("stopped") STOPPED,
        @SerialName("failed") FAILED,
    }

    companion object {
        /** The event [line] reports, or null for a line that is no known state. */
        fun parse(line: String): GatewayEvent? = runCatching { bridgeJson.decodeFromString<GatewayEvent>(line) }.getOrNull()
    }
}
