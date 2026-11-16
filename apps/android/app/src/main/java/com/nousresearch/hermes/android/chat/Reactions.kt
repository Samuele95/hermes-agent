package com.nousresearch.hermes.android.chat

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
