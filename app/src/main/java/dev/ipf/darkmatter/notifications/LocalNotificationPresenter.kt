package dev.ipf.darkmatter.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import dev.ipf.darkmatter.BuildConfig
import dev.ipf.darkmatter.MainActivity
import dev.ipf.darkmatter.R
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi

class LocalNotificationPresenter(
    private val context: Context,
) {
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_messages_description)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun show(
        update: NotificationUpdateFfi,
        conversationTitleOverride: String? = null,
    ): Boolean {
        val content =
            LocalNotificationFormatter.content(update, context) ?: run {
                notificationDebug { "skip key=${update.notificationKey.take(16)} reason=formatter" }
                return false
            }
        if (!canPostNotifications()) {
            notificationDebug { "skip key=${update.notificationKey.take(16)} reason=permission" }
            return false
        }
        // Channels are created during AppState bootstrap / runtime start
        // (AppState.bootstrap() and ensureNotificationRuntimeStarted() both
        // call ensureChannels()); we deliberately don't recreate them on
        // every show() to avoid the per-notification Binder IPC into
        // NotificationManagerService.

        val category =
            when (update.trigger) {
                NotificationTriggerFfi.NEW_MESSAGE -> NotificationCompat.CATEGORY_MESSAGE
                NotificationTriggerFfi.GROUP_INVITE -> NotificationCompat.CATEGORY_EVENT
            }
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_darkmatter)
                .setContentIntent(conversationPendingIntent(update, content.notificationTag))
                .setCategory(category)
                .setWhen(update.timestampMs)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setSilent(false)

        when (update.trigger) {
            // Messages stack into one per-conversation card; invites are
            // one-off events, so keep them as a plain expandable notification.
            NotificationTriggerFfi.NEW_MESSAGE -> builder.setStyle(messagingStyle(update, content, conversationTitleOverride))
            NotificationTriggerFfi.GROUP_INVITE ->
                builder
                    .setContentTitle(content.title)
                    .setContentText(content.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
        }

        NotificationManagerCompat.from(context).notify(content.notificationTag, content.notificationId, builder.build())
        notificationDebug {
            // Never log the title/body — they carry sender / group names (PII).
            "posted tag=${content.notificationTag.take(16)} trigger=${update.trigger} group=${update.groupIdHex.take(8)}"
        }
        return true
    }

    // Accumulate every message from a conversation into one card. Android keys a
    // notification by (tag, id); reusing the per-conversation tag updates the
    // existing card, and MessagingStyle appends the new line to the previous
    // ones it carried — so five messages read as one entry, not five alerts.
    private fun messagingStyle(
        update: NotificationUpdateFfi,
        content: LocalNotificationContent,
        conversationTitleOverride: String?,
    ): NotificationCompat.MessagingStyle {
        val self =
            Person
                .Builder()
                .setName(content.selfName)
                .setKey(content.selfKey)
                .build()
        val style = existingMessagingStyle(content.notificationTag) ?: NotificationCompat.MessagingStyle(self)
        style.isGroupConversation = content.isGroupConversation
        // Prefer the caller-resolved title (chat-list parity, e.g. "Group of N
        // people" for unnamed groups) over the often-empty payload group name.
        (conversationTitleOverride?.takeIf { it.isNotBlank() } ?: content.conversationTitle)?.let { style.conversationTitle = it }
        val sender =
            Person
                .Builder()
                .setName(content.senderName)
                .setKey(content.senderKey)
                .build()
        style.addMessage(content.body, update.timestampMs, sender)
        return style
    }

    private fun existingMessagingStyle(tag: String): NotificationCompat.MessagingStyle? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        val existing =
            runCatching { manager.activeNotifications }
                .getOrNull()
                ?.firstOrNull { it.tag == tag && it.id == MESSAGE_NOTIFICATION_ID }
                ?: return null
        return NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(existing.notification)
    }

    private fun conversationPendingIntent(
        update: NotificationUpdateFfi,
        tag: String,
    ): PendingIntent {
        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Key the tap target on the notification tag (per-conversation
                // for messages) so the accumulating card always reopens the
                // same conversation. PendingIntents compare by URI, not extras.
                NotificationNavigation.fromUpdate(update)?.let { target ->
                    NotificationNavigation.applyToIntent(this, target, tag)
                }
            }
        return PendingIntent.getActivity(
            context,
            NotificationNavigation.requestCode(tag),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "darkmatter.messages.v2"

        // Per-conversation cards share id 0; the per-conversation tag keeps them
        // distinct, so reusing (tag, 0) updates the right conversation's card.
        private const val MESSAGE_NOTIFICATION_ID = 0
    }
}

private inline fun notificationDebug(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i("DMLocalNotify", message())
}
