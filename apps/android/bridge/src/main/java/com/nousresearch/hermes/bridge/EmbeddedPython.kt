package com.nousresearch.hermes.bridge

import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.protocol.ClientCapabilitiesParams
import com.nousresearch.hermes.bridge.protocol.Methods
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the core runs: the extracted runtime, HOME, HERMES_HOME and the temp directory. */
data class HermesPaths(val runtime: File, val home: File, val hermesHome: File, val tmp: File)

/** Receives the core's frames, on core threads. Hand each frame off and return: submitting from here deadlocks. */
fun interface FrameSink {
    fun onFrame(frame: String)
}

/**
 * The process's one embedded CPython (`libhermes_bridge.so` over python.org's `libpython3.14.so`).
 *
 * [start], the core's frames ([submit]) and non-blocking [call]s run in order on `hermes-python`, the
 * interpreter's main thread. Blocking calls (`blocking` in the contract: they may wait on the network) run on
 * `hermes-python-net`, so they never queue a frame ([BridgeThreads]). The UI thread never enters Python.
 */
object EmbeddedPython : CoreRuntime {
    private val threads = BridgeThreads()

    @Volatile
    private var sink: FrameSink? = null

    @Volatile
    private var secrets: SecretStore? = null

    /**
     * Starts the interpreter; frames reach [sink] from now on. The `chat` role connects the core's
     * transport; the `gateway` role (the app's `:gateway` process) prepares the messaging gateway instead.
     */
    fun start(paths: HermesPaths, sink: FrameSink, role: String = "chat") {
        check(this.sink == null) { "the embedded interpreter is already running" }
        this.sink = sink
        listOf(paths.home, paths.hermesHome, paths.tmp).forEach { it.mkdirs() }
        threads.run(blocking = false) {
            System.loadLibrary("hermes_bridge")
            nativeStart(paths.runtime.path, paths.home.path, paths.hermesHome.path, paths.tmp.path, role)
        }
    }

    /**
     * Starts the core and returns its gateway, having told the core that this app answers in-turn prompts.
     * The core reads [secrets] as environment variables, through its `android_keystore` secret source.
     */
    override suspend fun connect(paths: HermesPaths, secrets: SecretStore): CoreGateway {
        this.secrets = secrets
        val gateway = FrameCoreGateway(::submit)
        withContext(Dispatchers.IO) { start(paths, gateway) }
        gateway.call(Methods.CLIENT_CAPABILITIES, ClientCapabilitiesParams(serverRequests = true))
        return gateway
    }

    /**
     * Starts the interpreter as this process's gateway host (`hermes_android.gateway_host`), reading [secrets]
     * like the chat's; its state changes reach [events] as JSON lines. [call] `gateway_start` to run it.
     */
    override fun startGateway(paths: HermesPaths, secrets: SecretStore, events: FrameSink) {
        this.secrets = secrets
        start(paths, events, role = "gateway")
    }

    override val bridge: AppBridge get() = EmbeddedBridge

    /** Sends one JSON-RPC frame to the core; the answer arrives as a frame. */
    fun submit(frame: String) {
        threads.post { nativeSubmit(frame.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Makes the bridge call [function] (declared in `hermes_android/bridge_contract.json`, D26) and returns its
     * reply; a call in [BridgeCalls.BLOCKING] runs on the network thread, apart from the core's frames.
     */
    fun call(function: String, arg: String): String =
        threads.run(function in BridgeCalls.BLOCKING) { String(nativeCall(function, arg.toByteArray(Charsets.UTF_8)), Charsets.UTF_8) }

    @JvmStatic
    private fun onFrame(frame: ByteArray): Boolean {
        val sink = sink ?: return false
        sink.onFrame(String(frame, Charsets.UTF_8))
        return true
    }

    /** The keystore's secrets as UTF-8 JSON, for the core's secret source; the caller wipes the bytes. */
    @JvmStatic
    private fun readSecrets(): ByteArray = SecretsJson.encode(secrets?.all().orEmpty())

    @JvmStatic
    private external fun nativeStart(runtime: String, home: String, hermesHome: String, tmp: String, role: String)

    @JvmStatic
    private external fun nativeSubmit(frame: ByteArray)

    @JvmStatic
    private external fun nativeCall(function: String, arg: ByteArray): ByteArray
}
