package dev.ipf.whitenoise.android.ui

import android.content.Context
import dev.ipf.whitenoise.android.core.RecentEmojiList

object RecentEmojiPreferences {
    private const val PreferencesName = "whitenoise_ui"
    private const val RecentReactionEmojiKey = "recent_reaction_emojis"
    private const val Separator = "\n"

    fun load(context: Context): List<String> =
        context
            .preferences()
            .getString(RecentReactionEmojiKey, null)
            ?.split(Separator)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun recordPicked(
        context: Context,
        emoji: String,
    ): List<String> {
        val updated = RecentEmojiList.recordPicked(load(context), emoji)
        context
            .preferences()
            .edit()
            .putString(RecentReactionEmojiKey, updated.joinToString(Separator))
            .apply()
        return updated
    }

    private fun Context.preferences() = applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
}
