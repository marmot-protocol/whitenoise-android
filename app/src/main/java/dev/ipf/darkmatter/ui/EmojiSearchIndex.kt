package dev.ipf.darkmatter.ui

import org.json.JSONArray
import java.util.Locale

internal data class EmojiSearchEntry(
    val emoji: String,
    val name: String,
    val keywords: List<String>,
) {
    private val searchableText =
        buildString {
            append(emoji)
            append(' ')
            append(name)
            keywords.forEach { keyword ->
                append(' ')
                append(keyword)
            }
        }.lowercase(Locale.ROOT)

    fun matches(normalizedQuery: String): Boolean = searchableText.contains(normalizedQuery)
}

internal class EmojiSearchIndex private constructor(
    private val entries: List<EmojiSearchEntry>,
) {
    fun search(
        query: String,
        limit: Int = DefaultSearchResultLimit,
    ): List<EmojiSearchEntry> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isEmpty()) return emptyList()
        return entries
            .asSequence()
            .filter { it.matches(normalizedQuery) }
            .take(limit)
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
