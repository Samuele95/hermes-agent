package com.nousresearch.hermes.bridge

import com.nousresearch.hermes.bridge.contract.BridgeCalls
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The bridge's two threads into Python. [post] (the core's frames) and non-blocking calls share
 * `hermes-python`, the interpreter's main thread, in submission order. Blocking calls (the contract's
 * `blocking`, network-bound) run on `hermes-python-net`, so a slow probe never queues a frame. They are
 * not ordered with frames or with other calls. Each entry takes the GIL itself (hermes_bridge.c).
 * [call] routes by name ([BridgeCalls.BLOCKING]); [onCore] is for the interpreter's start.
 */
internal class BridgeThreads(
    private val core: ExecutorService = single("hermes-python"),
    private val network: ExecutorService = single("hermes-python-net"),
) {
    /** Runs [block] on the core's thread after everything posted or run there before it; returns at once. */
    fun post(block: () -> Unit) {
        core.execute(block)
    }

    /**
     * Makes the bridge call [function]: on the network thread if the contract marks it blocking
     * ([BridgeCalls.BLOCKING]), else on the core's thread in order. Returns its result.
     */
    fun <T> call(function: String, block: () -> T): T = run(function in BridgeCalls.BLOCKING, block)

    /** Runs [block] on the core's thread after everything posted or run there before it, and returns its result. */
    fun <T> onCore(block: () -> T): T = run(blocking = false, block)

    /** Runs [block] on the network thread if [blocking], else on the core's thread in order, and returns its result. */
    private fun <T> run(blocking: Boolean, block: () -> T): T =
        try {
            (if (blocking) network else core).submit(Callable(block)).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }

    private companion object {
        /** A single thread named [name], started only by its first task. */
        fun single(name: String): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, name) }
    }
}
