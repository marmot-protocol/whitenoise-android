package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteAttempt
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteFailureCategory
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteFailureNotice
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteOperationKind
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteOperationOutcome
import dev.ipf.whitenoise.android.ui.conversation.BatchDeleteRetryState
import dev.ipf.whitenoise.android.ui.conversation.BatchMessageActionItem
import dev.ipf.whitenoise.android.ui.conversation.BatchMessageSelection
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual contract for scoped batch-delete failure recovery (#2017). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class BatchDeleteFailureNoticeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mixedFailureDark() {
        render(
            state = retryState(succeeded = 2, localFailures = 1, groupFailures = 1),
            width = 360,
            fontScale = 1f,
            darkTheme = true,
        )

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/batch_delete_failure_notice_mixed_dark.png")
    }

    @Test
    fun totalLocalFailureNarrowLargeText() {
        render(
            state = retryState(succeeded = 0, localFailures = 3, groupFailures = 0),
            width = 280,
            fontScale = 1.6f,
            darkTheme = false,
        )

        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/batch_delete_failure_notice_local_large_text_light.png")
    }

    @Test
    fun recoveryActionsRemainAdjacentAndDispatchOnce() {
        var retries = 0
        var dismissals = 0
        var copies = 0
        render(
            state = retryState(succeeded = 1, localFailures = 1, groupFailures = 0),
            width = 360,
            fontScale = 1f,
            darkTheme = false,
            onRetry = { retries++ },
            onDismiss = { dismissals++ },
            onCopyReport = { copies++ },
        )

        composeRule.onNodeWithText("Copy").performClick()
        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(1, copies)
        assertEquals(1, dismissals)
        assertEquals(1, retries)
    }

    private fun render(
        state: BatchDeleteRetryState,
        width: Int,
        fontScale: Float,
        darkTheme: Boolean,
        onRetry: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onCopyReport: () -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WhiteNoiseTheme(darkTheme = darkTheme) {
                    Surface(modifier = Modifier.width(width.dp).testTag(TAG)) {
                        BatchDeleteFailureNotice(
                            state = state,
                            retryInFlight = false,
                            onRetry = onRetry,
                            onDismiss = onDismiss,
                            onCopyReport = onCopyReport,
                        )
                    }
                }
            }
        }
    }

    private fun retryState(
        succeeded: Int,
        localFailures: Int,
        groupFailures: Int,
    ): BatchDeleteRetryState {
        val successfulAttempts =
            (0 until succeeded).map { index -> attempt("success-$index", BatchDeleteOperationKind.HideLocally) }
        val local =
            (0 until localFailures).map { index ->
                BatchDeleteOperationOutcome(
                    attempt("local-$index", BatchDeleteOperationKind.HideLocally),
                    BatchDeleteFailureCategory.Io,
                )
            }
        val group =
            (0 until groupFailures).map { index ->
                BatchDeleteOperationOutcome(
                    attempt("group-$index", BatchDeleteOperationKind.DeleteForEveryone),
                    BatchDeleteFailureCategory.PermissionDenied,
                )
            }
        return BatchDeleteRetryState(
            originalAttempts = successfulAttempts + local.map { it.attempt } + group.map { it.attempt },
            failures = local + group,
        )
    }

    private fun attempt(
        id: String,
        operation: BatchDeleteOperationKind,
    ): BatchDeleteAttempt {
        val record =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = "received",
                groupIdHex = "group",
                sender = "alice",
                plaintext = "not rendered",
                contentTokens =
                    MarkdownDocumentFfi(
                        truncated = false,
                        blocks = emptyList(),
                        blankLinesBefore = ByteArray(0),
                    ),
                kind = 9uL,
                tags = emptyList(),
                sourceEpoch = null,
                retentionSeconds = null,
                retentionExpiresAt = null,
                recordedAt = 1uL,
                receivedAt = 1uL,
            )
        return BatchDeleteAttempt(
            selection =
                BatchMessageSelection(
                    action =
                        BatchMessageActionItem(
                            messageId = id,
                            senderId = "alice",
                            senderDisplayName = "Alice",
                            copyableText = null,
                            forwardableText = null,
                            canDeleteForEveryone = operation == BatchDeleteOperationKind.DeleteForEveryone,
                        ),
                    record = record,
                    status = MessageStatus.Received,
                    timelineOrder = 1uL,
                ),
            operation = operation,
        )
    }

    private companion object {
        const val TAG = "batch-delete-failure-screenshot"
    }
}
