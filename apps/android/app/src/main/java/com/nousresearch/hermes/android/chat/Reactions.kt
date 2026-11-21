package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.MessageReaction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Who reacted, in the core's two words (`ReactionAuthor`). */
enum class Author(val wire: String) {
    USER("user"), AGENT("agent");

    companion object {
        /** The author the core's [word] names; null for any other word. */
        fun of(word: String): Author? = entries.firstOrNull { it.wire == word }
    }
}

/** One reaction on a message; the core keeps one per author (tapback: the same emoji again retracts, another replaces). */
data class Reaction(val emoji: String, val author: Author)

/**
 * The user's own reactions in a stored row's [meta] (`display_metadata.reactions`, `hermes_state.py:1615`
 * `REACTIONS_METADATA_KEY`): a list of `{emoji, author, at}`. An item the app cannot read is left out, never a throw, and
 * so is an agent's reaction (D51: "your reactions only"; only a desktop-sourced session can carry one). No list, or
 * anything unreadable: none.
 */
fun reactionsOf(meta: JsonElement?): List<Reaction> {
    val items = (meta as? JsonObject)?.get("reactions") as? JsonArray ?: return emptyList()
    return items.mapNotNull { item ->
        val fields = item as? JsonObject ?: return@mapNotNull null
        fun word(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val emoji = word("emoji")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val author = word("author")?.let(Author::of) ?: return@mapNotNull null
        Reaction(emoji, author).takeIf { it.author == Author.USER }
    }
}

/** Desktop's six quick reactions, Apple's Tapback defaults in Apple's order (reactions.ts:9; DC-P16: no full picker). */
val QUICK = listOf("❤️", "👍", "👎", "😂", "‼️", "❓")

/** The tapback a quick double tap on an answer lands: Apple's first Tapback, as Desktop's double-click (use-message-reactions.ts:27). */
val DOUBLE_TAP_REACTION: String = QUICK[0]

/**
 * The emoji a double tap sends for these reactions: null (retract) when the user's own is already [DOUBLE_TAP_REACTION],
 * else [DOUBLE_TAP_REACTION] (it replaces any other); other authors' entries never count. Desktop's
 * `mine?.emoji === DOUBLE_CLICK_REACTION ? null : …`.
 */
fun List<Reaction>.tapback(): String? =
    DOUBLE_TAP_REACTION.takeUnless { heart -> firstOrNull { it.author == Author.USER }?.emoji == heart }

/**
 * These reactions after the user's tap on [emoji], as the core will apply it (`set_message_reaction`, Desktop's
 * `applyReaction`): the user's own entry goes, and [emoji] takes its place unless it is null or the one that went (the
 * same emoji again retracts, another replaces). Other authors' entries stay where they are.
 */
fun List<Reaction>.tapped(emoji: String?): List<Reaction> {
    val mine = firstOrNull { it.author == Author.USER }
    val others = filterNot { it.author == Author.USER }
    return if (emoji == null || emoji == mine?.emoji) others else others + Reaction(emoji, Author.USER)
}

/**
 * The user's own reactions in the core's [rows] (a `message.react` answer, a `message.reaction` payload), read as
 * [reactionsOf] reads a stored row: a non-blank emoji by the user, in the core's order; anything else is left out (D7,
 * D51). Total.
 */
fun reactionsOf(rows: List<MessageReaction>): List<Reaction> =
    rows.filter { it.emoji.isNotBlank() && Author.of(it.author) == Author.USER }.map { Reaction(it.emoji, Author.USER) }
