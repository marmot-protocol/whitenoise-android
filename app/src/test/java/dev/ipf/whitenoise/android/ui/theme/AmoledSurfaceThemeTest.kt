package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AmoledSurfaceThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun darkMatterThemeExposesAmoledSurfaceFlagOnlyWhenAmoledIsActive() {
        var lightWithAmoledPreference = true
        var standardDark = true
        var amoledDark = false

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false, amoled = true) {
                val isAmoled = isAmoledSurfaceTheme()
                SideEffect { lightWithAmoledPreference = isAmoled }
            }
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                val isAmoled = isAmoledSurfaceTheme()
                SideEffect { standardDark = isAmoled }
            }
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val isAmoled = isAmoledSurfaceTheme()
                SideEffect { amoledDark = isAmoled }
            }
        }

        composeRule.runOnIdle {
            assertFalse(lightWithAmoledPreference)
            assertFalse(standardDark)
            assertTrue(amoledDark)
        }
    }

    @Test
    fun amoledSurfaceBorderStrokeFollowsExplicitAmoledFlag() {
        var standardDarkBorder: BorderStroke? = BorderStroke(2.dp, Color.Red)
        var amoledBorder: BorderStroke? = null

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                val border = amoledSurfaceBorderStroke()
                SideEffect { standardDarkBorder = border }
            }
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val border = amoledSurfaceBorderStroke()
                SideEffect { amoledBorder = border }
            }
        }

        composeRule.runOnIdle {
            assertNull(standardDarkBorder)
            assertNotNull(amoledBorder)
            assertEquals(1.dp, requireNotNull(amoledBorder).width)
        }
    }

    @Test
    fun amoledBorderTokensAreWarmAndBlueFree() {
        assertEquals(Color(0xFF665A00), AmoledSurfaceBorder)
        assertEquals(Color(0xFF665A00), AmoledEmphasizedSurfaceBorder)
    }

    @Test
    fun everyAmoledColorSchemeRoleHasZeroBlueEvenWithCustomAccountAccent() {
        var captured: ColorScheme? = null

        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = true,
                amoled = true,
                accentColorArgb = 0xFF336699,
            ) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect { captured = colorScheme }
            }
        }

        composeRule.runOnIdle {
            val blueBearingRoles =
                requireNotNull(captured)
                    .namedRoles()
                    .filterValues { color -> color.toArgb() and 0xFF != 0 }

            assertTrue("AMOLED roles still driving blue: $blueBearingRoles", blueBearingRoles.isEmpty())
        }
    }

    @Test
    fun amoledBodyMutedAndAccentColorsMeetContrastFloors() {
        var captured: ColorScheme? = null

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect { captured = colorScheme }
            }
        }

        composeRule.runOnIdle {
            val scheme = requireNotNull(captured)
            assertContrastAtLeast(scheme.onBackground, scheme.background, WCAG_AA_NORMAL_TEXT_CONTRAST)
            assertContrastAtLeast(scheme.onSurface, scheme.surface, WCAG_AA_NORMAL_TEXT_CONTRAST)
            assertContrastAtLeast(scheme.onSurfaceVariant, scheme.surface, WCAG_AA_NORMAL_TEXT_CONTRAST)
            assertContrastAtLeast(scheme.primary, scheme.background, MINIMUM_NON_TEXT_CONTRAST)
            assertContrastAtLeast(scheme.error, scheme.background, MINIMUM_NON_TEXT_CONTRAST)
            assertContrastAtLeast(scheme.outline, scheme.surface, MINIMUM_NON_TEXT_CONTRAST)
            assertContrastAtLeast(scheme.outlineVariant, scheme.surface, MINIMUM_NON_TEXT_CONTRAST)
            listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondary to scheme.secondary,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiary to scheme.tertiary,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onError to scheme.error,
                scheme.onErrorContainer to scheme.errorContainer,
                scheme.inverseOnSurface to scheme.inverseSurface,
            ).forEach { (foreground, background) ->
                assertContrastAtLeast(foreground, background, WCAG_AA_NORMAL_TEXT_CONTRAST)
            }
        }
    }
}

private const val MINIMUM_NON_TEXT_CONTRAST = 3.0

private fun assertContrastAtLeast(
    foreground: Color,
    background: Color,
    minimum: Double,
) {
    val foregroundArgb = foreground.toArgb().toLong() and 0xFFFFFFFFL
    val backgroundArgb = background.toArgb().toLong() and 0xFFFFFFFFL
    assertTrue(
        "Expected ${contrastRatio(foregroundArgb, backgroundArgb)} >= $minimum for $foreground on $background",
        contrastRatio(foregroundArgb, backgroundArgb) >= minimum,
    )
}

private fun ColorScheme.namedRoles(): Map<String, Color> =
    linkedMapOf(
        "primary" to primary,
        "onPrimary" to onPrimary,
        "primaryContainer" to primaryContainer,
        "onPrimaryContainer" to onPrimaryContainer,
        "inversePrimary" to inversePrimary,
        "secondary" to secondary,
        "onSecondary" to onSecondary,
        "secondaryContainer" to secondaryContainer,
        "onSecondaryContainer" to onSecondaryContainer,
        "tertiary" to tertiary,
        "onTertiary" to onTertiary,
        "tertiaryContainer" to tertiaryContainer,
        "onTertiaryContainer" to onTertiaryContainer,
        "background" to background,
        "onBackground" to onBackground,
        "surface" to surface,
        "onSurface" to onSurface,
        "surfaceVariant" to surfaceVariant,
        "onSurfaceVariant" to onSurfaceVariant,
        "surfaceTint" to surfaceTint,
        "inverseSurface" to inverseSurface,
        "inverseOnSurface" to inverseOnSurface,
        "error" to error,
        "onError" to onError,
        "errorContainer" to errorContainer,
        "onErrorContainer" to onErrorContainer,
        "outline" to outline,
        "outlineVariant" to outlineVariant,
        "scrim" to scrim,
        "surfaceBright" to surfaceBright,
        "surfaceDim" to surfaceDim,
        "surfaceContainer" to surfaceContainer,
        "surfaceContainerHigh" to surfaceContainerHigh,
        "surfaceContainerHighest" to surfaceContainerHighest,
        "surfaceContainerLow" to surfaceContainerLow,
        "surfaceContainerLowest" to surfaceContainerLowest,
        "primaryFixed" to primaryFixed,
        "primaryFixedDim" to primaryFixedDim,
        "onPrimaryFixed" to onPrimaryFixed,
        "onPrimaryFixedVariant" to onPrimaryFixedVariant,
        "secondaryFixed" to secondaryFixed,
        "secondaryFixedDim" to secondaryFixedDim,
        "onSecondaryFixed" to onSecondaryFixed,
        "onSecondaryFixedVariant" to onSecondaryFixedVariant,
        "tertiaryFixed" to tertiaryFixed,
        "tertiaryFixedDim" to tertiaryFixedDim,
        "onTertiaryFixed" to onTertiaryFixed,
        "onTertiaryFixedVariant" to onTertiaryFixedVariant,
    )
