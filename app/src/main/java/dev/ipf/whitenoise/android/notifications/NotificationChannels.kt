package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
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
    internal const val GLOBAL_DEFAULTS_GROUP_ID = "global_notification_defaults"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(
                GLOBAL_DEFAULTS_GROUP_ID,
                context.getString(R.string.notification_channel_group_global_defaults),
            ),
        )
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
            context.getString(spec.globalNameRes()),
            spec.importance.toAndroidImportance(),
        ).apply {
            group = GLOBAL_DEFAULTS_GROUP_ID
            description = context.getString(spec.globalDescriptionRes())
            // Every channel stays private on the lockscreen so a redacted public
            // version is shown instead of the body. Only the message channels opt
            // into explicit vibration for a short single-pulse chat alert.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            // A launcher dot is message-unread attention, not a generic signal
            // that any White Noise notification exists. Android freezes this
            // setting at first channel creation, so upgrades preserve every
            // existing/user-owned channel while fresh installs get the policy.
            setShowBadge(spec.launcherBadgeByDefault)
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
                NotificationChannelSpec.AGENT_ACTIVITY,
                NotificationChannelSpec.APP_UPDATES,
                -> Unit
            }
        }

    internal fun baseName(
        context: Context,
        spec: NotificationChannelSpec,
    ): String = context.getString(spec.baseNameRes())

    private fun NotificationChannelSpec.baseNameRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages
            NotificationChannelSpec.MENTIONS -> R.string.notification_channel_mentions
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites
            NotificationChannelSpec.AGENT_ACTIVITY -> R.string.notification_channel_agent_activity
            NotificationChannelSpec.APP_UPDATES -> R.string.notification_channel_app_updates
        }

    private fun NotificationChannelSpec.globalNameRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages_default
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages_default
            NotificationChannelSpec.MENTIONS -> R.string.notification_channel_mentions_default
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions_default
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites_default
            NotificationChannelSpec.AGENT_ACTIVITY -> R.string.notification_channel_agent_activity_default
            NotificationChannelSpec.APP_UPDATES -> R.string.notification_channel_app_updates_global
        }

    private fun NotificationChannelSpec.globalDescriptionRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages_default_description
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages_default_description
            NotificationChannelSpec.MENTIONS -> R.string.notification_channel_mentions_default_description
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions_default_description
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites_default_description
            NotificationChannelSpec.AGENT_ACTIVITY -> R.string.notification_channel_agent_activity_default_description
            NotificationChannelSpec.APP_UPDATES -> R.string.notification_channel_app_updates_global_description
        }

    private fun ChannelImportance.toAndroidImportance(): Int =
        when (this) {
            ChannelImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
            ChannelImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
            ChannelImportance.LOW -> NotificationManager.IMPORTANCE_LOW
        }
}
