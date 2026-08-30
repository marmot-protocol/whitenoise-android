package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.state.ForwardFailureStage
import dev.ipf.whitenoise.android.state.ForwardOperationPhase
import dev.ipf.whitenoise.android.state.ForwardOperationSnapshot
import dev.ipf.whitenoise.android.state.ForwardTargetPhase
import dev.ipf.whitenoise.android.state.ForwardTargetProgress
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
class ForwardOperationStatusInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeForwardKeepsCancellationInsideTappableDetails() {
        var cancellations = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ForwardOperationStatus(
                        snapshot =
                            ForwardOperationSnapshot(
                                phase = ForwardOperationPhase.Running,
                                preparedAttachments = 2,
                                totalAttachments = 4,
                                targets =
                                    listOf(
                                        ForwardTargetProgress(
                                            groupIdHex = "family",
                                            phase = ForwardTargetPhase.Uploading,
                                            uploadedAttachments = 2,
                                            totalAttachments = 4,
                                            totalMessages = 3,
                                        ),
                                        ForwardTargetProgress(
                                            groupIdHex = "work",
                                            phase = ForwardTargetPhase.Waiting,
                                            totalAttachments = 4,
                                            totalMessages = 3,
                                        ),
                                    ),
                            ),
                        targetTitles = mapOf("family" to "Family", "work" to "Design team"),
                        onCancel = { cancellations += 1 },
                        onRetry = {},
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Forwarding").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()

        composeRule.onNodeWithTag(FORWARD_OPERATION_STATUS_TEST_TAG).performClick()
        composeRule.onNodeWithText("Family").assertIsDisplayed()
        composeRule.onNodeWithText("Design team").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test
    fun failedForwardExposesRetryAndDismissButNotCancellation() {
        var retries = 0
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ForwardOperationStatus(
                        snapshot =
                            ForwardOperationSnapshot(
                                phase = ForwardOperationPhase.PartialFailure,
                                preparedAttachments = 1,
                                totalAttachments = 1,
                                targets =
                                    listOf(
                                        ForwardTargetProgress(
                                            groupIdHex = "family",
                                            phase = ForwardTargetPhase.Completed,
                                            uploadedAttachments = 1,
                                            totalAttachments = 1,
                                            sentMessages = 1,
                                            totalMessages = 1,
                                        ),
                                        ForwardTargetProgress(
                                            groupIdHex = "work",
                                            phase = ForwardTargetPhase.Failed,
                                            totalAttachments = 1,
                                            totalMessages = 1,
                                            failureStage = dev.ipf.whitenoise.android.state.ForwardFailureStage.Upload,
                                        ),
                                    ),
                            ),
                        targetTitles = emptyMap(),
                        onCancel = {},
                        onRetry = { retries += 1 },
                        onDismiss = { dismissals += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, dismissals)
        }
    }

    /** A preparation deadline explains the failure and keeps explicit retry and dismissal available. */
    @Test
    fun preparationTimeoutExposesActionableDetails() {
        var retries = 0
        var dismissals = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    ForwardOperationStatus(
                        snapshot =
                            ForwardOperationSnapshot(
                                phase = ForwardOperationPhase.Failed,
                                preparedAttachments = 0,
                                totalAttachments = 1,
                                targets =
                                    listOf(
                                        ForwardTargetProgress(
                                            groupIdHex = "family",
                                            phase = ForwardTargetPhase.Failed,
                                            totalAttachments = 1,
                                            totalMessages = 1,
                                            failureStage = ForwardFailureStage.PreparationTimeout,
                                        ),
                                    ),
                            ),
                        targetTitles = mapOf("family" to "Family"),
                        onCancel = {},
                        onRetry = { retries += 1 },
                        onDismiss = { dismissals += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(FORWARD_OPERATION_STATUS_TEST_TAG).performClick()
        composeRule
            .onNodeWithText("Preparation timed out. Retry, or dismiss to stop forwarding.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithText("Close").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Retry").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Dismiss").assertIsDisplayed().performClick()
        composeRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, dismissals)
        }
    }
}
