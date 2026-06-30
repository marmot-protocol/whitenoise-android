package dev.ipf.whitenoise.android.ui

import android.content.Context
import dev.ipf.whitenoise.android.core.RecentEmojiList

object RecentEmojiPreferences {
    private const val PreferencesName = "whitenoise_ui"
    private const val RecentReactionEmojiKey = "recent_reaction_emojis"
    private const val QuickReactionEmojiKey = "quick_reaction_emojis"
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

    fun loadQuickReactions(context: Context): List<String> =
        context
            .preferences()
            .getString(QuickReactionEmojiKey, null)
            ?.split(Separator)
            ?.let { RecentEmojiList.normalizeQuickChoices(it) }
            ?: RecentEmojiList.DefaultQuickChoices

    fun saveQuickReactions(
        context: Context,
        choices: List<String>,
    ): List<String> {
        val updated = RecentEmojiList.normalizeQuickChoices(choices)
        context
            .preferences()
            .edit()
            .putString(QuickReactionEmojiKey, updated.joinToString(Separator))
            .apply()
        return updated
    }

    fun resetQuickReactions(context: Context): List<String> = saveQuickReactions(context, RecentEmojiList.DefaultQuickChoices)

    private fun Context.preferences() = applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
}
