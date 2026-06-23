package dev.ipf.darkmatter.ui

import org.json.JSONArray
import java.util.Locale

internal data class EmojiSearchEntry(
    val emoji: String,
    val name: String,
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

private data class RankedEmojiSearchEntry(
    val rank: Int,
    val sourceIndex: Int,
    val entry: EmojiSearchEntry,
)

internal class EmojiSearchIndex private constructor(
    private val entries: List<EmojiSearchEntry>,
) {
    fun search(
        query: String,
        limit: Int = DefaultSearchResultLimit,
    ): List<EmojiSearchEntry> {
        if (limit <= 0) return emptyList()
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) return emptyList()
        return entries
            .asSequence()
            .mapIndexedNotNull { sourceIndex, entry ->
                entry.matchRank(normalizedQuery)?.let { rank ->
                    RankedEmojiSearchEntry(
                        rank = rank,
                        sourceIndex = sourceIndex,
                        entry = entry,
                    )
                }
            }.sortedWith(
                compareBy<RankedEmojiSearchEntry> { it.rank }.thenBy { it.sourceIndex },
            ).take(limit)
            .map { it.entry }
            .toList()
    }

    companion object {
        private const val DefaultSearchResultLimit = 96

        fun fromJson(json: String): EmojiSearchIndex {
            val array = JSONArray(json)
            val entries = LinkedHashMap<String, EmojiSearchEntry>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val emoji = item.optString("emoji").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString("name").trim()
                val keywordArray = item.optJSONArray("keywords")
                val keywords =
                    buildList {
                        if (keywordArray != null) {
                            for (keywordIndex in 0 until keywordArray.length()) {
                                val keyword = keywordArray.optString(keywordIndex).trim()
                                if (keyword.isNotBlank()) add(keyword)
                            }
                        }
                    }.distinct()
                if (name.isBlank() && keywords.isEmpty()) continue
                entries.putIfAbsent(emoji, EmojiSearchEntry(emoji = emoji, name = name, keywords = keywords))
            }
            return EmojiSearchIndex(entries.values.toList())
        }
    }
}
