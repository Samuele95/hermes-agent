package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.contract.EndpointProbe
import com.nousresearch.hermes.bridge.contract.FoundServer
import com.nousresearch.hermes.bridge.contract.ProbeEndpointReply
import com.nousresearch.hermes.bridge.contract.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The probe's reply is read in one place: a server or why none was found, never both and never neither. */
class ServerProbeTest {
    private val server = FoundServer("http://spark-98d1:11434/v1", ServerKind.OLLAMA, listOf("gemma4:12b-it-q8_0"))

    @Test
    fun aReplyWithOnlyAServerIsFound() {
        val probe = serverProbe(ProbeEndpointReply(found = server, failure = null))

        assertEquals(ServerProbe.Found(server), probe)
        assertEquals(ServerKind.OLLAMA, (probe as ServerProbe.Found).server.kind)
    }

    @Test
    fun aReplyWithOnlyAFailureIsFailed() {
        assertEquals(ServerProbe.Failed(EndpointProbe.REFUSED), serverProbe(ProbeEndpointReply(found = null, failure = EndpointProbe.REFUSED)))
    }

    @Test
    fun aReplyThatIsBothIsABug() {
        assertThrows(IllegalStateException::class.java) { serverProbe(ProbeEndpointReply(found = server, failure = EndpointProbe.REFUSED)) }
    }

    @Test
    fun aReplyThatIsNeitherIsABug() {
        assertThrows(IllegalStateException::class.java) { serverProbe(ProbeEndpointReply(found = null, failure = null)) }
    }
}
