package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.android.forUser
import com.nousresearch.hermes.android.setup.CatalogAt
import com.nousresearch.hermes.android.setup.CustomModels
import com.nousresearch.hermes.android.setup.ModelCatalog
import com.nousresearch.hermes.android.setup.ModelRow
import com.nousresearch.hermes.android.setup.Switched
import com.nousresearch.hermes.android.setup.of
import com.nousresearch.hermes.bridge.CoreGateway
import com.nousresearch.hermes.bridge.protocol.ConfigSetParams
import com.nousresearch.hermes.bridge.protocol.Methods
import com.nousresearch.hermes.bridge.protocol.ModelOptionsParams
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive

/** A pick the core takes only once the user confirms ([row]), with the core's own words ([message]; may be blank). */
data class Asked(val row: ModelRow, val message: String)

/**
 * The chat's model picker while it is open (models-08). [catalog]: null while the core's list loads; [loadFailed]: the
 * core's words when it could not list; [query]: what the field holds; [adding]: the field takes a model id ("Add
 * custom model…"); [switching]: the pick on its way, null when none; [confirm]: a pick the core asks the user to
 * confirm; [next]: the model the core applies from the next message; [failed]: the core's words on a refused pick (may
 * be blank); [locked]: the `key` of a Pro-locked row the user asked for, never sent.
 */
data class PickerUi(val catalog: ModelCatalog? = null, val loadFailed: String? = null, val query: String = "",
                    val adding: Boolean = false, val switching: ModelRow? = null, val confirm: Asked? = null,
                    val next: String? = null, val failed: String? = null, val locked: String? = null)

/**
 * The chat's model picker (models-08, bet 5): one list of every signed-in provider's models, and a pick that switches
 * only this chat's [session], in place, with a session-scoped `config.set` (no scope flag: the core decides whether it
 * also persists, DC-P2); the conversation stays as it is, with no new session. The default model is Setup's own
 * operation, never this class's (EJ-51: two operations with different scope, two methods). The last pick wins: an older pick's
 * answer, and any answer for a picker closed meanwhile, lands nowhere (DC-P3).
 *
 * [session]: the runtime id of the chat's core session, null before the chat is open; [custom]: the ids the user added
 * (M5's store, kept only once the core took one, DC-M9); [now]: the clock the facts are read at.
 */
class ModelSwitch(private val core: CoreGateway, private val session: () -> String?,
                  private val custom: CustomModels = CustomModels.InMemory(),
                  private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        /** A pick with no core session to switch: nothing is sent. */
        const val NO_CHAT = "There is no chat to switch yet."

        /** The mixture-of-agents preset: a transient pick that never becomes the default (D5, Desktop's `--session`). */
        private const val MOA = "moa"
    }

    private val screen = MutableStateFlow<PickerUi?>(null)

    /** The picker while it is open, null when closed. */
    val state: StateFlow<PickerUi?> = screen.asStateFlow()

    private val opens = AtomicLong()  // the latest showing; a list for an older one lands nowhere
    private val picks = AtomicLong()  // the latest pick; an older pick's answer lands nowhere

    /**
     * Opens the picker loading, then shows the core's list for this chat (one cache-only `model.options`) or its words.
     * A picker closed or opened again meanwhile keeps what it shows; typing meanwhile is kept.
     */
    suspend fun open() {
        val mine = opens.incrementAndGet()
        picks.incrementAndGet()  // a pick from an earlier showing lands nowhere
        screen.value = PickerUi()
        var catalog: ModelCatalog? = null
        var loadFailed: String? = null
        try {
            val options = core.call(Methods.MODEL_OPTIONS, ModelOptionsParams(sessionId = session()))
            catalog = ModelCatalog.of(options, CatalogAt(now(), options.providers.associate { it.slug to custom[it.slug] }))
        } catch (failed: Exception) {
            loadFailed = forUser(failed)  // rethrows a cancellation
        }
        screen.update { if (it == null || opens.get() != mine) it else it.copy(catalog = catalog, loadFailed = loadFailed) }
    }

    /** Closes the picker; a pick on its way lands nowhere. */
    fun close() {
        picks.incrementAndGet()
        screen.value = null
    }

    /** The field now holds [text]; a locked row's explanation goes. */
    fun query(text: String) = screen.update { it?.copy(query = text, locked = null) }

    /** "Add custom model…": the field takes a model id. */
    fun addCustom() = screen.update { it?.copy(adding = true) }

    /** "Keep current model": the confirm goes and nothing is sent. */
    fun keep() = screen.update { it?.copy(confirm = null) }

    /**
     * Switches this chat to [row] with one `config.set` (sent with `confirm_expensive_model` when [confirmed]): the
     * picker closes once the core switched, says when the core keeps it for the next message, asks in the core's words
     * when it must be confirmed, or shows the core's refusal with the list kept. A Pro-locked row is never sent (N10);
     * with no chat nothing is sent. A typed id is kept once the core took it, even when a newer pick replaced it.
     */
    suspend fun pick(row: ModelRow, confirmed: Boolean = false) {
        if (screen.value == null) return
        if (row.facts.locked) {  // the core's unavailable_models: never sent, never silent
            screen.update { it?.copy(locked = row.key, failed = null) }
            return
        }
        val sid = session()
        if (sid == null) {
            screen.update { it?.copy(failed = NO_CHAT) }
            return
        }
        val mine = picks.incrementAndGet()
        screen.update { it?.copy(switching = row, confirm = null, next = null, failed = null, locked = null) }
        val answered = try {
            Switched.of(core.call(Methods.CONFIG_SET, ConfigSetParams(sessionId = sid, key = "model", value = JsonPrimitive(valueOf(row)),
                                                                      confirmExpensiveModel = confirmed)), row.model)
        } catch (cancelled: CancellationException) {
            screen.update { if (it == null || picks.get() != mine) it else it.copy(switching = null) }
            throw cancelled
        } catch (failed: Exception) {
            Switched.Failed(forUser(failed))
        }
        // A confirmed resend the core still holds is a failure in its words (Desktop's guarded-model-switch.ts:83-85).
        val outcome = if (confirmed && answered is Switched.Confirm) Switched.Failed(answered.message) else answered
        if (row.custom && (outcome is Switched.Now || outcome is Switched.Next)) custom.add(row.provider, row.model)  // the core took it
        screen.update { ui ->
            if (ui == null || picks.get() != mine) ui
            else when (outcome) {
                is Switched.Now -> null  // switched: the chat's model follows session.info
                is Switched.Next -> ui.copy(switching = null, next = outcome.model)
                is Switched.Confirm -> ui.copy(switching = null, confirm = Asked(row, outcome.message))
                is Switched.Failed -> ui.copy(switching = null, failed = outcome.words)
            }
        }
    }

    /** "Switch anyway": sends the asked pick again, confirmed. */
    suspend fun confirm() {
        val asked = screen.value?.confirm ?: return
        pick(asked.row, confirmed = true)
    }

    /** The `config.set` value: the model and its provider, with no scope flag but for the mixture-of-agents preset (DC-P2, D5). */
    private fun valueOf(row: ModelRow) = "${row.model} --provider ${row.provider}" + if (row.provider == MOA) " --session" else ""
}
