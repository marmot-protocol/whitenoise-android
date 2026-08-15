package dev.ipf.whitenoise.android.notifications

import android.content.Context
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences

// The shade renders these as RemoteInput choice chips, which aren't action
// buttons and so aren't bound by the three-action-button cap.
internal const val MAX_NOTIFICATION_QUICK_REACTIONS = 6

internal fun notificationQuickReactionChoices(context: Context): List<String> =
    notificationQuickReactionChoices(RecentEmojiPreferences.loadQuickReactions(context))

internal fun notificationQuickReactionChoices(stored: List<String>): List<String> =
    stored
        .mapNotNull(::normalizeNotificationReaction)
        .distinct()
        .take(MAX_NOTIFICATION_QUICK_REACTIONS)
