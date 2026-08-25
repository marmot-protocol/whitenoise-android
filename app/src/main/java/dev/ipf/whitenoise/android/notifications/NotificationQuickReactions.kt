package dev.ipf.whitenoise.android.notifications

import android.content.Context
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences

// RemoteInput choices are rendered as inline choice chips rather than separate
// notification actions, so all six app quick reactions can remain available.
internal const val MAX_NOTIFICATION_QUICK_REACTIONS = 6

internal fun notificationQuickReactionChoices(context: Context): List<String> =
    notificationQuickReactionChoices(RecentEmojiPreferences.loadQuickReactions(context))

internal fun notificationQuickReactionChoices(stored: List<String>): List<String> =
    stored
        .mapNotNull(::normalizeNotificationReaction)
        .distinct()
        .take(MAX_NOTIFICATION_QUICK_REACTIONS)
