package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** How an empty conversation reads with a retention policy and without one (#2674). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationEmptyStateScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The retention wording explains the policy; the ordinary wording stays unchanged. */
    @Test
    fun retentionExplainsItselfWhileTheOrdinaryEmptyStateStays() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(modifier = Modifier.testTag(TAG)) {
                    Column {
                        Surface(modifier = Modifier.height(BLOCK_HEIGHT_DP.dp)) {
                            ConversationEmptyMessage(ConversationEmptyState.NoMessages)
                        }
                        Surface(modifier = Modifier.height(BLOCK_HEIGHT_DP.dp)) {
                            ConversationEmptyMessage(
                                ConversationEmptyState.DisappearingMessages(retentionSeconds = 86_400L),
                            )
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage(SNAPSHOT)
    }

    private companion object {
        const val TAG = "conversation-empty-states"
        const val BLOCK_HEIGHT_DP = 160
        const val SNAPSHOT = "src/test/snapshots/conversation_empty_states_light.png"
    }
}
