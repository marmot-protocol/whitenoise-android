package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.state.tonalBubbleColorPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Any action colour stays readable as text: dark blues on the dark theme, amber or white on the light one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccentReadabilityThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyAccentKeepsPrimaryReadableOnEverySurface() {
        val accents = tonalBubbleColorPresets() + listOf(0xFF000080L, 0xFFFFC107L, 0xFF808080L)
        val captured = mutableMapOf<Pair<Long, Boolean>, ColorScheme>()

        composeRule.setContent {
            accents.forEach { accent ->
                listOf(false, true).forEach { dark ->
                    WhiteNoiseTheme(darkTheme = dark, accentColorArgb = accent) {
                        val scheme = MaterialTheme.colorScheme
                        SideEffect { captured[accent to dark] = scheme }
                    }
                }
            }
        }

        composeRule.runOnIdle {
            captured.forEach { (key, scheme) ->
                val (accent, dark) = key
                val label = "#%06X dark=%s".format(accent and 0xFFFFFF, dark)
                assertEquals(label, Color(accent), scheme.primaryContainer)
                assertContrast(label, scheme.onPrimary, scheme.primary)
                scheme.surfaceRoles().forEach { surface -> assertContrast(label, scheme.primary, surface) }
            }
        }
    }

    /** An accent that already reads on the surfaces is used exactly as picked. */
    @Test
    fun readableAccentIsUsedUnchanged() {
        val accent = 0xFF1D4ED8L
        var scheme: ColorScheme? = null

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, accentColorArgb = accent) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect { scheme = colorScheme }
            }
        }

        composeRule.runOnIdle {
            assertEquals(Color(accent), requireNotNull(scheme).primary)
            assertEquals(Color.White, requireNotNull(scheme).onPrimary)
        }
    }
}

private fun ColorScheme.surfaceRoles(): List<Color> =
    listOf(
        background,
        surface,
        surfaceVariant,
        surfaceBright,
        surfaceDim,
        surfaceContainerLowest,
        surfaceContainerLow,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
    )

private fun assertContrast(
    label: String,
    foreground: Color,
    background: Color,
) {
    val ratio = contrastRatio(foreground.toOpaqueArgb(), background.toOpaqueArgb())
    assertTrue("$label: $foreground on $background was $ratio", ratio >= WCAG_AA_NORMAL_TEXT_CONTRAST)
}

private fun Color.toOpaqueArgb(): Long = toArgb().toLong() and 0xFFFFFFFFL
