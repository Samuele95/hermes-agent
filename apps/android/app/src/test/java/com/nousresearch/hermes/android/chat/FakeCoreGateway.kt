package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.CoreEvent
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.InTurnPrompt
import com.nousresearch.hermes.bridge.protocol.Method
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A [CoreGateway] whose core is a function: [reply] answers each call, [emit] sends an event frame. */
class FakeCoreGateway(private val reply: (method: String, params: JsonObject) -> String) : CoreGateway {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val eventFlow = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64)
    val calls = mutableListOf<Pair<String, JsonObject>>()

    override val events: SharedFlow<CoreEvent> = eventFlow
    override val prompts: SharedFlow<InTurnPrompt> get() = promptFlow

    override suspend fun <P, R> call(method: Method<P, R>, params: P): R {
        val sent = json.encodeToJsonElement(method.params, params) as JsonObject
        calls += method.name to sent
        return json.decodeFromString(method.result, reply(method.name, sent))
    }

    private val promptFlow = MutableSharedFlow<InTurnPrompt>(extraBufferCapacity = 64)
    /** Each settled prompt: its id and the result sent, or `declined: <reason>`. */
    val settled = mutableListOf<Pair<String, String>>()

    override fun answer(prompt: InTurnPrompt, result: JsonObject) { settled += prompt.id to result.toString() }

    override fun decline(prompt: InTurnPrompt, reason: String) { settled += prompt.id to "declined: $reason" }

    suspend fun ask(id: String, method: String, params: String) = promptFlow.emit(InTurnPrompt(id, method, json.parseToJsonElement(params) as JsonObject))

    suspend fun emit(type: String, session: String, payload: String? = null) =
        eventFlow.emit(CoreEvent(type, session, payload?.let { json.parseToJsonElement(it) }))
}
