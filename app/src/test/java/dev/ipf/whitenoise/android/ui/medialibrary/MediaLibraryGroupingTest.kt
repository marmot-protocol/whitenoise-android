package dev.ipf.whitenoise.android.ui.medialibrary

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MediaLibraryGroupingTest {
    @Test
    fun groupIntoMonthSectionsPreservesNewestFirstMonthOrder() {
        val feb = epochSeconds(2024, 2, 10)
        val janRecent = epochSeconds(2024, 1, 15)
        val janOlder = epochSeconds(2024, 1, 5)
        val items = listOf(feb, janRecent, janOlder)

        val sections = groupIntoMonthSections(items) { it }

        assertEquals(listOf(monthKeyForMedia(feb), monthKeyForMedia(janRecent)), sections.map { it.monthKey })
        assertEquals(listOf(feb), sections[0].items)
        assertEquals(listOf(janRecent, janOlder), sections[1].items)
    }

    @Test
    fun groupIntoMonthSectionsKeepsStableItemOrderWithinMonth() {
        val newer = epochSeconds(2023, 6, 20)
        val older = epochSeconds(2023, 6, 1)
        val sections = groupIntoMonthSections(listOf(newer, older)) { it }

        assertEquals(1, sections.size)
        assertEquals(listOf(newer, older), sections.single().items)
    }

    private fun epochSeconds(
        year: Int,
        month: Int,
        day: Int,
    ): ULong = ZonedDateTime.of(year, month, day, 12, 0, 0, 0, ZoneId.systemDefault()).toEpochSecond().toULong()
}
