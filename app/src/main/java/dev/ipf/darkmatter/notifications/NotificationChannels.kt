package dev.ipf.darkmatter.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.ipf.darkmatter.R

/**
 * Creates and maintains the per-type notification channels.
 *
 * Each [NotificationChannelSpec] becomes one OS channel so the user gets native
 * per-type controls (sound, vibration, importance, badge, lockscreen visibility,
 * DND bypass) from the system notification details — no in-app duplication of
 * those toggles. Muting a type is just setting its OS channel to "None".
 *
 * Android won't let an app rename or re-key a channel, so to carry a user's
 * existing sound / vibration / importance choices across the split from the
 * legacy single channel ([NotificationChannelSpec.LEGACY_MESSAGES_CHANNEL_ID])
 * its role is taken over by [NotificationChannelSpec.DIRECT_MESSAGES] and the
 * user-mutable fields are copied onto it before the old channel is deleted.
 */
object NotificationChannels {
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Snapshot the legacy channel's *current* (possibly user-overridden)
        // settings before we create/delete anything, so the migration onto
        // messages_dm preserves the user's choices rather than resetting to our
        // defaults.
        val legacy = manager.getNotificationChannel(NotificationChannelSpec.LEGACY_MESSAGES_CHANNEL_ID)
        NotificationChannelSpec.entries.forEach { spec ->
            // For direct messages, inherit the legacy channel's user-chosen
            // importance at construction time (importance is final once a
            // channel is built, so it cannot be copied afterwards).
            val migrateLegacy = spec == NotificationChannelSpec.DIRECT_MESSAGES && legacy != null
            val importanceOverride = if (migrateLegacy) legacy!!.importance else null
            val channel = buildChannel(context, spec, importanceOverride)
            if (migrateLegacy) {
                copyUserSettings(from = legacy!!, to = channel)
            }
            manager.createNotificationChannel(channel)
        }
        // Retire channels replaced above. Safe to call repeatedly: deleting a
        // missing channel is a no-op. Done last, after the replacements exist,
        // so a partial run never leaves the user without a channel. The reactions
        // and invites channels are re-keyed (not migrated) because their
        // importance was raised and a live channel's importance can't change.
        manager.deleteNotificationChannel(NotificationChannelSpec.LEGACY_MESSAGES_CHANNEL_ID)
        manager.deleteNotificationChannel(NotificationChannelSpec.LEGACY_REACTIONS_CHANNEL_ID)
        manager.deleteNotificationChannel(NotificationChannelSpec.LEGACY_INVITES_CHANNEL_ID)
    }

    /**
     * Carry the user-mutable settings the user may have customised on the legacy
     * single channel onto the new direct-messages channel, so the migration does
     * not silently reset a custom sound / vibration pattern / importance.
     *
     * Identity fields (id, name, description) stay as the new channel's; only
     * fields the OS lets a user change are copied. Importance is handled at
     * construction (it is final once the channel is built) — see [ensureChannels].
     */
    private fun copyUserSettings(
        from: NotificationChannel,
        to: NotificationChannel,
    ) {
        to.setSound(from.sound, from.audioAttributes)
        to.enableVibration(from.shouldVibrate())
        from.vibrationPattern?.let { to.vibrationPattern = it }
        to.enableLights(from.shouldShowLights())
        to.lightColor = from.lightColor
        to.lockscreenVisibility = from.lockscreenVisibility
        to.setShowBadge(from.canShowBadge())
        to.setBypassDnd(from.canBypassDnd())
        from.group?.let { to.group = it }
    }

    private fun buildChannel(
        context: Context,
        spec: NotificationChannelSpec,
        importanceOverride: Int? = null,
    ): NotificationChannel =
        NotificationChannel(
            spec.id,
            context.getString(spec.nameRes()),
            importanceOverride ?: spec.importance.toAndroidImportance(),
        ).apply {
            description = context.getString(spec.descriptionRes())
            // Every channel stays private on the lockscreen so a redacted public
            // version is shown instead of the body. Only the message channels opt
            // into explicit vibration to match the legacy single channel.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            when (spec) {
                NotificationChannelSpec.DIRECT_MESSAGES,
                NotificationChannelSpec.GROUP_MESSAGES,
                -> {
                    enableVibration(true)
                    // Single ~150ms pulse instead of the OS-default double-buzz:
                    // a chat message is one event, not two. Applies only to
                    // fresh installs and legacy-channel migrants who did not
                    // customise vibration (copyUserSettings preserves any
                    // user-set vibrationPattern); Android freezes channel
                    // settings after creation, so existing users keep whatever
                    // they have. The leading 0 is the wait-before-buzz, 150 is
                    // the buzz length in ms — see #449.
                    vibrationPattern = longArrayOf(0L, 150L)
                }

                NotificationChannelSpec.REACTIONS,
                NotificationChannelSpec.INVITES,
                NotificationChannelSpec.APP_UPDATES,
                -> Unit
            }
        }

    private fun NotificationChannelSpec.nameRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites
            NotificationChannelSpec.APP_UPDATES -> R.string.notification_channel_app_updates
        }

    private fun NotificationChannelSpec.descriptionRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages_description
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages_description
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions_description
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites_description
            NotificationChannelSpec.APP_UPDATES -> R.string.notification_channel_app_updates_description
        }

    private fun ChannelImportance.toAndroidImportance(): Int =
        when (this) {
            ChannelImportance.HIGH -> NotificationManager.IMPORTANCE_HIGH
            ChannelImportance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
            ChannelImportance.LOW -> NotificationManager.IMPORTANCE_LOW
        }
}
