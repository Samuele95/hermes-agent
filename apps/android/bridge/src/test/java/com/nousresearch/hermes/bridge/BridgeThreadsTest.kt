package com.nousresearch.hermes.bridge

import com.nousresearch.hermes.bridge.contract.BridgeCalls
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

    /** Starts the blocking call [function] from its own thread and returns once it is inside its block, held on [release]. */
    private fun holdABlockingCall(function: String = "probe_endpoint", on: BridgeThreads = threads): Thread {
        val entered = CountDownLatch(1)
        val caller = thread { on.call(function) { entered.countDown(); release.await(10, TimeUnit.SECONDS) } }
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

        thread { if (threads.call("runtime_facts") { "pong" } == "pong") answered.countDown() }

        assertTrue("a quick call waited behind a blocking call", answered.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun framesAndQuickCallsKeepTheirOrder() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val frameEntered = CountDownLatch(1)
        threads.post { frameEntered.countDown(); release.await(10, TimeUnit.SECONDS); order += "frame" }
        assertTrue(frameEntered.await(5, TimeUnit.SECONDS))

        val caller = thread { threads.call("runtime_facts") { order += "call" } }
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

        seen += "blocking:" + named.call("probe_endpoint") { Thread.currentThread().name }
        seen += "quick:" + named.call("runtime_facts") { Thread.currentThread().name }
        seen += "start:" + named.onCore { Thread.currentThread().name }
        named.post { seen += "frame:" + Thread.currentThread().name; framed.countDown() }
        assertTrue(framed.await(5, TimeUnit.SECONDS))

        assertEquals(
            listOf("blocking:hermes-python-net", "quick:hermes-python", "start:hermes-python", "frame:hermes-python"),
            seen.toList(),
        )
    }

    @Test
    fun aFailingCallThrowsItsOwnException() {
        for (function in listOf("runtime_facts", "telegram_check")) {
            val thrown = runCatching { threads.call<Unit>(function) { throw IllegalStateException("refused $function") } }
                .exceptionOrNull()

            assertTrue("$function: $thrown", thrown is IllegalStateException)
            assertEquals("refused $function", thrown!!.message)
        }
    }

    @Test
    fun everyCallRunsWhereTheContractSaysIt() {
        val named = BridgeThreads()
        val blocking = listOf(
            BridgeCalls.PROBE_ENDPOINT, BridgeCalls.DISCOVER_ENDPOINTS, BridgeCalls.SSH_HOST_KEY,
            BridgeCalls.SSH_CHECK_LOGIN, BridgeCalls.TELEGRAM_CHECK, BridgeCalls.HUB_SCAN,
        )
        val quick = listOf(
            BridgeCalls.RUNTIME_FACTS, BridgeCalls.SECRETS_CHANGED, BridgeCalls.GATEWAY_START, BridgeCalls.GATEWAY_STOP,
            BridgeCalls.GATEWAY_STATE, BridgeCalls.SAVE_CUSTOM_ENDPOINT, BridgeCalls.LIST_CUSTOM_ENDPOINTS,
            BridgeCalls.DELETE_CUSTOM_ENDPOINT, BridgeCalls.SSH_NEW_KEY, BridgeCalls.SSH_PUBLIC_KEY,
            BridgeCalls.SSH_TRUST_HOST, BridgeCalls.SSH_CONFIGURE, BridgeCalls.TERMINAL_STATUS,
            BridgeCalls.TERMINAL_LOCAL, BridgeCalls.TELEGRAM_CONFIGURE, BridgeCalls.TELEGRAM_STATUS,
            BridgeCalls.SKILLS_ARCHIVED, BridgeCalls.SKILLS_RESTORE, BridgeCalls.HUB_OFFICIAL, BridgeCalls.TOOLSETS_KEYS,
        )

        for (descriptor in blocking) {
            assertTrue("${descriptor.name} is not declared blocking", descriptor.blocking)
            assertEquals(descriptor.name, "hermes-python-net", named.call(descriptor.name) { Thread.currentThread().name })
        }
        for (descriptor in quick) {
            assertFalse("${descriptor.name} is declared blocking", descriptor.blocking)
            assertEquals(descriptor.name, "hermes-python", named.call(descriptor.name) { Thread.currentThread().name })
        }
        assertEquals(
            setOf("probe_endpoint", "discover_endpoints", "ssh_host_key", "ssh_check_login", "telegram_check", "hub_scan"),
            BridgeCalls.BLOCKING,
        )
    }

    @Test
    fun anUndeclaredNameStaysInOrderWithTheFrames() {
        // Python refuses an undeclared name (hermes_android.call); it is refused there, in order, never on the network thread.
        assertEquals("hermes-python", BridgeThreads().call("stop") { Thread.currentThread().name })
    }

    @Test
    fun aSlowCheckDoesNotHoldAFrame() {
        holdABlockingCall("ssh_host_key")
        val framed = CountDownLatch(1)

        threads.post { framed.countDown() }

        assertTrue("a frame waited behind an SSH host look", framed.await(1, TimeUnit.SECONDS))
    }
}
