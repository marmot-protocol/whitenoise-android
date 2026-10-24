package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.ForwardFailureStage
import dev.ipf.whitenoise.android.state.ForwardOperationPhase
import dev.ipf.whitenoise.android.state.ForwardOperationSnapshot
import dev.ipf.whitenoise.android.state.ForwardTargetPhase
import dev.ipf.whitenoise.android.state.ForwardTargetProgress
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardOperationStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardProgressContent
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
class ForwardProgressScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun multiTargetTransferProgressLight() {
        capture(
            name = "forward_progress_multi_target_light",
            snapshot =
                snapshot(
                    phase = ForwardOperationPhase.Running,
                    ForwardTargetProgress("family", ForwardTargetPhase.Uploading, 2, 4, 0, 3),
                    ForwardTargetProgress("work", ForwardTargetPhase.Sending, 4, 4, 1, 3),
                ),
        )
    }

    @Test
    fun persistentActiveForwardStatusLight() {
        val snapshot =
            snapshot(
                phase = ForwardOperationPhase.Running,
                ForwardTargetProgress("family", ForwardTargetPhase.Uploading, 2, 4, 0, 3),
                ForwardTargetProgress("work", ForwardTargetPhase.Waiting, 0, 4, 0, 3),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.testTag(TAG)) {
                    ForwardOperationStatus(
                        snapshot = snapshot,
                        targetTitles = mapOf("family" to "Family", "work" to "Design team"),
                        onCancel = {},
                        onRetry = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/forward_operation_status_active_light.png")
    }

    @Test
    fun actionablePartialFailureDark() {
        capture(
            name = "forward_progress_partial_failure_dark",
            darkTheme = true,
            snapshot =
                snapshot(
                    phase = ForwardOperationPhase.PartialFailure,
                    ForwardTargetProgress("family", ForwardTargetPhase.Completed, 4, 4, 3, 3),
                    ForwardTargetProgress(
                        "work",
                        ForwardTargetPhase.Failed,
                        4,
                        4,
                        1,
                        3,
                        ForwardFailureStage.Publish,
                    ),
                ),
        )
    }

    /** Captures the terminal APK-preparation deadline with its actionable failure copy. */
    @Test
    fun preparationTimeoutDark() {
        capture(
            name = "forward_progress_preparation_timeout_dark",
            darkTheme = true,
            snapshot =
                snapshot(
                    phase = ForwardOperationPhase.Failed,
                    ForwardTargetProgress(
                        "family",
                        ForwardTargetPhase.Failed,
                        0,
                        1,
                        0,
                        1,
                        ForwardFailureStage.PreparationTimeout,
                    ),
                ),
        )
    }

    @Test
    fun cancelledLargeTextRtl() {
        capture(
            name = "forward_progress_cancelled_large_rtl",
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Rtl,
            snapshot =
                snapshot(
                    phase = ForwardOperationPhase.Cancelled,
                    ForwardTargetProgress("family", ForwardTargetPhase.Cancelled, 1, 2, 0, 1),
                ),
        )
    }

    /** A send past the slow threshold keeps its spinner and says it can no longer be cancelled or sent twice. */
    @Test
    fun slowSendLight() {
        capture(
            name = "forward_progress_slow_send_light",
            snapshot =
                snapshot(
                    phase = ForwardOperationPhase.Running,
                    ForwardTargetProgress("family", ForwardTargetPhase.Sending, 1, 1, 0, 1, slow = true),
                    ForwardTargetProgress("work", ForwardTargetPhase.Uploading, 0, 1, 0, 1, slow = true),
                ),
        )
    }

    /** The slow notices stay readable at large text in a right-to-left dark layout. */
    @Test
    fun slowSendDarkLargeTextRtl() {
        capture(
            name = "forward_progress_slow_send_dark_large_rtl",
            darkTheme = true,
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Rtl,
            snapshot =
                snapshot(
                    phase = ForwardOperationPhase.Running,
                    ForwardTargetProgress("family", ForwardTargetPhase.Sending, 1, 1, 0, 1, slow = true),
                ),
        )
    }

    /** Slow source preparation replaces the attachment count under the preparation bar. */
    @Test
    fun slowPreparationLight() {
        capture(
            name = "forward_progress_slow_preparation_light",
            snapshot =
                snapshot(
                    phase = ForwardOperationPhase.Preparing,
                    ForwardTargetProgress("family", ForwardTargetPhase.Waiting, 0, 2, 0, 1),
                ).copy(preparationSlow = true),
        )
    }

    /** An upload that never started shows its own actionable failure text. */
    @Test
    fun uploadTimeoutDark() {
        capture(
            name = "forward_progress_upload_timeout_dark",
            darkTheme = true,
            snapshot =
                snapshot(
                    phase = ForwardOperationPhase.Failed,
                    ForwardTargetProgress(
                        "family",
                        ForwardTargetPhase.Failed,
                        0,
                        1,
                        0,
                        1,
                        ForwardFailureStage.UploadTimeout,
                    ),
                ),
        )
    }

    private fun capture(
        name: String,
        snapshot: ForwardOperationSnapshot,
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.testTag(TAG)) {
                        ForwardProgressContent(
                            snapshot = snapshot,
                            targetTitles = mapOf("family" to "Family", "work" to "Design team"),
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private fun snapshot(
        phase: ForwardOperationPhase,
        vararg targets: ForwardTargetProgress,
    ) = ForwardOperationSnapshot(
        phase = phase,
        preparedAttachments = targets.maxOfOrNull(ForwardTargetProgress::uploadedAttachments) ?: 0,
        totalAttachments = targets.maxOfOrNull(ForwardTargetProgress::totalAttachments) ?: 0,
        targets = targets.toList(),
    )

    private companion object {
        const val TAG = "forward-progress"
    }
}
