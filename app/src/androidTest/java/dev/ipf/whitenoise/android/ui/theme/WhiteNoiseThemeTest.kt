package dev.ipf.whitenoise.android.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val OPAQUE_ARGB_MASK = 0xFFFFFFFFL
private val AmoledPrimary = Color(0xFFFFC400)
private val AmoledPrimaryContainer = Color(0xFF493800)
private val BlueFreeWhiteAccent = Color(0xFFFFFF00)

class WhiteNoiseThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryHighlightIsCyanInLightAndDarkThemes() {
        var lightPrimary: Color? = null
        var lightPrimaryContainer: Color? = null
        var darkPrimary: Color? = null
        var darkPrimaryContainer: Color? = null

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect {
                    lightPrimary = colorScheme.primary
                    lightPrimaryContainer = colorScheme.primaryContainer
                }
            }
            WhiteNoiseTheme(darkTheme = true) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect {
                    darkPrimary = colorScheme.primary
                    darkPrimaryContainer = colorScheme.primaryContainer
                }
            }
        }

        composeRule.runOnIdle {
            val expected = Color(0xFF06B6D4)
            assertEquals(expected, lightPrimary)
            assertEquals(expected, lightPrimaryContainer)
            assertEquals(expected, darkPrimary)
            assertEquals(expected, darkPrimaryContainer)
        }
    }

    @Test
    fun customAccentDrivesPrimaryRolesAndSurfaceTint() {
        var scheme: ColorScheme? = null

        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = false,
                dynamicColor = true,
                accentColorArgb = 0xFFFFC107,
            ) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect { scheme = colorScheme }
            }
        }

        composeRule.runOnIdle {
            val s = requireNotNull(scheme)
            val accent = Color(0xFFFFC107)
            assertEquals(accent, s.primary)
            assertEquals(Color.Black, s.onPrimary)
            assertEquals(accent, s.primaryContainer)
            assertEquals(Color.Black, s.onPrimaryContainer)
            assertEquals(accent, s.inversePrimary)
            assertEquals(accent, s.surfaceTint)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
    fun dynamicPrimaryRolesArePreservedWithoutCustomAccent() {
        var scheme: ColorScheme? = null
        val expected =
            dynamicLightColorScheme(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )

        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = false,
                dynamicColor = true,
            ) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect { scheme = colorScheme }
            }
        }

        composeRule.runOnIdle {
            val actual = requireNotNull(scheme)
            assertEquals(expected.primary, actual.primary)
            assertEquals(expected.onPrimary, actual.onPrimary)
            assertEquals(expected.primaryContainer, actual.primaryContainer)
            assertEquals(expected.onPrimaryContainer, actual.onPrimaryContainer)
            assertEquals(expected.inversePrimary, actual.inversePrimary)
            assertEquals(expected.surfaceTint, actual.surfaceTint)
        }
    }

    @Test
    @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.R)
    fun dynamicColorFallsBackToBrandPaletteBeforeApi31() {
        var scheme: ColorScheme? = null

        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = false,
                dynamicColor = true,
            ) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect { scheme = colorScheme }
            }
        }

        composeRule.runOnIdle {
            val actual = requireNotNull(scheme)
            assertEquals(Color(0xFF06B6D4), actual.primary)
            assertEquals(Color(0xFF06B6D4), actual.primaryContainer)
        }
    }

    @Test
    fun customAccentKeepsAmoledSurfaceTintTransparentAndDropsBlueChannel() {
        var scheme: ColorScheme? = null

        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = true,
                amoled = true,
                accentColorArgb = 0xFFFFC107,
            ) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect { scheme = colorScheme }
            }
        }

        composeRule.runOnIdle {
            val s = requireNotNull(scheme)
            assertEquals(Color(0xFFFFC100), s.primary)
            assertEquals(Color.Transparent, s.surfaceTint)
        }
    }

    @Test
    fun blackAccentKeepsInversePrimaryReadableAcrossThemes() {
        assertInversePrimaryContrast(
            accent = Color.Black,
            expectedAmoledAccent = AmoledPrimary,
        )
    }

    @Test
    fun whiteAccentKeepsInversePrimaryReadableAcrossThemes() {
        assertInversePrimaryContrast(
            accent = Color.White,
            expectedAmoledAccent = BlueFreeWhiteAccent,
        )
    }

    /**
     * AMOLED audit (#446): with the AMOLED theme selected, every full-screen and
     * elevated surface token must paint pure #000000, and `surfaceTint` must be
     * transparent so M3 tonal elevation does not lift elevated components
     * (dialogs, menus, sheets, app bars, the chat-bubble long-press
     * reaction/actions popup) off the black canvas.
     */
    @Test
    fun amoledThemePaintsEverySurfaceTokenPureBlack() {
        var scheme: ColorScheme? = null

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect { scheme = colorScheme }
            }
        }

        composeRule.runOnIdle {
            val s = requireNotNull(scheme)
            // Window / scaffold backgrounds and the base surface.
            assertEquals(Color.Black, s.background)
            assertEquals(Color.Black, s.surface)
            // Container roles drive elevated components: sheets
            // (surfaceContainerLow), menus/dropdowns (surfaceContainer),
            // dialogs (surfaceContainerHigh). All must be black.
            assertEquals(Color.Black, s.surfaceContainerLowest)
            assertEquals(Color.Black, s.surfaceContainerLow)
            assertEquals(Color.Black, s.surfaceContainer)
            assertEquals(Color.Black, s.surfaceContainerHigh)
            assertEquals(Color.Black, s.surfaceContainerHighest)
            assertEquals(Color.Black, s.surfaceVariant)
            assertEquals(Color.Black, s.surfaceBright)
            assertEquals(Color.Black, s.surfaceDim)
            // Snackbars use inverse tokens in Material 3: container from
            // inverseSurface, text from inverseOnSurface, action from
            // inversePrimary. AMOLED snackbars must stay black with readable
            // text/action content.
            assertEquals(Color.Black, s.inverseSurface)
            assertEquals(s.onSurface, s.inverseOnSurface)
            assertEquals(AmoledPrimary, s.inversePrimary)
            // Tonal-elevation overlay must be a no-op on AMOLED.
            assertEquals(Color.Transparent, s.surfaceTint)
        }
    }

    /** AMOLED replaces the cyan brand primary with a readable blue-free accent. */
    @Test
    fun amoledThemeUsesBlueFreePrimary() {
        var primary: Color? = null
        var primaryContainer: Color? = null

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect {
                    primary = colorScheme.primary
                    primaryContainer = colorScheme.primaryContainer
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(AmoledPrimary, primary)
            assertEquals(AmoledPrimaryContainer, primaryContainer)
        }
    }

    private fun assertInversePrimaryContrast(
        accent: Color,
        expectedAmoledAccent: Color,
    ) {
        val captured = CapturedSchemes()
        composeRule.setContent { CaptureAccentSchemes(accent, captured) }

        composeRule.runOnIdle {
            listOf(
                captured.light to accent,
                captured.dark to accent,
                captured.amoled to expectedAmoledAccent,
            ).forEach { (capturedScheme, expectedPrimary) ->
                val scheme = requireNotNull(capturedScheme)
                assertEquals(expectedPrimary, scheme.primary)
                val ratio =
                    contrastRatio(
                        scheme.inversePrimary.toOpaqueArgb(),
                        scheme.inverseSurface.toOpaqueArgb(),
                    )
                assertTrue("inversePrimary contrast was $ratio", ratio >= WCAG_AA_NORMAL_TEXT_CONTRAST)
            }
        }
    }
}

private data class CapturedSchemes(
    var light: ColorScheme? = null,
    var dark: ColorScheme? = null,
    var amoled: ColorScheme? = null,
)

@Composable
private fun CaptureAccentSchemes(
    accent: Color,
    captured: CapturedSchemes,
) {
    val accentArgb = accent.toOpaqueArgb()
    WhiteNoiseTheme(darkTheme = false, accentColorArgb = accentArgb) {
        val scheme = MaterialTheme.colorScheme
        SideEffect { captured.light = scheme }
    }
    WhiteNoiseTheme(darkTheme = true, accentColorArgb = accentArgb) {
        val scheme = MaterialTheme.colorScheme
        SideEffect { captured.dark = scheme }
    }
    WhiteNoiseTheme(darkTheme = true, amoled = true, accentColorArgb = accentArgb) {
        val scheme = MaterialTheme.colorScheme
        SideEffect { captured.amoled = scheme }
    }
}

private fun Color.toOpaqueArgb(): Long = toArgb().toLong() and OPAQUE_ARGB_MASK
