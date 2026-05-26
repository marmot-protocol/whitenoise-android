package dev.ipf.darkmatter.ui.theme

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
}
