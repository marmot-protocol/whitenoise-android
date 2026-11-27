package dev.ipf.whitenoise.android.ui.group

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The timer label shows the real retention count, whatever range the protocol value is in. */
@RunWith(AndroidJUnit4::class)
class DisappearingMessagesLabelTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Counts past `Int.MAX_VALUE` in every unit keep their exact value instead of wrapping. */
    @Test
    fun countsBeyondIntRangeKeepTheirExactValue() {
        val count = 4_294_967_297L
        val labels =
            labelsFor(
                4_294_967_301L,
                count * 60L,
                count * 3_600L,
                count * 86_400L,
            )

        assertEquals(
            listOf("4294967301 seconds", "$count minutes", "$count hours", "$count days"),
            labels,
        )
    }

    /** Counts that already fit an `Int` keep their singular and plural labels. */
    @Test
    fun countsWithinIntRangeKeepTheirLabels() {
        assertEquals(listOf("1 second", "7 seconds"), labelsFor(1L, 7L))
    }

    /** Past the `Int` range, Russian still picks the one, few or many form from the real count. */
    @Test
    @Config(qualifiers = "ru")
    fun russianCountsBeyondIntRangeKeepTheirPluralForm() {
        val year = 31_536_000L
        val labels = labelsFor(2_147_483_651L * year, 2_147_483_652L * year, 2_147_483_655L * year)

        assertEquals(listOf("2147483651 год", "2147483652 года", "2147483655 лет"), labels)
    }

    /** A protocol value above `Long.MAX_VALUE` reads as the longest duration, never a negative one. */
    @Test
    fun protocolValuesAboveLongRangeNeverReadNegative() {
        var label = ""
        composeRule.setContent { label = disappearingMessagesLabel(ULong.MAX_VALUE) }
        composeRule.waitForIdle()

        assertEquals("${Long.MAX_VALUE} seconds", label)
    }

    private fun labelsFor(vararg seconds: Long): List<String> {
        val labels = MutableList(seconds.size) { "" }
        composeRule.setContent {
            seconds.forEachIndexed { index, secs -> labels[index] = disappearingMessagesLabel(secs) }
        }
        composeRule.waitForIdle()
        return labels
    }
}
