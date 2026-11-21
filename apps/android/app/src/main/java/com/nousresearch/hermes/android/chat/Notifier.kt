package com.nousresearch.hermes.android.chat

/**
 * What the user is told about a chat while no screen of the app shows it: a question that waits for them, and
 * a reply that is done. The chat says when; the adapter decides whether anyone needs telling (the app may be
 * on screen) and how.
 */
interface Notifier {
    /** The core waits for the user's answer to [prompt]; its turn waits with it, until the core's deadline. */
    fun promptPending(prompt: Prompt)

    /** No question of the chat waits any more: answered, withdrawn by the core, or let go with its session. */
    fun promptsSettled()

    /** A turn ended; [reply] is its last reply, if it wrote one. */
    fun turnFinished(reply: Entry.Reply?)

    /** Tells no one: a chat nobody needs to hear about. */
    object None : Notifier {
        override fun promptPending(prompt: Prompt) {}
        override fun promptsSettled() {}
        override fun turnFinished(reply: Entry.Reply?) {}
    }
}
