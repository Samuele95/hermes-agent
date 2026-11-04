package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.bridge.protocol.SessionCompressResult
import com.nousresearch.hermes.bridge.protocol.SessionContextBreakdownResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The context sheet: how full the session's context is ([meter], the chat's), what fills it ([breakdown], the core's,
 * dropped on close), and what Compress last did ([outcome]) or what went wrong ([error]); whether Compress runs is the
 * chat's (`Chat.compressing`). The breakdown is loading while it and [error] are both null.
 */
data class ContextUi(val meter: ContextMeter?, val breakdown: SessionContextBreakdownResult? = null,
                     val error: String? = null, val outcome: String? = null)

/**
 * The context sheet's controller (spec sessions-16/17): [open] shows the [meter] and reads the [breakdown]; [compress]
 * runs the chat's, which refuses in words what it cannot do; [compressing]: the chat's session is being compressed
 * (invariant 6).
 */
class ContextPanel(
    private val meter: () -> ContextMeter?,
    private val breakdown: suspend () -> SessionContextBreakdownResult,
    private val compress: suspend (focus: String?) -> String,
    private val compressing: () -> Boolean,
) {
    private val screen = MutableStateFlow<ContextUi?>(null)
    val state: StateFlow<ContextUi?> = screen.asStateFlow()
    private val tapping = AtomicBoolean(false)  // one Compress of this sheet at a time: a double tap acts once (improve #3, V2)

    suspend fun open() {
        screen.value = ContextUi(meter())
        read()
    }

    /** Closes the sheet; not while Compress runs (D3): the core holds the session for it. */
    fun close() {
        screen.update { if (it != null && compressing()) it else null }
    }

    /**
     * Compresses the session on [focus] (blank: none); the sheet then shows the new fill, a new breakdown and the core's
     * words (D7). One tap at a time; a tap while this sheet's own is on its way does nothing.
     */
    suspend fun compress(focus: String?) {
        // Closed: nothing to do. A tap of this sheet already on its way (compress + read, V3): this one is the same intent.
        if (screen.value == null || !tapping.compareAndSet(false, true)) return
        try {
            screen.update { it?.copy(error = null, outcome = null) }
            val said = try {
                compress.invoke(focus)
            } catch (failed: Exception) {
                screen.update { it?.copy(error = forUser(failed)) }
                return
            }
            screen.update { it?.copy(meter = meter(), breakdown = null, error = null, outcome = said) }
            read()
        } finally {
            tapping.set(false)  // success, refusal, failure or cancellation: the next tap is admitted
        }
    }

    /** The core's breakdown into the open sheet, or its words; a closed sheet stays closed. */
    private suspend fun read() {
        val read = try {
            breakdown()
        } catch (failed: Exception) {
            screen.update { it?.copy(error = forUser(failed)) }
            return
        }
        screen.update { it?.copy(breakdown = read) }
    }
}

/** What a compression did, in the core's words (D9): its summary lines, else its message, else a count. */
fun compressionOutcome(result: SessionCompressResult): String =
    result.summary?.takeIf { it.headline.isNotBlank() }
        ?.let { s -> listOf(s.headline, s.tokenLine, s.note.orEmpty()).filter(String::isNotBlank).joinToString("\n") }
        ?: result.message?.takeIf { it.isNotBlank() }
        ?: if ((result.removed ?: 0) > 0) "Compressed ${result.removed} messages." else "Nothing to compress."
