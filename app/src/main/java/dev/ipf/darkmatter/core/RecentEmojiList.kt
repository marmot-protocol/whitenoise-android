package dev.ipf.darkmatter.core

object RecentEmojiList {
    const val QuickLimit = 5
    const val StoredLimit = 24

    val DefaultQuickChoices = listOf("👍", "❤️", "😂", "🎉", "😮")

    fun recordPicked(
        existing: List<String>,
        picked: String,
        limit: Int = StoredLimit,
    ): List<String> {
        val normalized = picked.trim()
        if (normalized.isEmpty() || limit <= 0) return existing.distinct().take(limit.coerceAtLeast(0))
        return (listOf(normalized) + existing)
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
    }

    fun quickChoices(
        recent: List<String>,
        defaults: List<String> = DefaultQuickChoices,
        limit: Int = QuickLimit,
    ): List<String> {
        if (limit <= 0) return emptyList()
        return (recent + defaults)
            .filter { it.isNotBlank() }
            .distinct()
            .take(limit)
    }
}
