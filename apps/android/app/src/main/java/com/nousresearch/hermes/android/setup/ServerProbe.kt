package com.nousresearch.hermes.android.setup

import com.nousresearch.hermes.bridge.contract.EndpointProbe
import com.nousresearch.hermes.bridge.contract.FoundServer
import com.nousresearch.hermes.bridge.contract.ProbeEndpointReply

/** What Find server learned at an address: the server and its models, or why none was found. */
sealed interface ServerProbe {
    data class Found(val server: FoundServer) : ServerProbe

    data class Failed(val failure: EndpointProbe) : ServerProbe
}

/**
 * The one place the probe's reply is read; `found ⇒ base URL and kind present` is a type from here on. A reply that is
 * both found and failed, or neither, is a bug on one side of the bridge and is refused, never read as a server.
 */
internal fun serverProbe(reply: ProbeEndpointReply): ServerProbe {
    val found = reply.found
    val failure = reply.failure
    return when {
        found != null && failure == null -> ServerProbe.Found(found)
        failure != null && found == null -> ServerProbe.Failed(failure)
        else -> throw IllegalStateException("probe_endpoint answered found=${found != null} failure=${failure != null}")
    }
}
