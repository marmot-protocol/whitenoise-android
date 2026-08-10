package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TtsRateInputTest {
    @Test
    fun customRateBoundariesAreAccepted() {
        assertEquals(0.1f, parseTtsRateInput("0.1", Locale.US))
        assertEquals(10.0f, parseTtsRateInput("10.0", Locale.US))
    }

    @Test
    fun customRateInputNormalizesToOneDecimalPlace() {
        assertEquals(1.3f, parseTtsRateInput("1.25", Locale.US))
        assertEquals(1.3f, parseTtsRateInput("1.26", Locale.US))
        assertEquals(1.3f, parseTtsRateInput("1,26", Locale.GERMANY))
    }

    @Test
    fun invalidAndOutOfRangeCustomRatesAreRejected() {
        assertNull(parseTtsRateInput("", Locale.US))
        assertNull(parseTtsRateInput("fast", Locale.US))
        assertNull(parseTtsRateInput("0.09", Locale.US))
        assertNull(parseTtsRateInput("10.01", Locale.US))
        assertNull(parseTtsRateInput("1.0x", Locale.US))
    }

    @Test
    fun customRateInputRejectsGroupingAndWrongDecimalSeparators() {
        assertNull(parseTtsRateInput("0,5", Locale.US))
        assertNull(parseTtsRateInput("1,0", Locale.US))
        assertNull(parseTtsRateInput("0.5", Locale.GERMANY))
        assertNull(parseTtsRateInput("1.0", Locale.GERMANY))
    }

    @Test
    fun customRateInputFormattingUsesTheActiveLocalesDecimalSeparator() {
        assertEquals("1.3", ttsRateInputValue(1.26f, Locale.US))
        assertEquals("1,3", ttsRateInputValue(1.26f, Locale.GERMANY))
        assertEquals("10,0", ttsRateInputValue(10.0f, Locale.GERMANY))
    }
}
