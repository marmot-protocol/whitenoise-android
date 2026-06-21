package dev.ipf.darkmatter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DarkMatterThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryHighlightIsCyanInLightAndDarkThemes() {
        var lightPrimary: Color? = null
        var lightPrimaryContainer: Color? = null
        var darkPrimary: Color? = null
        var darkPrimaryContainer: Color? = null

        composeRule.setContent {
            DarkMatterTheme(darkTheme = false) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect {
                    lightPrimary = colorScheme.primary
                    lightPrimaryContainer = colorScheme.primaryContainer
                }
            }
            DarkMatterTheme(darkTheme = true) {
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
            DarkMatterTheme(darkTheme = true, amoled = true) {
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
            assertEquals(Color(0xFF06B6D4), s.inversePrimary)
            // Tonal-elevation overlay must be a no-op on AMOLED.
            assertEquals(Color.Transparent, s.surfaceTint)
        }
    }

    /** The locked brand primary stays cyan even on the AMOLED variant (#446). */
    @Test
    fun amoledThemeKeepsBrandPrimaryCyan() {
        var primary: Color? = null
        var primaryContainer: Color? = null

        composeRule.setContent {
            DarkMatterTheme(darkTheme = true, amoled = true) {
                val colorScheme = MaterialTheme.colorScheme
                SideEffect {
                    primary = colorScheme.primary
                    primaryContainer = colorScheme.primaryContainer
                }
            }
        }

        composeRule.runOnIdle {
            val expected = Color(0xFF06B6D4)
            assertEquals(expected, primary)
            assertEquals(expected, primaryContainer)
        }
    }
}
