package dev.ipf.whitenoise.android.ui.group

import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec

internal data class ConversationAlertSetting(
    val channel: NotificationChannelSpec,
    val enabled: Boolean,
    val blockedByAndroid: Boolean = false,
    val pausedByMute: Boolean = false,
)
