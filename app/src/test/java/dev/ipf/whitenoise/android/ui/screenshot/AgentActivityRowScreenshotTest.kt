package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.AgentActivityPresentation
import dev.ipf.whitenoise.android.ui.conversation.AgentActivityRow
import dev.ipf.whitenoise.android.ui.conversation.AgentOperationSenderPresentation
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
class AgentActivityRowScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun commentaryRowsShowStatusAndGroupSender() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Column(
                        modifier = Modifier.width(360.dp).padding(8.dp).testTag(TAG),
                    ) {
                        AgentActivityRow(
                            activity =
                                AgentActivityPresentation(
                                    text = "Checking the review comments and screenshot coverage.",
                                    status = "commentary",
                                ),
                            mine = false,
                            sender =
                                AgentOperationSenderPresentation(
                                    name = "Ada Lovelace",
                                    seed = "ada",
                                    avatarUrl = null,
                                ),
                        )
                        AgentActivityRow(
                            activity =
                                AgentActivityPresentation(
                                    text = "The compatibility fix is ready.",
                                    status = "completed",
                                ),
                            mine = true,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/agent_activity_rows_light.png")
    }

    private companion object {
        const val TAG = "agent-activity-rows"
    }
}
