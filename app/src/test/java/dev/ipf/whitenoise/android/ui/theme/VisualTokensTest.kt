package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class VisualTokensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scrimAlphaTokensPreserveExistingVisualLevels() {
        assertEquals(
            listOf(0.35f, 0.4f, 0.45f, 0.5f, 0.55f, 0.6f, 0.62f),
            listOf(
                ScrimAlpha.Light,
                ScrimAlpha.LightEmphasis,
                ScrimAlpha.Medium,
                ScrimAlpha.MediumEmphasis,
                ScrimAlpha.Strong,
                ScrimAlpha.Gradient,
                ScrimAlpha.Heavy,
            ),
        )
    }

    @Test
    fun appDividerUsesVisibleAmoledOutlineToken() {
        var expectedColor = Color.Unspecified
        var dividerColor = Color.Unspecified

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                val outlineVariant = MaterialTheme.colorScheme.outlineVariant
                val appDividerColor = appDividerColor()
                SideEffect {
                    expectedColor = outlineVariant
                    dividerColor = appDividerColor
                }
                AppDivider()
            }
        }

        composeRule.runOnIdle {
            assertNotEquals(Color.Black, expectedColor)
            assertEquals(expectedColor, dividerColor)
        }
    }
}
