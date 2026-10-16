package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentEmojiListTest {
    @Test
    fun pickedEmojiMovesToTheFrontWithoutDuplicates() {
        val recents =
            RecentEmojiList.recordPicked(
                existing = listOf("👍", "😂", "🎉"),
                picked = "😂",
            )

        assertEquals(listOf("😂", "👍", "🎉"), recents)
    }

    @Test
    fun repeatedPickKeepsNewestFirstOrder() {
        val first =
            RecentEmojiList.recordPicked(
                existing = listOf("👍", "😂"),
                picked = "🎉",
            )
        val second =
            RecentEmojiList.recordPicked(
                existing = first,
                picked = "👍",
            )

        assertEquals(listOf("👍", "🎉", "😂"), second)
    }

    @Test
    fun recordPickedCapsAtStoredLimit() {
        val existing = (1..RecentEmojiList.StoredLimit).map { "e$it" }
        val recents =
            RecentEmojiList.recordPicked(
                existing = existing,
                picked = "new",
            )

        assertEquals(RecentEmojiList.StoredLimit, recents.size)
        assertEquals("new", recents.first())
        assertEquals("e${RecentEmojiList.StoredLimit - 1}", recents.last())
    }

    @Test
    fun recordPickedDeduplicatesVariationSelectors() {
        val recents =
            RecentEmojiList.recordPicked(
                existing = listOf("❤️"),
                picked = "❤",
            )

        assertEquals(listOf("❤"), recents)
    }

    @Test
    fun quickChoicesPreferRecentsAndFillFromDefaults() {
        val choices =
            RecentEmojiList.quickChoices(
                recent = listOf("🔥", "👍"),
                defaults = listOf("👍", "❤️", "😂", "🎉", "😮", "😢"),
                limit = 6,
            )

        assertEquals(listOf("🔥", "👍", "❤️", "😂", "🎉", "😮"), choices)
    }

    @Test
    fun quickChoicesUseCommonDefaultsWhenNoRecentsExist() {
        assertEquals(
            listOf("❤️", "👍", "👎", "😂", "😮", "😢"),
            RecentEmojiList.quickChoices(recent = emptyList()),
        )
    }

    /** A short saved list is deduplicated and padded from the defaults to the standard six. */
    @Test
    fun normalizeQuickChoicesPadsAndDeduplicatesSavedChoices() {
        val choices =
            RecentEmojiList.normalizeQuickChoices(
                choices = listOf("🚀", "👍", "🚀"),
                defaults = listOf("👍", "❤️", "😂", "🎉", "😮", "😢"),
                minimum = 6,
            )

        assertEquals(listOf("🚀", "👍", "❤️", "😂", "🎉", "😮"), choices)
    }

    /** A customised six-choice list stays exactly six; the optional slots are never filled from defaults (#3279). */
    @Test
    fun normalizeQuickChoicesLeavesASixChoiceListUnchanged() {
        val six = listOf("🚀", "🔥", "🎉", "👀", "💯", "🙏")

        assertEquals(six, RecentEmojiList.normalizeQuickChoices(six))
        assertEquals(RecentEmojiList.DefaultQuickChoices, RecentEmojiList.normalizeQuickChoices(emptyList()))
    }

    /** Seven and eight configured choices survive in order, and anything past eight is dropped (#3279). */
    @Test
    fun normalizeQuickChoicesKeepsUpToEightChoicesInOrder() {
        val eight = listOf("🚀", "🔥", "🎉", "👀", "💯", "🙏", "🤝", "✅")

        assertEquals(eight.take(7), RecentEmojiList.normalizeQuickChoices(eight.take(7)))
        assertEquals(eight, RecentEmojiList.normalizeQuickChoices(eight))
        assertEquals(eight, RecentEmojiList.normalizeQuickChoices(eight + "🧪"))
        assertEquals(8, RecentEmojiList.QuickMaximum)
    }

    /** Picking for an existing slot replaces it; picking for the next free slot appends until eight (#3279). */
    @Test
    fun withQuickChoiceAtReplacesOrAppendsTheNextOptionalSlot() {
        val six = RecentEmojiList.DefaultQuickChoices
        val seven = six + "🚀"

        assertEquals(listOf("🔥") + six.drop(1), RecentEmojiList.withQuickChoiceAt(six, 0, "🔥"))
        assertEquals(seven, RecentEmojiList.withQuickChoiceAt(six, 6, "🚀"))
        assertEquals(seven + "✅", RecentEmojiList.withQuickChoiceAt(seven, 7, "✅"))
        assertEquals(seven + "✅", RecentEmojiList.withQuickChoiceAt(seven + "✅", 8, "🧪"))
        assertEquals(six, RecentEmojiList.withQuickChoiceAt(six, 9, "🧪"))
    }

    /** Only the optional seventh and eighth slots can be removed; the standard six are replace-only (#3279). */
    @Test
    fun withoutQuickChoiceAtRemovesOnlyOptionalSlots() {
        val eight = RecentEmojiList.DefaultQuickChoices + listOf("🚀", "✅")

        assertEquals(RecentEmojiList.DefaultQuickChoices + "✅", RecentEmojiList.withoutQuickChoiceAt(eight, 6))
        assertEquals(RecentEmojiList.DefaultQuickChoices + "🚀", RecentEmojiList.withoutQuickChoiceAt(eight, 7))
        assertEquals(eight, RecentEmojiList.withoutQuickChoiceAt(eight, 0))
        assertEquals(eight, RecentEmojiList.withoutQuickChoiceAt(eight, 8))
    }
}
