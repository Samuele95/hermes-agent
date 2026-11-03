package com.nousresearch.hermes.bridge

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge's two threads into Python: a network-bound call never queues the core's frames or a quick call. */
class BridgeThreadsTest {
    private val core: ExecutorService = Executors.newSingleThreadExecutor()
    private val network: ExecutorService = Executors.newSingleThreadExecutor()
    private val threads = BridgeThreads(core, network)
    private val release = CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        core.shutdownNow()
        network.shutdownNow()
    }

    /** Starts a blocking call from its own thread and returns once it is inside its block, held on [release]. */
    private fun holdABlockingCall(on: BridgeThreads = threads): Thread {
        val entered = CountDownLatch(1)
        val caller = thread { on.run(blocking = true) { entered.countDown(); release.await(10, TimeUnit.SECONDS) } }
        assertTrue("the blocking call never started", entered.await(5, TimeUnit.SECONDS))
        return caller
    }

    @Test
    fun aBlockingCallDoesNotHoldAFrame() {
        holdABlockingCall()
        val framed = CountDownLatch(1)

        threads.post { framed.countDown() }

        assertTrue("a frame waited behind a blocking call", framed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun aBlockingCallDoesNotHoldAQuickCall() {
        holdABlockingCall()
        val answered = CountDownLatch(1)

        thread { if (threads.run(blocking = false) { "pong" } == "pong") answered.countDown() }

        assertTrue("a quick call waited behind a blocking call", answered.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun framesAndQuickCallsKeepTheirOrder() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val frameEntered = CountDownLatch(1)
        threads.post { frameEntered.countDown(); release.await(10, TimeUnit.SECONDS); order += "frame" }
        assertTrue(frameEntered.await(5, TimeUnit.SECONDS))

        val caller = thread { threads.run(blocking = false) { order += "call" } }
        caller.join(200)
        assertFalse("a quick call overtook a frame still running", "call" in order)
        release.countDown()
        caller.join(5_000)

        assertEquals(listOf("frame", "call"), order.toList())
    }

    @Test
    fun eachKindRunsOnItsOwnThread() {
        val named = BridgeThreads()
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val framed = CountDownLatch(1)

        seen += "blocking:" + named.run(blocking = true) { Thread.currentThread().name }
        seen += "quick:" + named.run(blocking = false) { Thread.currentThread().name }
        named.post { seen += "frame:" + Thread.currentThread().name; framed.countDown() }
        assertTrue(framed.await(5, TimeUnit.SECONDS))

        assertEquals(listOf("blocking:hermes-python-net", "quick:hermes-python", "frame:hermes-python"), seen.toList())
    }

    @Test
    fun aFailingCallThrowsItsOwnException() {
        for (blocking in listOf(false, true)) {
            val thrown = runCatching { threads.run<Unit>(blocking) { throw IllegalStateException("refused $blocking") } }
                .exceptionOrNull()

            assertTrue("blocking=$blocking: $thrown", thrown is IllegalStateException)
            assertEquals("refused $blocking", thrown!!.message)
        }
    }
}
