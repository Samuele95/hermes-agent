package com.nousresearch.hermes.bridge

import com.nousresearch.hermes.bridge.protocol.ApprovalChoice
import com.nousresearch.hermes.bridge.protocol.ApprovalResult
import com.nousresearch.hermes.bridge.protocol.Events
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.PingParams
import com.nousresearch.hermes.bridge.protocol.PingResult
import com.nousresearch.hermes.bridge.protocol.Requests
import com.nousresearch.hermes.bridge.protocol.StreamDeltaPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON-RPC side of the app: requests matched to responses by id, events and in-turn prompts as flows. */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: frames are answered synchronously
class FrameCoreGatewayTest {
    private val sent = mutableListOf<JsonObject>()
    private val gateway = FrameCoreGateway { sent += Json.parseToJsonElement(it).jsonObject }

    private fun lastId(): Int = sent.last().getValue("id").jsonPrimitive.int

    @Test
    fun aCallSendsARequestAndReturnsItsTypedResult() = runTest(UnconfinedTestDispatcher()) {
        val pong = async { gateway.call(Methods.PING, PingParams) }

        assertEquals("ping", sent.single().getValue("method").jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), sent.single().getValue("params"))
        gateway.onFrame("""{"jsonrpc":"2.0","id":${lastId()},"result":{"pong":true}}""")

        assertEquals(PingResult(pong = true), pong.await())
    }

    @Test
    fun responsesAreMatchedToTheirRequestsById() = runTest(UnconfinedTestDispatcher()) {
        val first = async { gateway.call(Methods.PING, PingParams) }
        val second = async { gateway.call(Methods.PING, PingParams) }
        val (firstId, secondId) = sent.map { it.getValue("id").jsonPrimitive.int }

        gateway.onFrame("""{"jsonrpc":"2.0","id":$secondId,"result":{"pong":false}}""")
        gateway.onFrame("""{"jsonrpc":"2.0","id":$firstId,"result":{"pong":true}}""")

        assertEquals(listOf(true, false), listOf(first.await().pong, second.await().pong))
    }

    @Test
    fun anErrorResponseFailsTheCall() = runTest(UnconfinedTestDispatcher()) {
        val call = async { runCatching { gateway.call(Methods.PING, PingParams) } }

        gateway.onFrame("""{"jsonrpc":"2.0","id":${lastId()},"error":{"code":-32601,"message":"unknown method: ping"}}""")

        val error = call.await().exceptionOrNull() as CoreError
        assertEquals(-32601 to "unknown method: ping", error.code to error.message)
    }

    @Test
    fun eventFramesArriveAsCoreEventsAndDecodeToTheirPayload() = runTest(UnconfinedTestDispatcher()) {
        val event = async { gateway.events.first() }

        gateway.onFrame("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"s1","payload":{"text":"Hel"}}}""")

        assertEquals("message.delta" to "s1", event.await().type to event.await().sessionId)
        assertEquals(StreamDeltaPayload(text = "Hel"), event.await().decode(Events.MESSAGE_DELTA))
        assertEquals(null, event.await().decode(Events.MESSAGE_COMPLETE))
    }

    @Test
    fun aServerRequestBecomesAPromptAnsweredWithAResponseFrame() = runTest(UnconfinedTestDispatcher()) {
        val prompt = async { gateway.prompts.first() }

        gateway.onFrame("""{"jsonrpc":"2.0","id":"srq-0123456789ab","method":"clarify","params":{"session_id":"s1","questions":[]}}""")
        gateway.answer(prompt.await(), JsonObject(mapOf("answers" to JsonObject(emptyMap()))))

        assertEquals("clarify" to "s1", prompt.await().method to prompt.await().params.getValue("session_id").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":"srq-0123456789ab","result":{"answers":{}}}"""), sent.single())
    }

    @Test
    fun aPromptDecodesAsItsRequestAndIsAnsweredWithItsTypedResult() = runTest(UnconfinedTestDispatcher()) {
        val prompt = async { gateway.prompts.first() }
        gateway.onFrame("""{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s1","request_id":"r1","command":"rm -rf build"}}""")

        val approval = prompt.await().decode(Requests.APPROVAL)
        gateway.answer(prompt.await(), Requests.APPROVAL, ApprovalResult(ApprovalChoice.DENY))

        assertEquals("rm -rf build", approval?.command)
        assertEquals(null, prompt.await().decode(Requests.CLARIFY))
        assertEquals(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":"srq-1","result":{"choice":"deny"}}"""), sent.single())
    }

    @Test
    fun aDeclinedPromptIsAnsweredWithAnErrorSoTheCoreStopsWaiting() = runTest(UnconfinedTestDispatcher()) {
        val prompt = async { gateway.prompts.first() }
        gateway.onFrame("""{"jsonrpc":"2.0","id":"srq-2","method":"sudo","params":{"session_id":"s1","command":"apt"}}""")

        gateway.decline(prompt.await(), "not available in the Android app")

        val frame = sent.single()
        assertEquals("srq-2", frame.getValue("id").jsonPrimitive.content)
        assertEquals("not available in the Android app", frame.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        assertTrue("result" !in frame)
    }

    @Test
    fun framesTheAppCannotReadAreDroppedWithoutFailingPendingCalls() = runTest(UnconfinedTestDispatcher()) {
        val pong = async { gateway.call(Methods.PING, PingParams) }

        gateway.onFrame("not json")
        gateway.onFrame("""{"jsonrpc":"2.0","id":987654,"result":{"pong":true}}""")  // nobody asked
        gateway.onFrame("""{"jsonrpc":"2.0","id":${lastId()},"result":{"pong":true}}""")

        assertTrue(pong.await().pong)
    }

    @Test
    fun aResultThatDoesNotMatchTheMethodFailsTheCallNotTheGateway() = runTest(UnconfinedTestDispatcher()) {
        val call = async { runCatching { gateway.call(Methods.PING, PingParams) } }

        gateway.onFrame("""{"jsonrpc":"2.0","id":${lastId()},"result":{"pong":"maybe"}}""")

        assertTrue(call.await().isFailure)
        val next = async { gateway.call(Methods.PING, PingParams) }
        gateway.onFrame("""{"jsonrpc":"2.0","id":${lastId()},"result":{"pong":true}}""")
        assertTrue(next.await().pong)
    }
}
