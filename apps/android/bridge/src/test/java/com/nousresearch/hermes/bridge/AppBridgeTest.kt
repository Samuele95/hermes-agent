package com.nousresearch.hermes.bridge

import com.nousresearch.hermes.bridge.contract.BridgeCalls
import com.nousresearch.hermes.bridge.contract.Pinned
import com.nousresearch.hermes.bridge.contract.SaveCustomEndpointParams
import com.nousresearch.hermes.bridge.contract.SshHostKeyParams
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The app's typed bridge calls send the contract's request and read its reply (D26). */
class AppBridgeTest {
    private val sent = mutableListOf<Pair<String, String>>()
    private fun answering(reply: String) = AppBridge { function, arg -> sent += function to arg; reply }

    @Test
    fun aRequestGoesOutUnderTheCallsNameWithTheContractsFieldNames() = runBlocking {
        answering("""{"id":"lan","key_env":""}""")
            .ask(BridgeCalls.SAVE_CUSTOM_ENDPOINT, SaveCustomEndpointParams("lan", "http://10.0.0.2/v1", "m", true))

        val (function, arg) = sent.single()
        assertEquals("save_custom_endpoint", function)
        assertEquals(Json.parseToJsonElement("""{"name":"lan","base_url":"http://10.0.0.2/v1","model":"m","has_key":true}"""),
                     Json.parseToJsonElement(arg))
    }

    @Test
    fun aCallWithoutParamsSendsAnEmptyObject() = runBlocking {
        answering("""{"public":"ssh-ed25519 AAAA"}""").ask(BridgeCalls.SSH_PUBLIC_KEY)

        assertEquals("{}", sent.single().second)
    }

    @Test
    fun theReplyDecodesAsTheCallsDeclaredType() = runBlocking {
        val seen = answering("""{"key":"ssh-ed25519 AAAA","fingerprint":"SHA256:x","pinned":"changed"}""")
            .ask(BridgeCalls.SSH_HOST_KEY, SshHostKeyParams("h", 22))

        assertEquals(Pinned.CHANGED, seen.pinned)
    }

    @Test
    fun anErrorReplyIsTheUsersWords() {
        val refused = assertThrows(CoreError::class.java) {
            runBlocking { answering("""{"error":"Could not reach h:22"}""").ask(BridgeCalls.SSH_HOST_KEY, SshHostKeyParams("h", 22)) }
        }

        assertEquals("Could not reach h:22", refused.message)
    }
}
