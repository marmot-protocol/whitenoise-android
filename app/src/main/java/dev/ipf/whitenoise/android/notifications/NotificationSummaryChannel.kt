package dev.ipf.whitenoise.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/** Check both user-reachable OS blocking controls before taking any shared write slot. */
internal fun isNotificationSummaryChannelBlocked(
    manager: NotificationManager,
    channel: NotificationChannel?,
): Boolean {
    if (channel == null) return false
    return channel.importance == NotificationManager.IMPORTANCE_NONE ||
        (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                channel.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } == true
        )
}
