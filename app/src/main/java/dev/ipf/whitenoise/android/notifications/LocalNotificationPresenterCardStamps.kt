package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.ipf.whitenoise.android.R

internal fun LocalNotificationPresenter.stampConversationCardMessageId(
    builder: NotificationCompat.Builder,
    messageIdHex: String?,
) {
    val generationId = messageIdHex?.takeIf(String::isNotBlank) ?: return
    builder.addExtras(
        Bundle().apply {
            putString(LocalNotificationFormatter.EXTRA_CONVERSATION_CARD_MESSAGE_ID_HEX, generationId)
        },
    )
}

internal fun LocalNotificationPresenter.stampPresentationTime(
    builder: NotificationCompat.Builder,
    channelId: String,
    category: String,
    presentationTimestampMs: Long,
) {
    builder.setWhen(presentationTimestampMs)
    builder.setPublicVersion(redactedPublicVersion(channelId, category, presentationTimestampMs))
}

/**
 * Shown in place of the real card whenever the lockscreen redacts private
 * notifications. The OS can auto-generate one, but that behaviour varies by
 * OEM; supplying our own guarantees no sender, body, or group name ever
 * reaches the lockscreen — it carries only the app name and the generic
 * hidden-content message.
 */
private fun LocalNotificationPresenter.redactedPublicVersion(
    channelId: String,
    category: String,
    presentationTimestampMs: Long,
): Notification =
    NotificationCompat
        .Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_stat_whitenoise)
        .setContentTitle(context.getString(R.string.app_name))
        // A body line even in the redacted variant — without it the shade
        // shows a bare icon+header shell when the OS hides sensitive
        // content, which reads as a broken notification.
        .setContentText(context.getString(R.string.notification_hidden_content))
        .setCategory(category)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setWhen(presentationTimestampMs)
        .build()
