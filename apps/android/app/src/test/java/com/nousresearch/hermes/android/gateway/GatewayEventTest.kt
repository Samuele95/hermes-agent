package com.nousresearch.hermes.android.gateway

import com.nousresearch.hermes.bridge.contract.GatewayState
import com.nousresearch.hermes.bridge.contract.GatewayStateEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The gateway host's state lines, the bridge contract's `gateway_state` event, as the service reads them. */
class GatewayEventTest {
    @Test
    fun eachStateTheHostReportsIsRead() {
        assertEquals(GatewayStateEvent(GatewayState.RUNNING, null), gatewayStateEvent("""{"gateway": "running", "reason": null}"""))
        assertEquals(GatewayStateEvent(GatewayState.RESTARTING, null), gatewayStateEvent("""{"gateway": "restarting", "reason": null}"""))
        assertEquals(GatewayStateEvent(GatewayState.STOPPED, null), gatewayStateEvent("""{"gateway": "stopped", "reason": null}"""))
        assertEquals(GatewayStateEvent(GatewayState.FAILED, "the gateway exited with code 78"),
                     gatewayStateEvent("""{"gateway": "failed", "reason": "the gateway exited with code 78"}"""))
    }

    @Test
    fun aLineThatIsNoKnownStateIsNoEvent() {
        assertNull(gatewayStateEvent("""{"gateway": "sleeping", "reason": null}"""))
        assertNull(gatewayStateEvent("""{"type": "gateway.ready"}"""))
        assertNull(gatewayStateEvent("not json"))
    }
}
