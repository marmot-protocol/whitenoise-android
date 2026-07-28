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

    @Test
    fun normalizeQuickChoicesPadsAndDeduplicatesSavedChoices() {
        val choices =
            RecentEmojiList.normalizeQuickChoices(
                choices = listOf("🚀", "👍", "🚀"),
                defaults = listOf("👍", "❤️", "😂", "🎉", "😮", "😢"),
                limit = 6,
            )

        assertEquals(listOf("🚀", "👍", "❤️", "😂", "🎉", "😮"), choices)
    }
}
