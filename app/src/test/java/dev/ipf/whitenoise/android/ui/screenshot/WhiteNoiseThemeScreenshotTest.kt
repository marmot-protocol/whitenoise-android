package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Theme regression guard. Renders one tiny, stable swatch through
 * [WhiteNoiseTheme] in its three visual modes — light, dark, and AMOLED — so a
 * change to the color roles (e.g. the AMOLED true-black audit, #446/#495) shows
 * up as a pixel diff rather than slipping through. The content is deliberately
 * small and fixed-width so each baseline PNG stays well under 100 KB.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class WhiteNoiseThemeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightTheme() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ThemeSwatch()
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/white_noise_theme_light.png")
    }

    @Test
    fun darkTheme() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = false) {
                ThemeSwatch()
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/white_noise_theme_dark.png")
    }

    @Test
    fun amoledTheme() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                ThemeSwatch()
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/white_noise_theme_amoled.png")
    }
}

/**
 * A compact slice of the theme's color roles: the page surface/onSurface, a
 * primary [Button], and a secondaryContainer [Surface]. Defined here in the
 * test source set so production code stays untouched.
 */
@Composable
private fun ThemeSwatch() {
    Surface(
        modifier = Modifier.width(220.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "White Noise", fontSize = 18.sp)
            Button(onClick = {}) {
                Text(text = "Primary")
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(text = "Container", modifier = Modifier.padding(12.dp))
            }
        }
    }
}
