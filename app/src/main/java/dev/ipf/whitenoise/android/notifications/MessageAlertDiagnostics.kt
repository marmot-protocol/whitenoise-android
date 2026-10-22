package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager

/** Which channel a card was written to, named by role rather than by its account- and chat-scoped id. */
internal enum class MessageAlertChannelKind(
    val label: String,
) {
    PARENT("parent"),
    CONVERSATION_CHILD("conversation_child"),
    PROFILE_CHILD("profile_child"),
    EXISTING_CARD("existing_card"),
}

/**
 * One alert decision as evidence for the media-interruption investigation (#1717).
 *
 * White Noise plays no alert sound of its own: the OS notification service plays whatever the selected
 * channel is configured to, which is why [ALERT_POLICY] is always `os_channel`. The line carries only the
 * route, the channel's role and its user-owned audio configuration, whether this write may alert, and
 * whether other media was playing — no content, account, chat or channel id, and no media metadata.
 */
internal data class MessageAlertDiagnostic(
    val route: NotificationChannelSpec,
    val channelKind: MessageAlertChannelKind,
    val silentWrite: Boolean,
    val channelImportance: Int?,
    val channelSound: Boolean?,
    val channelVibration: Boolean?,
    val otherMediaActive: Boolean?,
) {
    /** Renders the fixed-vocabulary log line; unknown values print as `unknown`. */
    fun line(): String =
        "alert_path route=${route.id} channel=${channelKind.label} policy=$ALERT_POLICY " +
            "silent_write=$silentWrite importance=${channelImportance ?: UNKNOWN} " +
            "channel_sound=${onOff(channelSound)} channel_vibration=${onOff(channelVibration)} " +
            "other_media=${otherMediaActive?.let { if (it) "active" else "idle" } ?: UNKNOWN}"

    companion object {
        /** The only alert policy this build has: the OS channel owns sound, vibration and focus. */
        const val ALERT_POLICY = "os_channel"
        private const val UNKNOWN = "unknown"

        /** Renders a nullable channel switch as `on`, `off` or `unknown`. */
        private fun onOff(value: Boolean?): String =
            when (value) {
                true -> "on"
                false -> "off"
                null -> UNKNOWN
            }
    }
}

/** Names the role of [channelId] for [route] without exposing the id itself. */
internal fun messageAlertChannelKind(
    route: NotificationChannelSpec,
    channelId: String,
    existingCardChannel: String?,
): MessageAlertChannelKind =
    when {
        existingCardChannel != null && existingCardChannel == channelId -> MessageAlertChannelKind.EXISTING_CARD
        channelId == route.id -> MessageAlertChannelKind.PARENT
        channelId.startsWith(ProfileNotificationChannels.PREFIX) -> MessageAlertChannelKind.PROFILE_CHILD
        else -> MessageAlertChannelKind.CONVERSATION_CHILD
    }

/**
 * Reads the selected channel's user-owned audio settings and whether other media is playing.
 * Called only from a debug log lambda, so release builds never make these system calls.
 */
internal fun messageAlertDiagnostic(
    context: Context,
    route: NotificationChannelSpec,
    channelId: String,
    existingCardChannel: String?,
    silentWrite: Boolean,
): MessageAlertDiagnostic {
    val channel =
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)
        }.getOrNull()
    val mediaActive =
        runCatching { context.getSystemService(AudioManager::class.java)?.isMusicActive }.getOrNull()
    return MessageAlertDiagnostic(
        route = route,
        channelKind = messageAlertChannelKind(route, channelId, existingCardChannel),
        silentWrite = silentWrite,
        channelImportance = channel?.importance,
        channelSound = channel?.let { it.sound != null },
        channelVibration = channel?.shouldVibrate(),
        otherMediaActive = mediaActive,
    )
}
