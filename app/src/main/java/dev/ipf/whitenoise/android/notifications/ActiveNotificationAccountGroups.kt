package dev.ipf.whitenoise.android.notifications

import android.app.NotificationManager
import android.content.Context

/**
 * The groups of this app's active cards that name [accountRef] in their dismissal metadata. An account that was
 * revoked elsewhere may no longer list every group it had, and a card still showing one of them must come down too.
 * An unreadable tray yields no ids.
 */
internal fun activeNotificationGroupIds(
    context: Context,
    accountRef: String,
): Set<String> {
    val manager = context.getSystemService(NotificationManager::class.java)
    val active = manager?.let { runCatching { it.activeNotifications }.getOrNull() }.orEmpty()
    return active.mapNotNullTo(mutableSetOf()) { card ->
        val extras = card.notification?.extras
        val owner = extras?.getString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF)
        val groupIdHex = extras?.getString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID)
        groupIdHex?.takeIf { owner == accountRef && it.isNotBlank() }
    }
}
