package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.AgentOperationPresentation
import dev.ipf.whitenoise.android.ui.conversation.AgentOperationRow
import dev.ipf.whitenoise.android.ui.conversation.AgentOperationSenderPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class AgentOperationRowScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toolCallExpandsFromCollapsedPreviewToFullDetails() {
        val operation =
            AgentOperationPresentation(
                eventType = "tool_call",
                name = "mcp__fff_whitenoise_android__grep",
                text = "⚙️ mcp__fff_whitenoise_android__grep: AgentOperation",
                preview = "AgentOperation\napp/src/main/java/dev/ipf/whitenoise/android",
                argumentsJson = "{\n  \"query\": \"AgentOperation\",\n  \"path\": \"app/src/main\"\n}",
                status = "completed",
                ok = true,
                durationMs = 1250L,
            )

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    Column(
                        modifier = Modifier.width(360.dp).padding(8.dp).testTag(TAG),
                    ) {
                        AgentOperationRow(
                            messageId = "operation-1",
                            operation = operation,
                            mine = false,
                            sender =
                                AgentOperationSenderPresentation(
                                    name = "Ada Lovelace",
                                    seed = "ada",
                                    avatarUrl = null,
                                ),
                            modifier = Modifier.testTag(CHIP_TAG),
                        )
                        AgentOperationRow(
                            messageId = "operation-2",
                            operation = operation.copy(name = "mcp__fff_whitenoise_android__read_file"),
                            mine = false,
                            sender =
                                AgentOperationSenderPresentation(
                                    name = "Grace Hopper",
                                    seed = "grace",
                                    avatarUrl = null,
                                ),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithText("Grace Hopper").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/agent_operation_row_collapsed_light.png")
        composeRule.onNodeWithTag(CHIP_TAG).performClick()
        composeRule.onNodeWithText("Message").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/agent_operation_row_expanded_light.png")
    }

    @Test
    fun longPressKeepsDeleteActionReachable() {
        var deleteRequests = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                AgentOperationRow(
                    messageId = "operation-delete",
                    operation = sampleOperation(),
                    onRequestDelete = { deleteRequests += 1 },
                    modifier = Modifier.testTag(CHIP_TAG),
                )
            }
        }

        composeRule.onNodeWithText("⚙").assertDoesNotExist()
        composeRule.onNodeWithTag(CHIP_TAG).performTouchInput { longClick() }

        assertEquals(1, deleteRequests)
    }

    private fun sampleOperation() =
        AgentOperationPresentation(
            eventType = "tool_call",
            name = "grep",
            text = "Searching",
            preview = "needle",
            argumentsJson = null,
            status = "started",
            ok = null,
            durationMs = null,
        )

    private companion object {
        const val TAG = "agent-operation-rows"
        const val CHIP_TAG = "agent-operation-chip"
    }
}
