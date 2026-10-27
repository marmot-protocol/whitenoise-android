package dev.ipf.whitenoise.android.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Returns a card posted by the earlier expanded-choice build to Android's standard conversation template.
 *
 * That build replaced the expanded card with its own image-choice layout, which hid Reply and Mark as
 * read, and stamped every conversation card with a copy of its routing target. A rewrite drops both,
 * so the card keeps its messages, actions, reply history and alert behavior. Any other custom view, such as the
 * received-reaction body, is returned unchanged. Image choices still showing on an old card keep
 * reacting through the receiver's direct-value path and its current allowlist.
 */
internal fun withoutLegacyReactionRail(
    context: Context,
    notification: Notification,
): Notification {
    val extras = notification.extras
    val rail = extras.getBoolean(LEGACY_EXTRA_EXPANDED_REACTION_CHOICES)
    if (!rail && !extras.containsKey(LEGACY_EXTRA_FIXED_REACTION_TARGET)) return notification
    val standard =
        if (rail) {
            // The recovered builder resets group alert behavior to ALL, so a child card would alert for its group.
            NotificationCompat
                .Builder(context, notification)
                .setGroupAlertBehavior(notification.groupAlertBehavior)
                .setCustomBigContentView(null)
                .setRemoteInputHistory(extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY))
                .build()
                .withoutApi30RemoteInputHistoryItems()
        } else {
            notification.clone()
        }
    return standard.apply {
        this.extras.remove(LEGACY_EXTRA_EXPANDED_REACTION_CHOICES)
        this.extras.remove(LEGACY_EXTRA_FIXED_REACTION_TARGET)
    }
}

/** Marks a card whose expanded view is the earlier build's image-choice layout. */
internal const val LEGACY_EXTRA_EXPANDED_REACTION_CHOICES =
    "dev.ipf.whitenoise.android.notification.EXPANDED_REACTION_CHOICES"

/** The earlier build's copy of a card's routing target, read only by its image choices. */
internal const val LEGACY_EXTRA_FIXED_REACTION_TARGET = "dev.ipf.whitenoise.android.notification.FIXED_REACTION_TARGET"
