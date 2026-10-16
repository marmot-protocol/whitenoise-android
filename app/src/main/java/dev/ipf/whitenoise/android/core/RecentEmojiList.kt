package dev.ipf.whitenoise.android.core

object RecentEmojiList {
    /** Quick reactions a fresh install or Reset offers, and the floor a saved list is padded to. */
    const val QuickLimit = 6

    /** The most quick reactions a user can configure: the six standard slots plus two optional ones (#3279). */
    const val QuickMaximum = 8
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

    /** Exactly [limit] choices, preferring [recent] and filling from [defaults]. */
    fun quickChoices(
        recent: List<String>,
        defaults: List<String> = DefaultQuickChoices,
        limit: Int = QuickLimit,
    ): List<String> = normalizeQuickChoices(recent + defaults, defaults, minimum = limit, maximum = limit)

    /**
     * A saved quick-reaction list in its configured order: blanks and duplicates dropped, at most [maximum]
     * kept, and padded from [defaults] up to [minimum] so a short or older list still offers the standard six.
     */
    fun normalizeQuickChoices(
        choices: List<String>,
        defaults: List<String> = DefaultQuickChoices,
        minimum: Int = QuickLimit,
        maximum: Int = QuickMaximum,
    ): List<String> {
        if (maximum <= 0) return emptyList()
        val chosen = choices.distinctEmoji().take(maximum)
        return when {
            chosen.size >= minimum -> chosen
            else -> (chosen + defaults).distinctEmoji().take(minimum.coerceAtMost(maximum))
        }
    }

    /** Puts [emoji] in quick-reaction [slot], appending it when [slot] is the next free optional slot. */
    fun withQuickChoiceAt(
        choices: List<String>,
        slot: Int,
        emoji: String,
    ): List<String> =
        when {
            slot in choices.indices -> choices.mapIndexed { index, current -> if (index == slot) emoji else current }
            slot == choices.size && slot < QuickMaximum -> choices + emoji
            else -> choices
        }

    /** Removes optional quick-reaction [slot]; the first [QuickLimit] slots can be replaced but not removed. */
    fun withoutQuickChoiceAt(
        choices: List<String>,
        slot: Int,
    ): List<String> =
        if (slot >= QuickLimit && slot in choices.indices) {
            choices.filterIndexed { index, _ -> index != slot }
        } else {
            choices
        }

    /** Non-blank entries with variation-selector duplicates removed, in order. */
    private fun List<String>.distinctEmoji(): List<String> = filter { it.isNotBlank() }.distinctBy { emojiIdentity(it) }

    // Dedup key that ignores variation selectors, so the same glyph stored with
    // and without U+FE0F (e.g. ❤️ vs ❤) collapses to one entry.
    private fun emojiIdentity(emoji: String): String = emoji.filterNot { it == '\uFE0F' || it == '\uFE0E' }
}
