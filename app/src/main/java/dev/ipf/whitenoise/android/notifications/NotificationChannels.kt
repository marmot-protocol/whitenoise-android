package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.ipf.whitenoise.android.R

/**
 * Creates and maintains the per-type notification channels.
 *
 * Each [NotificationChannelSpec] becomes one OS channel so the user gets native
 * per-type controls (sound, vibration, importance, badge, lockscreen visibility,
 * DND bypass) from the system notification details — no in-app duplication of
 * those toggles. Muting a type is just setting its OS channel to "None".
 *
 * Android won't let an app rename or re-key a channel, so channel IDs are kept
 * stable once published.
 */
object NotificationChannels {
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        NotificationChannelSpec.entries.forEach { spec ->
            manager.createNotificationChannel(buildChannel(context, spec))
        }
    }

    private fun buildChannel(
        context: Context,
        spec: NotificationChannelSpec,
    ): NotificationChannel =
        NotificationChannel(
            spec.id,
            context.getString(spec.nameRes()),
            spec.importance.toAndroidImportance(),
        ).apply {
            description = context.getString(spec.descriptionRes())
            // Every channel stays private on the lockscreen so a redacted public
            // version is shown instead of the body. Only the message channels opt
            // into explicit vibration for a short single-pulse chat alert.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            when (spec) {
                NotificationChannelSpec.DIRECT_MESSAGES,
                NotificationChannelSpec.GROUP_MESSAGES,
                NotificationChannelSpec.MENTIONS,
                -> {
                    enableVibration(true)
                    // Single ~150ms pulse instead of the OS-default double-buzz:
                    // a chat message is one event, not two. Applies only to
                    // fresh installs. Android freezes channel settings after
                    // creation, so existing users keep whatever they have. The
                    // leading 0 is the wait-before-buzz, 150 is the buzz length
                    // in ms — see #449.
                    vibrationPattern = longArrayOf(0L, 150L)
                }

                NotificationChannelSpec.REACTIONS,
                NotificationChannelSpec.INVITES,
                -> Unit
            }
        }

    private fun NotificationChannelSpec.nameRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages
            NotificationChannelSpec.MENTIONS -> R.string.notification_channel_mentions
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites
        }

    private fun NotificationChannelSpec.descriptionRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages_description
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages_description
            NotificationChannelSpec.MENTIONS -> R.string.notification_channel_mentions_description
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions_description
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites_description
        }

    private fun ChannelImportance.toAndroidImportance(): Int =
        when (this) {
            ChannelImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
            ChannelImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
            ChannelImportance.LOW -> NotificationManager.IMPORTANCE_LOW
        }
}
