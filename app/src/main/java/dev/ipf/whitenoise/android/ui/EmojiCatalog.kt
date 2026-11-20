package dev.ipf.whitenoise.android.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import java.util.Locale

/** One picker tab and its grid section: a Unicode category, a pre-installed set or an installed set. */
internal sealed interface EmojiPickerTab {
    /** Stable key for lazy items and test tags. */
    val id: String
}

/** An emoji set the account installed, shown as its own section under its title. */
internal data class InstalledEmojiSetTab(
    val coordinate: String,
    val title: String,
    val iconEmoji: String,
) : EmojiPickerTab {
    override val id: String get() = "set.$coordinate"
}

/**
 * Picker sections in prototype order. Each category collects one or more groups of the bundled
 * dataset, so the sheet shows the prototype's eight tabs while the emoji come from `emoji.json`.
 */
@Suppress("MagicNumber")
internal enum class EmojiCategory(
    override val id: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val datasetGroups: List<Int>,
) : EmojiPickerTab {
    Custom("custom", R.string.emoji_category_custom, R.drawable.ic_image, emptyList()),
    Recent("recent", R.string.emoji_category_recent, R.drawable.ic_emoji_recent, emptyList()),
    SmileysAndPeople("smileys", R.string.emoji_category_smileys_people, R.drawable.ic_emoji_smileys, listOf(0, 1)),
    AnimalsAndNature("animals", R.string.emoji_category_animals_nature, R.drawable.ic_emoji_animals, listOf(2)),
    FoodAndDrink("food", R.string.emoji_category_food_drink, R.drawable.ic_emoji_food, listOf(3)),
    Activities("activities", R.string.emoji_category_activities, R.drawable.ic_emoji_activities, listOf(5)),
    TravelAndPlaces("travel", R.string.emoji_category_travel_places, R.drawable.ic_emoji_travel, listOf(4)),
    Objects("objects", R.string.emoji_category_objects, R.drawable.ic_emoji_objects, listOf(6)),
    Symbols("symbols", R.string.emoji_category_symbols, R.drawable.ic_emoji_symbols, listOf(7)),
    Flags("flags", R.string.emoji_category_flags, R.drawable.ic_emoji_flags, listOf(8)),
    ;

    companion object {
        /** The section a dataset group belongs to; unknown groups land with the symbols. */
        fun forDatasetGroup(group: Int): EmojiCategory = entries.firstOrNull { group in it.datasetGroups } ?: Symbols
    }
}

/** One header plus its emoji in the picker grid. */
internal data class EmojiSection(
    val category: EmojiPickerTab,
    val emoji: List<String>,
)

/**
 * Browse layout: the user's emoji and recents first when there are any, then one section per emoji set,
 * then every populated Unicode category. Set emoji appear only in their set's section.
 */
internal fun emojiBrowseSections(
    entries: List<EmojiEntry>,
    recents: List<String>,
    custom: List<String>,
    sets: List<EmojiSection> = emptyList(),
): List<EmojiSection> {
    val byCategory =
        entries
            .filterNot { EmojiShortcodes.isShortcode(it.emoji) }
            .groupBy { EmojiCategory.forDatasetGroup(it.group) }
    return buildList {
        if (custom.isNotEmpty()) add(EmojiSection(EmojiCategory.Custom, custom))
        if (recents.isNotEmpty()) add(EmojiSection(EmojiCategory.Recent, recents))
        addAll(sets.filter { it.emoji.isNotEmpty() })
        for (category in EmojiCategory.entries) {
            val emoji = byCategory[category].orEmpty()
            if (emoji.isNotEmpty()) add(EmojiSection(category, emoji.map { it.emoji }))
        }
    }
}

/** Search results keep their rank order inside each section: pre-installed sets first, then categories. */
internal fun emojiSearchSections(results: List<EmojiEntry>): List<EmojiSection> {
    val (shortcodes, unicode) = results.partition { BuiltinEmojiSet.forShortcode(it.emoji) != null }
    val sets =
        shortcodes
            .groupBy { requireNotNull(BuiltinEmojiSet.forShortcode(it.emoji)) }
            .entries
            .sortedBy { it.key.ordinal }
            .map { (set, matches) -> EmojiSection(set, matches.map { it.emoji }) }
    val categories =
        unicode
            .groupBy { EmojiCategory.forDatasetGroup(it.group) }
            .entries
            .sortedBy { it.key.ordinal }
            .map { (category, matches) -> EmojiSection(category, matches.map { it.emoji }) }
    return sets + categories
}

/** The user's own emoji whose shortcode contains the query, or null when none match. */
internal fun customEmojiSearchSection(
    custom: List<String>,
    query: String,
): EmojiSection? = shortcodeMatches(EmojiSection(EmojiCategory.Custom, custom), query)

/** Installed sets keep their own sections in search, holding only the shortcodes that contain the query. */
internal fun installedSetSearchSections(
    sets: List<EmojiSection>,
    query: String,
): List<EmojiSection> = sets.filter { it.category is InstalledEmojiSetTab }.mapNotNull { shortcodeMatches(it, query) }

/** Search uses the browse order: the user's own emoji, pre-installed sets, installed sets, then categories. */
internal fun emojiSearchLayout(
    custom: EmojiSection?,
    results: List<EmojiSection>,
    installed: List<EmojiSection>,
): List<EmojiSection> {
    val (builtin, categories) = results.partition { it.category is BuiltinEmojiSet }
    return listOfNotNull(custom) + builtin + installed + categories
}

/** [section] narrowed to shortcodes containing [query], or null when none match or the query is blank. */
private fun shortcodeMatches(
    section: EmojiSection,
    query: String,
): EmojiSection? {
    val needle = query.trim().trim(':').lowercase(Locale.ROOT)
    if (needle.isEmpty()) {
        return null
    }
    val matches = section.emoji.filter { it.trim(':').lowercase(Locale.ROOT).contains(needle) }
    return matches.takeIf { it.isNotEmpty() }?.let { section.copy(emoji = it) }
}
