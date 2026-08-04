package dev.ipf.whitenoise.android.notifications

import android.content.Context
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences

internal const val MAX_NOTIFICATION_QUICK_REACTIONS = 2

internal fun notificationQuickReactionChoices(context: Context): List<String> =
    RecentEmojiPreferences
        .loadQuickReactions(context)
        .mapNotNull(::normalizeNotificationReaction)
        .distinct()
        .take(MAX_NOTIFICATION_QUICK_REACTIONS)
