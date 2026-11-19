package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Set sections in the picker's browse and search layouts. */
class EmojiSetSectionsTest {
    private val grin = EmojiEntry("😀", "grinning face", 0, listOf("smile"))
    private val marmot = EmojiEntry(":marmot:", "marmot", 2, listOf("marmot"))
    private val wn = EmojiEntry(":wn:", "white noise", 7, listOf("white noise"))
    private val forest = InstalledEmojiSetTab("30030:author:forest", "Forest friends", ":fox:")

    /** Set emoji appear only in their own section, which sits between recents and the Unicode categories. */
    @Test
    fun browseShowsSetsBeforeCategories() {
        val sets =
            listOf(
                EmojiSection(BuiltinEmojiSet.Marmot, listOf(":marmot:")),
                EmojiSection(forest, emptyList()),
            )

        val sections = emojiBrowseSections(listOf(grin, marmot, wn), listOf("😀"), listOf(":mine:"), sets)

        assertEquals(
            listOf(EmojiCategory.Custom, EmojiCategory.Recent, BuiltinEmojiSet.Marmot, EmojiCategory.SmileysAndPeople),
            sections.map { it.category },
        )
        assertEquals(listOf("😀"), sections.last().emoji)
    }

    /** Pre-installed matches lead the results, grouped under their own set. */
    @Test
    fun searchPutsBuiltinSetsFirst() {
        val sections = emojiSearchSections(listOf(grin, marmot, wn))

        assertEquals(
            listOf(BuiltinEmojiSet.WhiteNoise, BuiltinEmojiSet.Marmot, EmojiCategory.SmileysAndPeople),
            sections.map { it.category },
        )
    }

    /** Search keeps the browse order, so installed sets follow the pre-installed ones. */
    @Test
    fun searchKeepsBrowseSetOrder() {
        val mine = EmojiSection(EmojiCategory.Custom, listOf(":mine:"))
        val installed = listOf(EmojiSection(forest, listOf(":fox:")))

        val sections = emojiSearchLayout(mine, emojiSearchSections(listOf(grin, marmot, wn)), installed)

        assertEquals(
            listOf(
                EmojiCategory.Custom,
                BuiltinEmojiSet.WhiteNoise,
                BuiltinEmojiSet.Marmot,
                forest,
                EmojiCategory.SmileysAndPeople,
            ),
            sections.map { it.category },
        )
    }

    /** Installed sets keep their own section in search, holding only matching shortcodes. */
    @Test
    fun installedSetSearchMatchesShortcodes() {
        val sets =
            listOf(
                EmojiSection(BuiltinEmojiSet.WhiteNoise, listOf(":wn:")),
                EmojiSection(forest, listOf(":fox:", ":owl:", ":foxglove:")),
            )

        assertEquals(
            listOf(EmojiSection(forest, listOf(":fox:", ":foxglove:"))),
            installedSetSearchSections(sets, ":FOX"),
        )
        assertTrue(installedSetSearchSections(sets, " ").isEmpty())
    }

    /** Every bundled shortcode belongs to exactly one pre-installed set. */
    @Test
    fun builtinSetsPartitionBundledShortcodes() {
        assertEquals(
            EmojiShortcodes.builtins.sorted(),
            BuiltinEmojiSet.entries.flatMap { it.shortcodes }.sorted(),
        )
        assertEquals(BuiltinEmojiSet.WhiteNoise, BuiltinEmojiSet.forShortcode(":wn:"))
        assertEquals(BuiltinEmojiSet.Marmot, BuiltinEmojiSet.forShortcode(":marmot_wave:"))
        assertNull(BuiltinEmojiSet.forShortcode(":fox:"))
    }

    /** Hiding a set only covers its own emoji. */
    @Test
    fun hiddenSetsCoverOnlyTheirEmoji() {
        val hidden = setOf(BuiltinEmojiSet.Marmot)

        assertTrue(hidden.hides(":marmot_yes:"))
        assertFalse(hidden.hides(":wn:"))
        assertFalse(hidden.hides("😀"))
    }
}
