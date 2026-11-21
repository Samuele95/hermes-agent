package com.nousresearch.hermes.android.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The sessions a chat left, each closed in the core once its agent build has settled (B3-D2), unless the chat came back
 * to it (D3): one close record per session however often it is left, gone once it has its [Outcome] (improve #5).
 * Lock order: [switching], then the chat's own lock (read by [stillHere]); the records' monitor is never held across
 * a call or a suspension.
 */
internal class LeftSessions(
    private val scope: CoroutineScope,
    private val switching: Mutex,
    private val stillHere: (runtime: RuntimeSessionId) -> Boolean,
    private val close: suspend (runtime: RuntimeSessionId) -> Unit,  // session.close; throws when the call fails
    private val settleWait: Duration = SETTLE_WAIT,
    private val closeWait: Duration = CLOSE_WAIT,
) {
    companion object {
        /** How long a left session's build may take to announce itself before it is left live (W3). */
        val SETTLE_WAIT = 30.seconds
        /** How long a delete waits for a close's outcome (W4): the settle wait and one close call. */
        val CLOSE_WAIT = 60.seconds
    }

    /** What became of a left session: closed; kept (the chat came back); failed (still live, D5); no build frame in time. */
    enum class Outcome { CLOSED, KEPT, FAILED, UNSETTLED }

    /** One pending close: the stored row it belongs to and the outcome it will have. */
    private class Record(val stored: StoredSessionId, val outcome: CompletableDeferred<Outcome>)

    private val records = mutableMapOf<RuntimeSessionId, Record>()  // guarded by itself; by runtime id

    /** Sessions whose build has announced itself or failed, while the core holds them live (W1). */
    private val marks = MutableStateFlow<Set<RuntimeSessionId>>(emptySet())

    /** A `session.info` or `error` of [runtime]: its build has settled. */
    fun settled(runtime: RuntimeSessionId) = marks.update { it + runtime }

    /** [runtime] was closed aside (rename's lazy mount): the core holds it no more. */
    fun forget(runtime: RuntimeSessionId) = marks.update { it - runtime }

    /** The chat left idle session [ref]: its close, once (a). A pending record is shared; a new one starts its close. */
    fun leave(ref: SessionRef): Deferred<Outcome> {
        val record = synchronized(records) {
            records[ref.runtime]?.let { return it.outcome }
            Record(ref.stored, CompletableDeferred()).also { records[ref.runtime] = it }
        }
        scope.launch {  // outside the records' monitor: an eager start may call close at once
            var outcome = Outcome.FAILED  // cancelled or thrown: not closed
            try {
                outcome = closeOnce(ref.runtime)
            } finally {
                synchronized(records) { records.remove(ref.runtime, record) }  // first: a new leave starts a new close
                record.outcome.complete(outcome)
            }
        }
        return record.outcome
    }

    /** The close still pending for stored row [stored], if the chat left that row's session (W7). */
    fun closing(stored: StoredSessionId): Deferred<Outcome>? =
        synchronized(records) { records.values.firstOrNull { it.stored == stored }?.outcome }

    /** [outcome] within [closeWait], else null: the close is still running (W4, W6). */
    suspend fun waitFor(outcome: Deferred<Outcome>): Outcome? = withTimeoutOrNull(closeWait) { outcome.await() }

    /** Closes [runtime] once its build has settled, within [settleWait], unless the chat is back on it. */
    private suspend fun closeOnce(runtime: RuntimeSessionId): Outcome {
        // D2, bounded (W3): a build that never announces itself leaves the session live, as a failed close does (D5).
        withTimeoutOrNull(settleWait) { marks.first { runtime in it } } ?: return Outcome.UNSETTLED
        return switching.withLock {
            if (stillHere(runtime)) return@withLock Outcome.KEPT  // D3: the chat came back to it
            try {
                close(runtime)
                marks.update { it - runtime }
                Outcome.CLOSED
            } catch (failed: Exception) {
                if (failed is CancellationException) throw failed
                Outcome.FAILED  // D5: it stays live, and so does its mark: the next leave tries again at once
            }
        }
    }
}
