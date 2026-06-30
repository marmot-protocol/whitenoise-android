package dev.ipf.whitenoise.android.core

object RecentEmojiList {
    const val QuickLimit = 6
    const val StoredLimit = 24

    val DefaultQuickChoices = listOf("❤️", "👍", "👎", "😂", "😮", "😢")

    fun recordPicked(
        existing: List<String>,
        picked: String,
        limit: Int = StoredLimit,
    ): List<String> {
        val normalized = picked.trim()
        val source = if (normalized.isEmpty()) existing else listOf(normalized) + existing
        return source
            .filter { it.isNotBlank() }
            .distinctBy { emojiIdentity(it) }
            .take(limit.coerceAtLeast(0))
    }

    fun quickChoices(
        recent: List<String>,
        defaults: List<String> = DefaultQuickChoices,
        limit: Int = QuickLimit,
    ): List<String> = normalizeQuickChoices(recent + defaults, defaults, limit)

    fun normalizeQuickChoices(
        choices: List<String>,
        defaults: List<String> = DefaultQuickChoices,
        limit: Int = QuickLimit,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return (choices + defaults)
            .filter { it.isNotBlank() }
            .distinctBy { emojiIdentity(it) }
            .take(limit)
    }

    // Dedup key that ignores variation selectors, so the same glyph stored with
    // and without U+FE0F (e.g. ❤️ vs ❤) collapses to one entry.
    private fun emojiIdentity(emoji: String): String = emoji.filterNot { it == '\uFE0F' || it == '\uFE0E' }
}
