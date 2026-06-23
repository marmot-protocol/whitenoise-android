package dev.ipf.darkmatter.ui

import android.content.Context
import org.json.JSONArray

data class EmojiEntry(
    val emoji: String,
    val name: String,
    val group: Int,
    val keywords: List<String>,
)

// Full emoji set bundled at assets/emoji.json (generated from the `emojis` crate,
// the same source darkmatter-desktop uses). Parsed once and cached.
object EmojiData {
    const val GroupCount = 9

    // One representative glyph per category, in dataset group order, for the tab row.
    val groupTabIcons = listOf("😀", "🧑", "🐻", "🍎", "⚽", "🚗", "💡", "🔣", "🏁")

    @Volatile private var cache: List<EmojiEntry>? = null

    fun load(context: Context): List<EmojiEntry> {
        cache?.let { return it }
        val parsed =
            runCatching {
                val text = context.applicationContext.assets.open("emoji.json").bufferedReader().use { it.readText() }
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

    // Name + shortcode substring match — mirrors the desktop client's emoji search.
    fun search(
        all: List<EmojiEntry>,
        query: String,
    ): List<EmojiEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { entry -> entry.name.contains(q) || entry.keywords.any { it.contains(q) } }
    }
}
