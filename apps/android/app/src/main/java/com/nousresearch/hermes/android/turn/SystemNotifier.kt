package com.nousresearch.hermes.android.turn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nousresearch.hermes.android.MainActivity
import com.nousresearch.hermes.android.R
import com.nousresearch.hermes.android.chat.Entry
import com.nousresearch.hermes.android.chat.Notifier
import com.nousresearch.hermes.android.chat.Prompt
import com.nousresearch.hermes.bridge.protocol.TurnStatus

/**
 * [Notifier] as Android notifications, posted only while [visible] is false: on screen, the chat shows the
 * question or the reply itself. A waiting question goes on the high-importance `questions` channel, since its
 * turn waits for the user until the core's deadline; a finished reply on `replies`. Both open the app, and on
 * the lock screen both say only that Hermes asks or replied.
 */
class SystemNotifier(private val context: Context, private val visible: () -> Boolean) : Notifier {
    companion object {
        // TurnService uses 1, GatewayService 2 and 3: the app's processes share one notification namespace.
        const val QUESTION_ID = 4
        const val REPLY_ID = 5
        const val QUESTIONS = "questions"
        const val REPLIES = "replies"
    }

    private val manager = NotificationManagerCompat.from(context)

    override fun promptPending(prompt: Prompt) {
        val (title, text) = when (prompt) {
            is Prompt.Approval -> R.string.notify_approval to prompt.command
            is Prompt.Clarify -> R.string.notify_clarify to prompt.questions.firstOrNull()?.question
            is Prompt.Secret -> R.string.notify_secret to prompt.prompt  // what is asked for, never a value
        }
        post(QUESTIONS, QUESTION_ID, title, text)
    }

    override fun promptsSettled() = manager.cancel(QUESTION_ID)

    override fun turnFinished(reply: Entry.Reply?) {
        if (reply == null || reply.status == TurnStatus.ERROR) post(REPLIES, REPLY_ID, R.string.notify_reply_failed, reply?.error)
        else post(REPLIES, REPLY_ID, R.string.notify_replied, reply.text)
    }

    /** The user is looking at the app: what the notifications would say is on screen. */
    fun shown() {
        manager.cancel(QUESTION_ID)
        manager.cancel(REPLY_ID)
    }

    private fun post(channel: String, id: Int, title: Int, text: String?) {
        if (visible() || !manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val (name, importance) = if (channel == QUESTIONS) R.string.notify_channel_questions to NotificationManager.IMPORTANCE_HIGH
                                     else R.string.notify_channel_replies to NotificationManager.IMPORTANCE_DEFAULT
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(channel, context.getString(name), importance))
        }
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun builder() = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(title))
            .setContentIntent(open)
            .setAutoCancel(true)
        val shown = builder()
            .setContentText(text?.trim()?.take(300))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text?.trim()?.take(1000)))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(builder().build())
            .build()
        try {
            manager.notify(id, shown)
        } catch (_: SecurityException) {  // the permission was revoked between the check and the post
        }
    }
}
