package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.BatchSelectionActionAvailability
import dev.ipf.whitenoise.android.ui.conversation.MessageSelectionBottomBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageSelectionBottomBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun allActionsDark() {
        render(width = 360.dp, fontScale = 1f, darkTheme = true)

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_selection_bottom_bar_all_actions_dark.png")
    }

    @Test
    fun narrowLargeTextLight() {
        render(width = 240.dp, fontScale = 1.6f, darkTheme = false)

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_selection_bottom_bar_narrow_large_text_light.png")
    }

    private fun render(
        width: Dp,
        fontScale: Float,
        darkTheme: Boolean,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(
                        modifier =
                            Modifier
                                .width(width)
                                .testTag(TAG),
                    ) {
                        MessageSelectionBottomBar(
                            availability =
                                BatchSelectionActionAvailability(
                                    canCopy = true,
                                    canForward = true,
                                    canSave = true,
                                    canReply = true,
                                    canInfo = true,
                                    canDelete = true,
                                ),
                            onCopy = {},
                            onForward = {},
                            onSave = {},
                            onReply = {},
                            onInfo = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "message-selection-bottom-bar"
    }
}
