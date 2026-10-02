package dev.ipf.whitenoise.android.media

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.state.AttachmentCancellationState
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubbleContent
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Platform rendering and accessibility evidence; HTTP and native acknowledgement use the separate held-body probe. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class AttachmentProgressDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val phase = mutableStateOf(AttachmentTransferStateFfi.QUEUED)
    private val total = mutableStateOf<ULong?>(1024u)
    private val cancellation = mutableStateOf(AttachmentCancellationState.None)
    private var cancels = 0

    /** Real platform text metrics retain truthful phase semantics and 48dp controls at 200% font scale in RTL. */
    @Test
    fun phaseAndCancellationSemanticsRemainAccessibleAtLargeFontRtl() {
        render()
        listOf(
            AttachmentTransferStateFfi.QUEUED to "Queued for download",
            AttachmentTransferStateFfi.DOWNLOADING to "512 B of 1.0 KB",
            AttachmentTransferStateFfi.RETRY_SCHEDULED to "Waiting to retry",
            AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT to "Verifying download",
            AttachmentTransferStateFfi.DECRYPTING to "Decrypting download",
            AttachmentTransferStateFfi.VERIFYING_PLAINTEXT to "Verifying download",
            AttachmentTransferStateFfi.PAUSED to "Download paused",
            AttachmentTransferStateFfi.READY to "Preparing attachment",
        ).forEach { (state, description) ->
            composeRule.runOnIdle { phase.value = state }
            assertControl(description)
        }
        composeRule.runOnIdle {
            phase.value = AttachmentTransferStateFfi.DOWNLOADING
            total.value = null
        }
        assertControl("512 B received")
        composeRule.onNodeWithContentDescription("512 B received").performClick()
        assertControl("Cancelling download")
        composeRule.onNodeWithContentDescription("Cancelling download").assertHasNoClickAction()
        composeRule.runOnIdle { cancellation.value = AttachmentCancellationState.Unconfirmed }
        assertControl("Could not confirm cancellation")
        composeRule.runOnIdle { assertEquals(1, cancels) }
    }

    /** Native readiness remains local preparation until the host has established usable plaintext. */
    private fun render() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                WhiteNoiseTheme(darkTheme = true) {
                    val reference = reference()
                    MediaFileBubbleContent(
                        reference,
                        resolveAttachmentPresentation(reference.mediaType, reference.fileName),
                        if (cancellation.value == AttachmentCancellationState.Unconfirmed) {
                            AttachmentTransferState.Failed
                        } else {
                            AttachmentTransferState.Downloading
                        },
                        nativeProgress = NativeAttachmentProgress(phase.value, 1u, 512u, total.value, null, "body"),
                        cancellationState = cancellation.value,
                        onCancelTransfer = {
                            cancels++
                            cancellation.value = AttachmentCancellationState.Pending
                        },
                    )
                }
            }
        }
    }

    /** A fully spoken state label and fixed control bounds survive visual truncation in a narrow card. */
    private fun assertControl(description: String) {
        val bounds = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        val minimumPixels = with(composeRule.density) { 48.dp.roundToPx() }
        assertTrue("control width: $bounds", bounds.width.roundToInt() >= minimumPixels)
        assertTrue("control height: $bounds", bounds.height.roundToInt() >= minimumPixels)
    }

    /** Generated metadata contains no user file or network-capable locator. */
    private fun reference() =
        MediaAttachmentReferenceFfi(
            emptyList(),
            "ab".repeat(32),
            "cd".repeat(32),
            "00".repeat(12),
            "fixture.pdf",
            "application/pdf",
            EncryptedMediaVersionFfi.V1,
            1u,
            null,
            null,
        )
}
