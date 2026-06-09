package dev.ipf.darkmatter.core

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
    fun quickChoicesPreferRecentsAndFillFromDefaults() {
        val choices =
            RecentEmojiList.quickChoices(
                recent = listOf("🔥", "👍"),
                defaults = listOf("👍", "❤️", "😂", "🎉", "😮"),
                limit = 5,
            )

        assertEquals(listOf("🔥", "👍", "❤️", "😂", "🎉"), choices)
    }

    @Test
    fun quickChoicesUseCommonDefaultsWhenNoRecentsExist() {
        assertEquals(
            listOf("👍", "❤️", "😂", "🎉", "😮"),
            RecentEmojiList.quickChoices(recent = emptyList()),
        )
    }
}
