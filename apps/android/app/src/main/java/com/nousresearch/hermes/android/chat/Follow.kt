package com.nousresearch.hermes.android.chat

/**
 * Whether the transcript follows its newest row (chat-17, blueprint F9), after the list moved or its content changed.
 * A reader who drags back ([draggedBack]) stops it. Reaching the end ([atEnd]: nothing below) starts it again, by hand
 * or through Jump. Content growing below a follower keeps it. This is Desktop's stick-to-bottom: "escape on user
 * scroll-up, re-lock at bottom" (apps/desktop/src/components/assistant-ui/thread/list.tsx:501-503). It is the view's
 * state, never the conversation's (DC-B16): the caller keeps it in Compose.
 */
internal fun follows(was: Boolean, atEnd: Boolean, draggedBack: Boolean): Boolean = atEnd || (was && !draggedBack)
