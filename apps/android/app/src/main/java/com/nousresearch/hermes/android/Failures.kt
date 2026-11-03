package com.nousresearch.hermes.android

import com.nousresearch.hermes.bridge.CoreError
import kotlin.coroutines.cancellation.CancellationException

/**
 * Words this app wrote for the user (a setup step it refuses), apart from the core's [CoreError]s and from failures
 * named only by their kind.
 */
class SetupRefusal(message: String) : Exception(message)

/**
 * What a failed step tells the user. The core's refusals and the app's own ([SetupRefusal]) are written for people;
 * any other failure (the keystore, the bridge, whose messages may carry a traceback or a secret) is named only by
 * its kind. A cancelled coroutine is not a failure and goes on.
 */
fun forUser(failed: Exception): String {
    if (failed is CancellationException) throw failed
    return (failed as? CoreError)?.message ?: (failed as? SetupRefusal)?.message
        ?: "Something went wrong (${failed.javaClass.simpleName})."
}
