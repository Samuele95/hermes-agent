package com.nousresearch.hermes.android.chat

import com.nousresearch.hermes.bridge.protocol.MessageReaction
import org.junit.Assert.assertEquals
import org.junit.Test

/** The user's reactions: a tap as the core applies it, and the core's rows read as the user's own (chat-14, D51). */
class ReactionsTest {
    private fun user(emoji: String) = Reaction(emoji, Author.USER)

    @Test
    fun aTapSetsReplacesOrRetractsTheUsersOwnOnly() {
        assertEquals(listOf(user("❤️")), emptyList<Reaction>().tapped("❤️"))
        assertEquals("another emoji replaces", listOf(user("👍")), listOf(user("❤️")).tapped("👍"))
        assertEquals("the same emoji again retracts", emptyList<Reaction>(), listOf(user("👍")).tapped("👍"))
        assertEquals("null clears", emptyList<Reaction>(), listOf(user("❤️")).tapped(null))
        assertEquals("another author's entry stays where it is", listOf(Reaction("👍", Author.AGENT), user("😂")),
                     listOf(Reaction("👍", Author.AGENT), user("❤️")).tapped("😂"))
    }

    @Test
    fun aDoubleTapHeartsAnAnswerAndAgainRetractsIt() {
        assertEquals("❤️", emptyList<Reaction>().tapback())
        assertEquals("the user's own heart again retracts", null, listOf(user("❤️")).tapback())
        assertEquals("the heart replaces another emoji", "❤️", listOf(user("👍")).tapback())
        assertEquals("another author's heart never counts", "❤️", listOf(Reaction("❤️", Author.AGENT)).tapback())
        assertEquals("Desktop's first quick reaction", QUICK[0], DOUBLE_TAP_REACTION)
    }

    @Test
    fun theCoresRowsAreTheUsersOwnReactions() {
        val read = reactionsOf(listOf(MessageReaction("❤️", "user", 1.0, true), MessageReaction("👍", "agent"),
                                      MessageReaction(" ", "user"), MessageReaction("😂", "bot")))

        assertEquals(listOf(user("❤️")), read)
    }

    @Test
    fun theQuickReactionsAreDesktopsSix() {
        assertEquals(listOf("❤️", "👍", "👎", "😂", "‼️", "❓"), QUICK)
    }
}
