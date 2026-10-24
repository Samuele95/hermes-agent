package com.nousresearch.hermes.android.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gateway host's state lines (`hermes_android.gateway_host`) as the service reads them. */
class GatewayEventTest {
    @Test
    fun eachStateTheHostReportsIsRead() {
        assertEquals(GatewayEvent(GatewayEvent.State.RUNNING), GatewayEvent.parse("""{"gateway": "running"}"""))
        assertEquals(GatewayEvent(GatewayEvent.State.RESTARTING), GatewayEvent.parse("""{"gateway": "restarting"}"""))
        assertEquals(GatewayEvent(GatewayEvent.State.STOPPED), GatewayEvent.parse("""{"gateway": "stopped"}"""))
        assertEquals(GatewayEvent(GatewayEvent.State.FAILED, "the gateway exited with code 78"),
                     GatewayEvent.parse("""{"gateway": "failed", "reason": "the gateway exited with code 78"}"""))
    }

    @Test
    fun aLineThatIsNoKnownStateIsNoEvent() {
        assertNull(GatewayEvent.parse("""{"gateway": "sleeping"}"""))
        assertNull(GatewayEvent.parse("""{"type": "gateway.ready"}"""))
        assertNull(GatewayEvent.parse("not json"))
    }
}
