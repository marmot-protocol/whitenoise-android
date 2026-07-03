package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.material3.Typography as M3Typography

class ScaledTypographyTest {
    private fun M3Typography.allStyles(): List<TextStyle> =
        listOf(
            displayLarge,
            displayMedium,
            displaySmall,
            headlineLarge,
            headlineMedium,
            headlineSmall,
            titleLarge,
            titleMedium,
            titleSmall,
            bodyLarge,
            bodyMedium,
            bodySmall,
            labelLarge,
            labelMedium,
            labelSmall,
        )

    // The Default step must be a true no-op — same object, not just equal —
    // so existing screenshot baselines can't drift.
    @Test
    fun scaledBy_oneIsIdentity() {
        assertSame(Typography, Typography.scaledBy(1f))
    }

    @Test
    fun scaledBy_multipliesFontSizeAndLineHeightOfEveryStyle() {
        val scaled = Typography.scaledBy(1.3f)
        Typography.allStyles().zip(scaled.allStyles()).forEach { (base, style) ->
            assertEquals(base.fontSize.value * 1.3f, style.fontSize.value, 1e-4f)
            assertEquals(base.lineHeight.value * 1.3f, style.lineHeight.value, 1e-4f)
        }
    }

    @Test
    fun scaledBy_smallStepShrinksBodyLarge() {
        val scaled = Typography.scaledBy(0.85f)
        assertEquals(13.6f, scaled.bodyLarge.fontSize.value, 1e-4f)
        assertEquals(20.4f, scaled.bodyLarge.lineHeight.value, 1e-4f)
    }

    // Em line heights already track the font size, so an extra multiply would
    // double-apply the step; unspecified values must stay unspecified.
    @Test
    fun scaledBy_leavesEmAndUnspecifiedUnitsAlone() {
        val typography =
            M3Typography(
                bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 1.5.em),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = TextUnit.Unspecified),
            )
        val scaled = typography.scaledBy(1.15f)
        assertEquals(1.5.em, scaled.bodyLarge.lineHeight)
        assertTrue(scaled.bodyMedium.lineHeight.isUnspecified)
    }

    // Letter spacing is left untouched: tracking is a design choice, not a
    // legibility scale, and the OS font-size setting doesn't scale it either.
    @Test
    fun scaledBy_preservesLetterSpacing() {
        val scaled = Typography.scaledBy(1.3f)
        assertEquals(Typography.bodyLarge.letterSpacing, scaled.bodyLarge.letterSpacing)
    }
}
