package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import dev.ipf.whitenoise.android.R

/** Update only owned child labels; never recreate IDs or modify user-controlled alert behavior. */
internal fun redactNotificationChannelNames(context: Context): Boolean =
    runCatching {
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        val description = context.getString(R.string.notification_hidden_content)
        manager.notificationChannels.forEach { channel ->
            val parent =
                NotificationChannelSpec.entries.firstOrNull {
                    channel.id.startsWith("${it.id}:conv:$CONVERSATION_SHORTCUT_PREFIX")
                }
            if (parent != null || channel.id.startsWith(ProfileNotificationChannels.PREFIX)) {
                val name =
                    parent?.let { NotificationChannels.baseName(context, it) }
                        ?: context.getString(R.string.notifications)
                if (channel.name.toString() != name || channel.description != description) {
                    channel.name = name
                    channel.description = description
                    manager.createNotificationChannel(channel)
                }
            }
        }
        true
    }.getOrDefault(false)
