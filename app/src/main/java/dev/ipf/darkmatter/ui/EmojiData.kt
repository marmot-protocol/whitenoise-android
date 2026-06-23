package dev.ipf.darkmatter.ui

import android.content.Context
import org.json.JSONArray
import java.util.Locale

data class EmojiEntry(
    val emoji: String,
    val name: String,
    val group: Int,
    val keywords: List<String>,
) {
    private val normalizedName = name.lowercase(Locale.ROOT)
    private val normalizedKeywords = keywords.map { it.lowercase(Locale.ROOT) }

    fun matchRank(normalizedQuery: String): Int? =
        when {
            normalizedName == normalizedQuery -> 0
            normalizedName.startsWith(normalizedQuery) -> 1
            normalizedKeywords.any { it == normalizedQuery } -> 2
            normalizedKeywords.any { it.startsWith(normalizedQuery) } -> 3
            normalizedName.contains(normalizedQuery) -> 4
            normalizedKeywords.any { it.contains(normalizedQuery) } -> 5
            else -> null
        }
}

// Single bundled emoji set at assets/emoji.json: categorized (group) for browsing
// and keyworded (name + CLDR annotations) for search. Parsed once and cached.
object EmojiData {
    const val GroupCount = 9
    private const val SearchResultLimit = 96

    // One representative glyph per category, in dataset group order, for the tab row.
    val groupTabIcons = listOf("😀", "🧑", "🐻", "🍎", "⚽", "🚗", "💡", "🔣", "🏁")

    @Volatile private var cache: List<EmojiEntry>? = null

    fun load(context: Context): List<EmojiEntry> {
        cache?.let { return it }
        val parsed =
            runCatching {
                val text =
                    context.applicationContext.assets
                        .open("emoji.json")
                        .bufferedReader()
                        .use { it.readText() }
                val array = JSONArray(text)
                buildList(array.length()) {
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val codes = obj.getJSONArray("k")
                        val keywords = buildList(codes.length()) { for (j in 0 until codes.length()) add(codes.getString(j)) }
                        add(EmojiEntry(obj.getString("e"), obj.getString("n"), obj.getInt("g"), keywords))
                    }
                }
            }.getOrDefault(emptyList())
        cache = parsed
        return parsed
    }

    // Ranked name/keyword match: exact and prefix hits sort ahead of substring
    // hits, ties broken by source order. Empty query returns nothing (the picker
    // shows the categorized browse grid instead).
    fun search(
        all: List<EmojiEntry>,
        query: String,
        limit: Int = SearchResultLimit,
    ): List<EmojiEntry> {
        if (limit <= 0) return emptyList()
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) return emptyList()
        return all
            .asSequence()
            .mapIndexedNotNull { sourceIndex, entry ->
                entry.matchRank(normalizedQuery)?.let { rank -> Triple(rank, sourceIndex, entry) }
            }.sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { it.third }
            .toList()
    }
}
