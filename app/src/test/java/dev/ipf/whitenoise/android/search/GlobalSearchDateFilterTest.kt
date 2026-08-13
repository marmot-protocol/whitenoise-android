package dev.ipf.whitenoise.android.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class GlobalSearchDateFilterTest {
    @Test
    fun anyTimeProducesNoEpochBounds() {
        val bounds =
            GlobalSearchDateFilterSelection.AnyTime.resolveEpochBounds(
                nowMillis = FIXED_NOW_MILLIS,
                zoneId = NEW_YORK,
            )

        assertNull(bounds)
    }

    @Test
    fun todayUsesStartOfDayThroughNextStartOfDayInDeviceZone() {
        val bounds =
            GlobalSearchDateFilterSelection.Today.resolveEpochBounds(
                nowMillis = FIXED_NOW_MILLIS,
                zoneId = NEW_YORK,
            )

        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_786_420_800_000L,
                endEpochMillisExclusive = 1_786_507_200_000L,
            ),
            bounds,
        )
    }

    @Test
    fun lastSevenDaysIncludesTodayAndSixPriorCivilDays() {
        val bounds =
            GlobalSearchDateFilterSelection.Last7Days.resolveEpochBounds(
                nowMillis = FIXED_NOW_MILLIS,
                zoneId = NEW_YORK,
            )

        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_785_902_400_000L,
                endEpochMillisExclusive = 1_786_507_200_000L,
            ),
            bounds,
        )
    }

    @Test
    fun lastThirtyDaysIncludesTodayAndTwentyNinePriorCivilDays() {
        val bounds =
            GlobalSearchDateFilterSelection.Last30Days.resolveEpochBounds(
                nowMillis = FIXED_NOW_MILLIS,
                zoneId = NEW_YORK,
            )

        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_783_915_200_000L,
                endEpochMillisExclusive = 1_786_507_200_000L,
            ),
            bounds,
        )
    }

    @Test
    fun customInclusiveRangeUsesHalfOpenUpperBound() {
        val bounds =
            GlobalSearchDateFilterSelection
                .Custom(
                    from = LocalDate.of(2026, 7, 1),
                    to = LocalDate.of(2026, 7, 3),
                    zoneId = NEW_YORK,
                ).resolveEpochBounds(
                    nowMillis = FIXED_NOW_MILLIS,
                    zoneId = BERLIN,
                )

        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_782_878_400_000L,
                endEpochMillisExclusive = 1_783_137_600_000L,
            ),
            bounds,
        )
    }

    @Test
    fun civilDateConversionNeverTreatsPickerUtcMidnightAsLocalMidnight() {
        val pickerUtcMillis =
            LocalDate
                .of(2026, 8, 11)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()

        val civilDate = civilDateFromPickerUtcMillis(pickerUtcMillis)
        val tokyoStart = civilDateStartEpochMillis(civilDate, ZoneId.of("Asia/Tokyo"))
        val newYorkStart = civilDateStartEpochMillis(civilDate, ZoneId.of("America/New_York"))

        assertEquals(LocalDate.of(2026, 8, 11), civilDate)
        assertEquals(1_786_374_000_000L, tokyoStart)
        assertEquals(1_786_420_800_000L, newYorkStart)
        assertFalse(tokyoStart == newYorkStart)
    }

    @Test
    fun epochBoundsStayDeterministicAcrossDstGapAndOverlap() {
        val springGap =
            GlobalSearchDateFilterSelection
                .Custom(
                    from = LocalDate.of(2026, 3, 8),
                    to = LocalDate.of(2026, 3, 8),
                    zoneId = NEW_YORK,
                ).resolveEpochBounds(
                    nowMillis = FIXED_NOW_MILLIS,
                    zoneId = NEW_YORK,
                )
        val fallOverlap =
            GlobalSearchDateFilterSelection
                .Custom(
                    from = LocalDate.of(2026, 11, 1),
                    to = LocalDate.of(2026, 11, 1),
                    zoneId = NEW_YORK,
                ).resolveEpochBounds(
                    nowMillis = FIXED_NOW_MILLIS,
                    zoneId = NEW_YORK,
                )

        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_772_946_000_000L,
                endEpochMillisExclusive = 1_773_028_800_000L,
            ),
            springGap,
        )
        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_793_505_600_000L,
                endEpochMillisExclusive = 1_793_595_600_000L,
            ),
            fallOverlap,
        )
    }

    @Test
    fun leapDayCustomRangeUsesNextCivilDayExclusiveEnd() {
        val bounds =
            GlobalSearchDateFilterSelection
                .Custom(
                    from = LocalDate.of(2024, 2, 29),
                    to = LocalDate.of(2024, 2, 29),
                    zoneId = NEW_YORK,
                ).resolveEpochBounds(
                    nowMillis = FIXED_NOW_MILLIS,
                    zoneId = NEW_YORK,
                )

        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_709_182_800_000L,
                endEpochMillisExclusive = 1_709_269_200_000L,
            ),
            bounds,
        )
    }

    @Test
    fun reversedCustomRangeIsInvalidAndCannotApply() {
        val validation =
            GlobalSearchDateFilterSelection
                .Custom(
                    from = LocalDate.of(2026, 8, 12),
                    to = LocalDate.of(2026, 8, 10),
                    zoneId = NEW_YORK,
                ).validateCustomRange()

        assertEquals(GlobalSearchCustomRangeValidation.Reversed, validation)
        assertFalse(validation.canApply)
    }

    @Test
    fun equalCustomRangeEndsAreValid() {
        val validation =
            GlobalSearchDateFilterSelection
                .Custom(
                    from = LocalDate.of(2026, 8, 10),
                    to = LocalDate.of(2026, 8, 10),
                    zoneId = NEW_YORK,
                ).validateCustomRange()

        assertEquals(GlobalSearchCustomRangeValidation.Valid, validation)
        assertTrue(validation.canApply)
    }

    @Test
    fun dateFilterCodecRoundTripsPresetAndCustomDates() {
        val selections =
            listOf(
                GlobalSearchDateFilterSelection.AnyTime,
                GlobalSearchDateFilterSelection.Today,
                GlobalSearchDateFilterSelection.Last7Days,
                GlobalSearchDateFilterSelection.Last30Days,
                GlobalSearchDateFilterSelection.Custom(
                    from = LocalDate.of(2026, 1, 2),
                    to = LocalDate.of(2026, 3, 4),
                    zoneId = LOS_ANGELES,
                ),
            )

        selections.forEach { selection ->
            assertEquals(
                selection,
                decodeGlobalSearchDateFilter(encodeGlobalSearchDateFilter(selection)),
            )
        }
    }

    @Test
    fun dateFilterCodecRoundTripsFixedOffsetZoneWithColon() {
        val selection =
            GlobalSearchDateFilterSelection.Custom(
                from = LocalDate.of(2026, 1, 2),
                to = LocalDate.of(2026, 3, 4),
                zoneId = ZoneId.of("+05:30"),
            )

        assertEquals(selection, decodeGlobalSearchDateFilter(encodeGlobalSearchDateFilter(selection)))
    }

    @Test
    fun customSelectionRetainsSnapshottedZoneRegardlessOfCallerZone() {
        val custom =
            GlobalSearchDateFilterSelection.Custom(
                from = LocalDate.of(2026, 7, 1),
                to = LocalDate.of(2026, 7, 3),
                zoneId = LOS_ANGELES,
            )

        val losAngelesBounds =
            custom.resolveEpochBounds(
                nowMillis = FIXED_NOW_MILLIS,
                zoneId = LOS_ANGELES,
            )
        val berlinCallerBounds =
            custom.resolveEpochBounds(
                nowMillis = FIXED_NOW_MILLIS,
                zoneId = BERLIN,
            )

        assertEquals(losAngelesBounds, berlinCallerBounds)
        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_782_889_200_000L,
                endEpochMillisExclusive = 1_783_148_400_000L,
            ),
            losAngelesBounds,
        )
    }

    @Test
    fun malformedCustomDateFilterDecodeReturnsAnyTime() {
        assertEquals(
            GlobalSearchDateFilterSelection.AnyTime,
            decodeGlobalSearchDateFilter("gsd:custom:not-a-date:2026-03-04:America/Los_Angeles"),
        )
        assertEquals(
            GlobalSearchDateFilterSelection.AnyTime,
            decodeGlobalSearchDateFilter("gsd:custom:2026-01-02:2026-03-04:Not/AZone"),
        )
        assertEquals(
            GlobalSearchDateFilterSelection.AnyTime,
            decodeGlobalSearchDateFilter("gsd:custom:2026-01-02:2026-03-04"),
        )
        assertEquals(
            GlobalSearchDateFilterSelection.AnyTime,
            decodeGlobalSearchDateFilter("gsd:custom:2026-08-12:2026-08-10:UTC"),
        )
        assertEquals(
            GlobalSearchDateFilterSelection.AnyTime,
            decodeGlobalSearchDateFilter("gsd:custom:only-one-part"),
        )
        assertEquals(
            GlobalSearchDateFilterSelection.AnyTime,
            decodeGlobalSearchDateFilter("bad-prefix:custom:2026-01-02:2026-03-04:UTC"),
        )
    }

    @Test
    fun reversedCustomRangeCannotBeResolvedOrProjected() {
        val reversed =
            GlobalSearchDateFilterSelection.Custom(
                from = LocalDate.of(2026, 8, 12),
                to = LocalDate.of(2026, 8, 10),
                zoneId = NEW_YORK,
            )

        assertThrows(IllegalArgumentException::class.java) {
            reversed.resolveEpochBounds(
                nowMillis = FIXED_NOW_MILLIS,
                zoneId = NEW_YORK,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            projectGlobalSearchRequest(
                query = "hello",
                dateFilter = reversed,
                contentFilter = GlobalSearchContentFilterSelection.EMPTY,
                zoneId = NEW_YORK,
                nowMillis = FIXED_NOW_MILLIS,
            )
        }
    }

    @Test
    fun requestProjectionMarksActiveDateFiltersAsUnsupportedByCurrentEngine() {
        val projection =
            projectGlobalSearchRequest(
                query = "hello",
                dateFilter = GlobalSearchDateFilterSelection.Today,
                contentFilter = GlobalSearchContentFilterSelection.EMPTY,
                zoneId = NEW_YORK,
                nowMillis = FIXED_NOW_MILLIS,
            )

        assertTrue(projection.requiresTypedMdkContract)
        assertEquals(
            GlobalSearchEpochBounds(
                startEpochMillisInclusive = 1_786_420_800_000L,
                endEpochMillisExclusive = 1_786_507_200_000L,
            ),
            projection.dateBounds,
        )
    }

    @Test
    fun textOnlyQueryRemainsEngineCompatibleWithoutActiveFilters() {
        val projection =
            projectGlobalSearchRequest(
                query = "hello",
                dateFilter = GlobalSearchDateFilterSelection.AnyTime,
                contentFilter = GlobalSearchContentFilterSelection.EMPTY,
                zoneId = NEW_YORK,
                nowMillis = FIXED_NOW_MILLIS,
            )

        assertFalse(projection.requiresTypedMdkContract)
        assertNull(projection.dateBounds)
        assertTrue(projection.contentKinds.isEmpty())
        assertEquals("hello", projection.query)
    }

    private companion object {
        private val NEW_YORK = ZoneId.of("America/New_York")
        private val LOS_ANGELES = ZoneId.of("America/Los_Angeles")
        private val BERLIN = ZoneId.of("Europe/Berlin")
        private const val FIXED_NOW_MILLIS = 1_786_462_800_000L // 2026-08-11 11:40:00 EDT
    }
}
