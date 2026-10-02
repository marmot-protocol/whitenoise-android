@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.state.AttachmentCancellationState
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.attachmentFilePresentationState
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubbleContent
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h1400dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentProgressScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    /** Checks real bytes, phase semantics and unconfirmed cancellation on narrow light-theme cards. */
    @Test
    fun progressLight() = captureProgress("light")

    /** Dark rendering uses the same fixed 48dp controls and localized phase descriptions. */
    @Test
    fun progressDark() = captureProgress("dark", dark = true)

    /** Large type remains bounded within the existing card chrome on a narrow RTL surface. */
    @Test
    fun progressLargeRtl() = captureProgress("large-rtl", rtl = true, fontScale = 1.6f)

    /** The same large RTL states remain readable in dark mode. */
    @Test
    fun progressDarkLargeRtl() = captureProgress("dark-large-rtl", dark = true, rtl = true, fontScale = 1.6f)

    /** Produces phase-specific cards; cancellation acknowledgement is independent of body completion. */
    private fun captureProgress(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) { ProgressGallery() }
            }
        }
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertCountEquals(1)
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(5)
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f))).assertCountEquals(0)
        composeRule.onNodeWithText("512 of 1024 bytes").assertExists()
        composeRule.onNodeWithText("512 bytes received").assertExists()
        composeRule.onNodeWithText("Download blocked by policy").assertExists()
        composeRule.onNodeWithText("Attachment unavailable").assertExists()
        composeRule.onNodeWithText("Preparing attachment").assertExists()
        composeRule.onNodeWithContentDescription("Cancelling download").assertExists()
        composeRule.onNodeWithContentDescription("Could not confirm cancellation. Tap to retry").assertExists()
        composeRule.onAllNodesWithContentDescription("Download cancelled. Tap to download again").assertCountEquals(0)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/attachment-progress-$name.png")
    }

    /** Renders native phases alongside separate cancellation acknowledgements. */
    @Composable
    private fun ProgressGallery() {
        val phases =
            listOf(
                AttachmentTransferStateFfi.QUEUED,
                AttachmentTransferStateFfi.DOWNLOADING,
                AttachmentTransferStateFfi.DOWNLOADING,
                AttachmentTransferStateFfi.RETRY_SCHEDULED,
                AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT,
                AttachmentTransferStateFfi.DECRYPTING,
                AttachmentTransferStateFfi.PAUSED,
                AttachmentTransferStateFfi.READY,
                AttachmentTransferStateFfi.POLICY_BLOCKED,
                AttachmentTransferStateFfi.UNAVAILABLE,
            )
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(8.dp)) {
            phases.forEachIndexed { index, phase ->
                val reference = fileReference("attachment-$index.pdf", "application/pdf")
                val progress =
                    NativeAttachmentProgress(
                        phase,
                        1u,
                        512u,
                        1024uL.takeUnless { index == 2 },
                        null,
                        "body",
                    )
                val host =
                    if (phase == AttachmentTransferStateFfi.READY) {
                        AttachmentTransferState.Resolving
                    } else {
                        attachmentFilePresentationState(
                            AttachmentTransferState.Remote,
                            progress,
                            AttachmentCancellationState.None,
                        )
                    }
                MediaFileBubbleContent(
                    reference,
                    resolveAttachmentPresentation(reference.mediaType, reference.fileName),
                    host,
                    nativeProgress = progress,
                    onCancelTransfer = {},
                )
            }
            CancellationGallery()
        }
    }

    /** Cancellation remains pending until native acknowledgement, and failure keeps an explicit Retry. */
    @Composable
    private fun CancellationGallery() {
        listOf(
            AttachmentCancellationState.Pending,
            AttachmentCancellationState.Unconfirmed,
        ).forEach { cancellation ->
            val reference = fileReference("cancel.pdf", "application/pdf")
            MediaFileBubbleContent(
                reference,
                resolveAttachmentPresentation(reference.mediaType, reference.fileName),
                if (cancellation ==
                    AttachmentCancellationState.Pending
                ) {
                    AttachmentTransferState.Downloading
                } else {
                    AttachmentTransferState.Failed
                },
                cancellationState = cancellation,
                onCancelTransfer = {},
            )
        }
    }
}
