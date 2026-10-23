package com.nousresearch.hermes.android.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the gateway is doing, as seen from the app: no `:gateway` process, one coming up, or one serving. */
enum class GatewayStatus { STOPPED, STARTING, RUNNING }

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
data class GatewayUi(val status: GatewayStatus, val startAtBoot: Boolean, val askBatteryExemption: Boolean = false)

/**
 * The always-on gateway's controls (D23). Its switch shows what is observed, not a stored wish: the gateway
 * can also be stopped from its notification, by its own failure, or by Android. Turning it on asks once
 * for the battery-optimization exemption (declinable), which keeps its network up while the phone dozes.
 * [state] is null while the screen is closed.
 */
class GatewayControl(
    private val settings: GatewaySettings,
    private val launcher: GatewayLauncher,
    private val observe: () -> GatewayStatus,
    private val batteryExempt: () -> Boolean,
) {
    private val screen = MutableStateFlow<GatewayUi?>(null)
    val state: StateFlow<GatewayUi?> = screen.asStateFlow()

    fun open() {
        screen.value = GatewayUi(observe(), settings.startAtBoot)
    }

    fun close() {
        screen.value = null
    }

    /** Re-reads what runs, for a screen that is showing. */
    fun refresh() {
        screen.update { it?.copy(status = observe()) }
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
