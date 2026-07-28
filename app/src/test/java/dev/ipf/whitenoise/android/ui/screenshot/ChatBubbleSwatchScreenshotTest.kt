package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.BubbleTheme
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.OPAQUE_WHITE_ARGB
import dev.ipf.whitenoise.android.ui.settings.TonalSwatchPicker
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val SWATCH_FIXTURE_TAG = "chat-bubble-swatch"

/**
 * Quick-swatch picker baselines for light, dark, and AMOLED. Each image shows
 * black selected (unselected white visible) and white selected (unselected black
 * visible) without a separate capture per swatch state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatBubbleSwatchScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun lightThemeBlackAndWhiteSwatches() {
        render(darkTheme = false, amoled = false)
        composeRule
            .onNodeWithTag(SWATCH_FIXTURE_TAG)
            .captureRoboImage("src/test/snapshots/chat_bubble_swatch_light.png")
    }

    @Test
    fun darkThemeBlackAndWhiteSwatches() {
        render(darkTheme = true, amoled = false)
        composeRule
            .onNodeWithTag(SWATCH_FIXTURE_TAG)
            .captureRoboImage("src/test/snapshots/chat_bubble_swatch_dark.png")
    }

    @Test
    fun amoledThemeBlackAndWhiteSwatches() {
        render(darkTheme = true, amoled = true)
        composeRule
            .onNodeWithTag(SWATCH_FIXTURE_TAG)
            .captureRoboImage("src/test/snapshots/chat_bubble_swatch_amoled.png")
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                Surface(
                    modifier = Modifier.width(360.dp),
                    color = if (amoled) Color.Black else MaterialTheme.colorScheme.surface,
                ) {
                    BlackWhiteSwatchFixture(
                        theme =
                            when {
                                amoled -> BubbleTheme.Amoled
                                darkTheme -> BubbleTheme.Dark
                                else -> BubbleTheme.Light
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun BlackWhiteSwatchFixture(theme: BubbleTheme) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag(SWATCH_FIXTURE_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Black selected", style = MaterialTheme.typography.labelLarge)
        TonalSwatchPicker(
            selectedArgb = OPAQUE_BLACK_ARGB,
            onColorSelected = {},
            scopeKey = "screenshot-black",
            theme = theme,
            slotKey = "mine",
        )
        Text("White selected", style = MaterialTheme.typography.labelLarge)
        TonalSwatchPicker(
            selectedArgb = OPAQUE_WHITE_ARGB,
            onColorSelected = {},
            scopeKey = "screenshot-white",
            theme = theme,
            slotKey = "other",
        )
    }
}
