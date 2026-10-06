package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.ipf.whitenoise.android.R

/** One bounded channel family per local account and author, independent of conversation membership. */
internal class ProfileNotificationChannels(
    context: Context,
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    /** Message/view paths may refresh an existing label, but can never materialize a new channel. */
    fun existing(
        accountRef: String,
        author: String,
        pattern: ConversationVibrationPattern,
        title: String?,
        redact: Boolean = false,
    ): String? =
        synchronized(UserEventNotificationGroup.mutationLock) {
            val id = id(accountRef, author, pattern) ?: return@synchronized null
            val channel = manager?.getNotificationChannel(id) ?: return@synchronized null
            refreshLabel(channel, title, redact)
            id
        }

    /** Refresh at most four existing waveform versions; profile updates never create a channel. */
    fun refreshExisting(
        accountRef: String,
        author: String,
        title: String?,
        isCurrent: () -> Boolean = { true },
    ) {
        synchronized(UserEventNotificationGroup.mutationLock) {
            if (!isCurrent()) return
            ConversationVibrationPattern.entries.forEach { pattern -> existing(accountRef, author, pattern, title) }
        }
    }

    /** Explicit customization is the only creator; dormant versions keep every Android-owned setting. */
    fun customize(
        accountRef: String,
        author: String,
        title: String,
        pattern: ConversationVibrationPattern,
        previous: ConversationVibrationPattern,
    ): String =
        synchronized(UserEventNotificationGroup.mutationLock) {
            val platform = checkNotNull(manager)
            val id = requireNotNull(id(accountRef, author, pattern))
            val existing = platform.getNotificationChannel(id)
            if (existing != null) {
                refreshLabel(existing, title, false)
            } else {
                val parent = checkNotNull(platform.getNotificationChannel(NotificationChannelSpec.DIRECT_MESSAGES.id))
                val source = platform.getNotificationChannel(id(accountRef, author, previous)) ?: parent
                val created =
                    NotificationChannel(id, label(title, false), source.importance).apply {
                        description = context.getString(R.string.profile_notification_scope_detail)
                        setShowBadge(source.canShowBadge())
                        setSound(source.sound, source.audioAttributes)
                        enableLights(source.shouldShowLights())
                        lightColor = source.lightColor
                        setAllowBubbles(source.canBubble())
                        lockscreenVisibility = source.lockscreenVisibility
                        if (source.canBypassDnd()) runCatching { setBypassDnd(true) }
                        if (pattern == ConversationVibrationPattern.SYSTEM_DEFAULT) {
                            enableVibration(parent.shouldVibrate())
                            vibrationPattern = parent.vibrationPattern
                        } else {
                            applyConversationVibration(this, pattern)
                        }
                    }
                platform.createNotificationChannel(created)
            }
            id
        }

    /** Reads actual OS vibration for an honest summary after returning from Android settings. */
    fun effectiveVibration(
        account: String,
        author: String,
        pattern: ConversationVibrationPattern,
    ): EffectiveConversationVibration {
        val channel =
            id(account, author, pattern)?.let { manager?.getNotificationChannel(it) }
                ?: return EffectiveConversationVibration(pattern, true, false)
        val recognized =
            ConversationVibrationPattern.entries.firstOrNull {
                it.waveform?.contentEquals(channel.vibrationPattern) == true
            }
        return EffectiveConversationVibration(
            pattern = recognized ?: pattern.takeIf { it == ConversationVibrationPattern.SYSTEM_DEFAULT },
            enabled = channel.shouldVibrate(),
            overriddenByAndroid =
                !channel.shouldVibrate() ||
                    (pattern != ConversationVibrationPattern.SYSTEM_DEFAULT && recognized != pattern),
        )
    }

    /** Only mutable presentation fields are republished; importance, sound and DND remain OS-owned. */
    private fun refreshLabel(
        channel: NotificationChannel,
        title: String?,
        redact: Boolean,
    ) {
        val name = label(title, redact)
        val description = context.getString(R.string.profile_notification_scope_detail)
        if (channel.name.toString() != name || channel.description != description) {
            channel.name = name
            channel.description = description
            manager?.createNotificationChannel(channel)
        }
    }

    /** Privacy and name writes share the same platform lock as preview cleanup. */
    private fun label(
        title: String?,
        redact: Boolean,
    ): String =
        if (redact || !NotificationPreviewPreferences.enabled(context) || title.isNullOrBlank()) {
            context.getString(R.string.notifications)
        } else {
            context.getString(R.string.profile_notification_channel_name, title.trim().take(MAX_NAME_LENGTH))
        }

    companion object {
        const val PREFIX = "profile_messages_v1:"
        private const val MAX_NAME_LENGTH = 80

        /** Four deterministic versions at most; a new group never creates another profile channel. */
        fun id(
            account: String,
            author: String,
            pattern: ConversationVibrationPattern,
        ): String? {
            val key = ProfileNotificationOverridePreferences.key(account, author) ?: return null
            return "$PREFIX$key:${pattern.channelToken}"
        }
    }
}
