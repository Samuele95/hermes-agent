package com.nousresearch.hermes.bridge

import com.nousresearch.hermes.bridge.protocol.Event
import com.nousresearch.hermes.bridge.protocol.Method
import com.nousresearch.hermes.bridge.protocol.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A core→client `event` frame: its type, the session it belongs to, and its raw payload. */
data class CoreEvent(val type: String, val sessionId: String?, val payload: JsonElement?) {
    /** The payload as [event]'s type, or null when this frame is another event. */
    fun <P> decode(event: Event<P>): P? =
        if (type == event.name) wire.decodeFromJsonElement(event.payload, payload ?: JsonObject(emptyMap())) else null
}

/**
 * A core→client request waiting on the user (clarify, approval, secret), settled once: answered with
 * [CoreGateway.answer] or declined with [CoreGateway.decline].
 */
data class InTurnPrompt(val id: String, val method: String, val params: JsonObject) {
    /** The params as [request]'s type, or null when this prompt is another request or unreadable. */
    fun <P, R> decode(request: Request<P, R>): P? =
        if (method == request.name) runCatching { wire.decodeFromJsonElement(request.params, params) }.getOrNull() else null
}

/** An error response from the core. */
class CoreError(val code: Int, message: String) : Exception(message)

/** The app's driving port onto the core. */
interface CoreGateway {
    /** Sends one request and suspends until its response; throws [CoreError] on an error response. */
    suspend fun <P, R> call(method: Method<P, R>, params: P): R

    val events: SharedFlow<CoreEvent>

    val prompts: SharedFlow<InTurnPrompt>

    fun answer(prompt: InTurnPrompt, result: JsonObject)

    /** Settles [prompt] with no answer at once (an error response), so the core does not wait out its deadline. */
    fun decline(prompt: InTurnPrompt, reason: String)
}

/** Answers [prompt] with [request]'s typed result. */
fun <P, R> CoreGateway.answer(prompt: InTurnPrompt, request: Request<P, R>, result: R) =
    answer(prompt, wire.encodeToJsonElement(request.result, result) as JsonObject)

// The core adds fields over time; the app reads the ones it models and leaves defaults to the core.
internal val wire = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * [CoreGateway] over JSON-RPC frames: [send] hands one frame to the core, and the core's frames come
 * back through [onFrame], on core threads — so it only completes calls and emits, never sends.
 * Collect [events] and [prompts] before the first call: frames nobody collects are not replayed.
 */
class FrameCoreGateway(private val send: (String) -> Unit) : CoreGateway, FrameSink {
    private companion object {
        /** JSON-RPC's implementation-defined server-error range; the core treats any error response as no answer. */
        const val DECLINED = -32000
    }

    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = Channel.UNLIMITED)
    private val promptFlow = MutableSharedFlow<InTurnPrompt>(extraBufferCapacity = Channel.UNLIMITED)

    override val events: SharedFlow<CoreEvent> = eventFlow.asSharedFlow()
    override val prompts: SharedFlow<InTurnPrompt> = promptFlow.asSharedFlow()

    override suspend fun <P, R> call(method: Method<P, R>, params: P): R {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<JsonElement>()
        pending[id] = reply
        try {
            send(frame("id" to JsonPrimitive(id), "method" to JsonPrimitive(method.name),
                       "params" to wire.encodeToJsonElement(method.params, params)))
            return wire.decodeFromJsonElement(method.result, reply.await())
        } finally {
            pending.remove(id)
        }
    }

    override fun answer(prompt: InTurnPrompt, result: JsonObject) {
        send(frame("id" to JsonPrimitive(prompt.id), "result" to result))
    }

    override fun decline(prompt: InTurnPrompt, reason: String) {
        send(frame("id" to JsonPrimitive(prompt.id),
                   "error" to JsonObject(mapOf("code" to JsonPrimitive(DECLINED), "message" to JsonPrimitive(reason)))))
    }

    override fun onFrame(frame: String) {
        val message = runCatching { wire.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val id = message["id"] as? JsonPrimitive
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        when {
            method == "event" -> eventFlow.tryEmit(
                CoreEvent(params.text("type") ?: return, params.text("session_id"), params["payload"]))
            method != null && id != null && id.isString -> promptFlow.tryEmit(InTurnPrompt(id.content, method, params))
            id != null -> respond(id.longOrNull ?: return, message)
        }
    }

    private fun respond(id: Long, message: JsonObject) {
        val reply = pending[id] ?: return  // the caller gave up, or nobody asked
        val error = message["error"] as? JsonObject
        if (error == null) {
            reply.complete(message["result"] ?: JsonNull)
        } else {
            reply.completeExceptionally(CoreError((error["code"] as? JsonPrimitive)?.intOrNull ?: 0, error.text("message") ?: ""))
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun frame(vararg fields: Pair<String, JsonElement>): String =
        JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), *fields)).toString()
}
