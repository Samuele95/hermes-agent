package com.nousresearch.hermes.bridge

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Where the core runs: the extracted runtime, HOME, HERMES_HOME and the temp directory. */
data class HermesPaths(val runtime: File, val home: File, val hermesHome: File, val tmp: File)

/** Receives the core's frames, on core threads. Hand each frame off and return: submitting from here deadlocks. */
fun interface FrameSink {
    fun onFrame(frame: String)
}

/**
 * The process's one embedded CPython (`libhermes_bridge.so` over python.org's `libpython3.14.so`).
 *
 * Python is entered from one bridge thread only, which is also its main thread: [start], [submit] and
 * [call] run there in order, and the UI thread never enters Python.
 */
object EmbeddedPython {
    private val bridge = Executors.newSingleThreadExecutor { Thread(it, "hermes-python") }

    @Volatile
    private var sink: FrameSink? = null

    /** Starts the interpreter and connects the core's transport; frames reach [sink] from now on. */
    fun start(paths: HermesPaths, sink: FrameSink) {
        check(this.sink == null) { "the embedded interpreter is already running" }
        this.sink = sink
        listOf(paths.home, paths.hermesHome, paths.tmp).forEach { it.mkdirs() }
        onBridge {
            System.loadLibrary("hermes_bridge")
            nativeStart(paths.runtime.path, paths.home.path, paths.hermesHome.path, paths.tmp.path)
        }
    }

    /** Sends one JSON-RPC frame to the core; the answer arrives as a frame. */
    fun submit(frame: String) {
        bridge.execute { nativeSubmit(frame.toByteArray(Charsets.UTF_8)) }
    }

    /** Calls `hermes_android.<function>(arg)` and returns its string result. */
    fun call(function: String, arg: String): String =
        onBridge { String(nativeCall(function, arg.toByteArray(Charsets.UTF_8)), Charsets.UTF_8) }

    private fun <T> onBridge(block: () -> T): T =
        try {
            bridge.submit(Callable(block)).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    @JvmStatic
    private fun onFrame(frame: ByteArray): Boolean {
        val sink = sink ?: return false
        sink.onFrame(String(frame, Charsets.UTF_8))
        return true
    }

    @JvmStatic
    private external fun nativeStart(runtime: String, home: String, hermesHome: String, tmp: String)

    @JvmStatic
    private external fun nativeSubmit(frame: ByteArray)

    @JvmStatic
    private external fun nativeCall(function: String, arg: ByteArray): ByteArray
}
