package dev.ipf.whitenoise.android.ui.conversation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

/** Received stamps are untrusted: unrepresentable ones render an unknown day instead of failing the row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDayLabelTest {
    private val unrepresentable =
        listOf(
            253_402_300_800uL,
            40_000_000_000_000_000uL,
            Instant.MAX.epochSecond.toULong(),
            Long.MAX_VALUE.toULong(),
            ULong.MAX_VALUE,
        )
    private val ordinary = 1_780_787_662uL
    private lateinit var savedZone: TimeZone

    @Before
    fun pinFarthestAheadZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun actualDayGroupingKeepsUnknownDatesDistinctFromEpochInBothExtremeZones() {
        val maximum = 253_402_300_799uL
        val stamps = listOf(0uL, maximum, maximum + 1uL, 18_446_744_073_709_552uL, ULong.MAX_VALUE)
        for (zoneName in listOf("Etc/GMT+12", "Pacific/Kiritimati")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneName))
            val zone = java.time.ZoneId.of(zoneName)
            for (a in stamps) {
                for (b in stamps) {
                    val expectedA =
                        a
                            .takeIf { it <= maximum }
                            ?.let { Instant.ofEpochSecond(it.toLong()).atZone(zone).toLocalDate() }
                    val expectedB =
                        b
                            .takeIf { it <= maximum }
                            ?.let { Instant.ofEpochSecond(it.toLong()).atZone(zone).toLocalDate() }
                    assertEquals(expectedA != expectedB, differentDay(a, b))
                }
                if (a == 0uL || a > maximum) {
                    assertEquals("", messageDayLabel(a, Locale.US))
                } else {
                    assertTrue(messageDayLabel(a, Locale.US).isNotEmpty())
                }
            }
        }
    }

    @Test
    fun unrepresentableStampsHaveAnEmptyLabel() {
        unrepresentable.forEach { assertEquals("", messageDayLabel(it, Locale.US)) }
        assertEquals("", messageDayLabel(0uL, Locale.US))
    }

    @Test
    fun ordinaryAndLastDisplayableStampsKeepTheirLabel() {
        assertTrue(messageDayLabel(ordinary, Locale.US).isNotEmpty())
        assertTrue(messageDayLabel(253_402_300_799uL, Locale.US).isNotEmpty())
    }

    @Test
    fun anUnrepresentableNeighborStartsADayWithoutFailing() {
        unrepresentable.forEach {
            assertTrue(differentDay(it, ordinary))
            assertTrue(differentDay(ordinary, it))
            assertTrue(differentDay(0uL, it))
        }
        assertFalse(differentDay(40_000_000_000_000_000uL, ULong.MAX_VALUE))
        assertFalse(differentDay(ordinary, ordinary + 60uL))
    }
}
