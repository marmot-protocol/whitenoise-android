package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A search match must be obvious on the bubble it lands on, including the pale incoming bubble where
 * a thin outline alone is easy to miss.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MessageSearchHighlightScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The marker reads on a pale incoming bubble and on a tinted outgoing one alike. */
    @Test
    fun matchesAreMarkedOnLightAndTintedBubbles() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.padding(16.dp).testTag(TAG)) {
                    Column {
                        MarkedBubble(
                            background = MaterialTheme.colorScheme.surfaceContainerHigh,
                            content = MaterialTheme.colorScheme.onSurface,
                        )
                        MarkedBubble(
                            background = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage(SNAPSHOT)
    }

    @Composable
    private fun MarkedBubble(
        background: Color,
        content: Color,
    ) {
        val highlight =
            remember(content) {
                MessageSearchHighlight(needle = NEEDLE, fill = content.copy(alpha = MESSAGE_SEARCH_HIGHLIGHT_ALPHA))
            }
        val ranges = remember { messageSearchMatchRanges(BODY, NEEDLE) }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Surface(color = background, contentColor = content, shape = MaterialTheme.shapes.large) {
            Text(
                BODY,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .padding(12.dp)
                        .messageSearchHighlight(layout, ranges, highlight),
                onTextLayout = { layout = it },
            )
        }
    }

    private companion object {
        const val TAG = "search-highlight-bubbles"
        const val NEEDLE = "ferry"
        const val BODY = "The ferry leaves at six, so meet me by the ferry terminal."
        const val SNAPSHOT = "src/test/snapshots/message_search_highlight_bubbles_light.png"
    }
}
