package dev.ipf.darkmatter.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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
            DarkMatterTheme(darkTheme = false, amoled = true) {
                val isAmoled = isAmoledSurfaceTheme()
                SideEffect { lightWithAmoledPreference = isAmoled }
            }
            DarkMatterTheme(darkTheme = true, amoled = false) {
                val isAmoled = isAmoledSurfaceTheme()
                SideEffect { standardDark = isAmoled }
            }
            DarkMatterTheme(darkTheme = true, amoled = true) {
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
            DarkMatterTheme(darkTheme = true, amoled = false) {
                val border = amoledSurfaceBorderStroke()
                SideEffect { standardDarkBorder = border }
            }
            DarkMatterTheme(darkTheme = true, amoled = true) {
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
    fun amoledBorderTokensAreDimNeutralGreys() {
        assertEquals(Color(0xFF242424), AmoledSurfaceBorder)
        assertEquals(Color(0xFF2A2A2A), AmoledEmphasizedSurfaceBorder)
    }
}
