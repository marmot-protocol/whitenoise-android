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

    @Test
    fun monthKeysClampAtTheSameLocalBoundaryInBothExtremeZones() {
        val maximum = 253_402_300_799uL
        for (zone in listOf(ZoneId.of("Etc/GMT+12"), ZoneId.of("Pacific/Kiritimati"))) {
            for (seconds in listOf(0uL, maximum, maximum + 1uL, 18_446_744_073_709_552uL, ULong.MAX_VALUE)) {
                val expected =
                    java.time.Instant
                        .ofEpochSecond(seconds.coerceAtMost(maximum).toLong())
                        .atZone(zone)
                assertEquals(expected.year * 100 + expected.monthValue - 1, monthKeyForMedia(seconds, zone))
            }
        }
    }

    @Test
    fun unrepresentableStampsGroupAsTheNewestMonthInsteadOfThrowing() {
        val ordinary = epochSeconds(2024, 2, 10)
        val items = listOf(ULong.MAX_VALUE, 40_000_000_000_000_000uL, ordinary)

        val sections = groupIntoMonthSections(items) { it }

        assertEquals(2, sections.size)
        assertEquals(listOf(ULong.MAX_VALUE, 40_000_000_000_000_000uL), sections[0].items)
        assertEquals(listOf(ordinary), sections[1].items)
    }
}
