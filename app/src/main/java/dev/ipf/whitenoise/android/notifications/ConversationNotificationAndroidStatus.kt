package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** Read on an IO dispatcher; Android remains the authority for its channel controls. */
internal fun androidBlockedConversationCategories(
    context: Context,
    settings: List<ConversationNotificationCategorySetting>,
): Set<NotificationChannelSpec> {
    val manager = context.getSystemService(NotificationManager::class.java)
    val appBlocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
    return settings.filter { setting ->
        val channel = manager?.getNotificationChannel(setting.settingsTarget.channelId)
            ?: manager?.getNotificationChannel(setting.channel.id)
        val groupBlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            channel?.group?.let { manager?.getNotificationChannelGroup(it)?.isBlocked } == true
        } else false
        appBlocked || channel?.importance == NotificationManager.IMPORTANCE_NONE || groupBlocked
    }.map { it.channel }.toSet()
}
