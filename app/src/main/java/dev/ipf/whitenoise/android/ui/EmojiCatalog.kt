package dev.ipf.whitenoise.android.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

/**
 * Picker sections in prototype order. Each category collects one or more groups of the bundled
 * dataset, so the sheet shows the prototype's eight tabs while the emoji come from `emoji.json`.
 */
@Suppress("MagicNumber")
internal enum class EmojiCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @DrawableRes val iconRes: Int,
    val datasetGroups: List<Int>,
) {
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
    val category: EmojiCategory,
    val emoji: List<String>,
)

/** Browse layout: recents first when there are any, then every populated category in [EmojiCategory] order. */
internal fun emojiBrowseSections(
    entries: List<EmojiEntry>,
    recents: List<String>,
): List<EmojiSection> {
    val byCategory = entries.groupBy { EmojiCategory.forDatasetGroup(it.group) }
    return buildList {
        if (recents.isNotEmpty()) add(EmojiSection(EmojiCategory.Recent, recents))
        for (category in EmojiCategory.entries) {
            val emoji = byCategory[category].orEmpty()
            if (emoji.isNotEmpty()) add(EmojiSection(category, emoji.map { it.emoji }))
        }
    }
}

/** Search results keep their rank order inside each category section. */
internal fun emojiSearchSections(results: List<EmojiEntry>): List<EmojiSection> =
    results
        .groupBy { EmojiCategory.forDatasetGroup(it.group) }
        .entries
        .sortedBy { it.key.ordinal }
        .map { (category, matches) -> EmojiSection(category, matches.map { it.emoji }) }
