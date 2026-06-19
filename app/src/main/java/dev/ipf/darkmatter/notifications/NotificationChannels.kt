package dev.ipf.darkmatter.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.ipf.darkmatter.R

/**
 * Creates and maintains the per-type notification channels for #288.
 *
 * Each [NotificationChannelSpec] becomes one OS channel so the user gets native
 * per-type controls (sound, vibration, importance, badge, lockscreen visibility,
 * DND bypass) from the system notification details — no in-app duplication of
 * those toggles. Muting a type is just setting its OS channel to "None".
 *
 * Migration from the legacy single channel
 * ([NotificationChannelSpec.LEGACY_MESSAGES_CHANNEL_ID]): #288 requires the
 * user's existing sound / vibration / importance choices survive the split.
 * Android won't let an app rename or re-key a channel, so the only ways to carry
 * the settings forward are (a) reuse the old ID, or (b) copy the user-mutable
 * fields onto the replacement before deleting the old channel. We do (b): the
 * legacy channel's role is taken over by [NotificationChannelSpec.DIRECT_MESSAGES]
 * with its stable `messages_dm` ID, and any user overrides on the old channel
 * (importance, sound, vibration, lights, lockscreen visibility, badge, DND
 * bypass) are copied across before the old channel is deleted.
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
        // Retire the pre-#288 single channel. Safe to call repeatedly: deleting
        // a missing channel is a no-op. We do this last, after messages_dm has
        // been created with the migrated settings, so a partial run never leaves
        // the user with no message channel and never loses their settings.
        manager.deleteNotificationChannel(NotificationChannelSpec.LEGACY_MESSAGES_CHANNEL_ID)
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
            // Preserve the legacy message-channel behaviour for the message
            // channels (vibrate + private lockscreen); reactions/invites inherit
            // OS defaults for their importance and stay private on the lockscreen
            // so a redacted public version is always shown instead of the body.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            when (spec) {
                NotificationChannelSpec.DIRECT_MESSAGES,
                NotificationChannelSpec.GROUP_MESSAGES,
                -> enableVibration(true)

                NotificationChannelSpec.REACTIONS,
                NotificationChannelSpec.INVITES,
                -> Unit
            }
        }

    private fun NotificationChannelSpec.nameRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages
            NotificationChannelSpec.REACTIONS -> R.string.notification_channel_reactions
            NotificationChannelSpec.INVITES -> R.string.notification_channel_invites
        }

    private fun NotificationChannelSpec.descriptionRes(): Int =
        when (this) {
            NotificationChannelSpec.DIRECT_MESSAGES -> R.string.notification_channel_direct_messages_description
            NotificationChannelSpec.GROUP_MESSAGES -> R.string.notification_channel_group_messages_description
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
