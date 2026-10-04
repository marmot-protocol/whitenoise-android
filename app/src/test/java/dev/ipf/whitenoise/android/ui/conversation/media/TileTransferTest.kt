package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentCancellationState
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The control every image, video and voice tile shows for its transfer, and the state it is derived from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
class TileTransferTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** A queued or running body is cancellable and determinate only while the engine reports a trustworthy total. */
    @Test
    fun anActiveTransferIsCancellableAndDeterminateOnlyWithATrustworthyTotal() {
        val known = transfer(AttachmentTransferState.Downloading, progress(received = 4, total = 8))
        assertTrue(known.visible && known.active)
        assertFalse(known.cancelling || known.cancelled || known.failed)
        assertEquals(0.5f, known.fraction)

        val unknownTotal = transfer(AttachmentTransferState.Downloading, progress(received = 4, total = null))
        assertTrue(unknownTotal.active)
        assertNull(unknownTotal.fraction)

        val verifying =
            transfer(AttachmentTransferState.Downloading, progress(received = 8, total = 8, verifying = true))
        assertTrue(verifying.active)
        assertNull(verifying.fraction)
    }

    /** The feed opens for a materializing tile or a download another surface started, never for an idle one. */
    @Test
    fun theEngineFeedOpensOnlyForMaterializingOrSharedDownloads() {
        val downloading = AttachmentTransferState.Downloading
        assertTrue(tileObservesNative(materializing = true, mine = false, host = AttachmentTransferState.Remote))
        assertTrue("a download another surface started must show bytes", tileObservesNative(false, false, downloading))
        assertFalse(tileObservesNative(materializing = false, mine = false, host = AttachmentTransferState.Remote))
        assertFalse(tileObservesNative(false, false, AttachmentTransferState.Cancelled))
        assertFalse("own sends have no download to show", tileObservesNative(true, true, downloading))
    }

    /** Each step owns exactly one meaning, and an idle or finished attachment shows no control. */
    @Test
    fun cancelFailureAndIdleStatesAreDistinct() {
        val pending = transfer(AttachmentTransferState.Downloading, cancellation = AttachmentCancellationState.Pending)
        assertTrue(pending.visible && pending.cancelling)
        assertFalse(pending.active)
        assertNull(pending.fraction)

        val unconfirmed =
            transfer(AttachmentTransferState.Failed, cancellation = AttachmentCancellationState.Unconfirmed)
        assertTrue(unconfirmed.visible && unconfirmed.failed)

        val cancelled = transfer(AttachmentTransferState.Cancelled)
        assertTrue(cancelled.visible && cancelled.cancelled)
        assertFalse(cancelled.failed)

        val failed = transfer(AttachmentTransferState.Failed)
        assertTrue(failed.visible && failed.failed)

        val idleStates =
            listOf(
                AttachmentTransferState.Resolving,
                AttachmentTransferState.Remote,
                AttachmentTransferState.Available,
                AttachmentTransferState.NotRetained,
            )
        for (idle in idleStates) {
            assertFalse(idle.name, transfer(idle).visible)
        }
    }

    /** A tile whose own materialization failed offers Retry even while the host has not published a failure. */
    @Test
    fun aLocalMaterializationFailureOffersRetryWhateverTheHostSays() {
        val failed = transfer(AttachmentTransferState.Resolving, failedLocally = true)
        assertTrue(failed.failed)
        assertTrue(failed.visible)
        assertFalse(failed.active)
        render(failed)
        composeRule.onNodeWithContentDescription("Tap to retry").assertHasClickAction()
    }

    /** A restarted transfer is not hidden by a stale Cancelled, but a Cancel just made still reads as Cancelled. */
    @Test
    fun aRestartedTransferIsNotHiddenByAStaleCancelled() {
        val running = progress(received = 1, total = 8)
        val cancelled = AttachmentTransferState.Cancelled
        assertEquals(AttachmentTransferState.Remote, tileHostState(cancelled, running, materializing = true))
        assertEquals(AttachmentTransferState.Remote, tileHostState(cancelled, null, materializing = true))
        assertEquals(cancelled, tileHostState(cancelled, null, materializing = false))
        val confirmed = progress(received = 1, total = 8, phase = AttachmentTransferStateFfi.CANCELLED)
        assertEquals(cancelled, tileHostState(cancelled, confirmed, materializing = true))
        assertEquals(AttachmentTransferState.Failed, tileHostState(AttachmentTransferState.Failed, running, true))
    }

    /** The active control names the real bytes, exposes a determinate range and cancels on one tap. */
    @Test
    fun theActiveControlDescribesBytesCancelsOnTapAndIsAtLeast48Dp() {
        var cancels = 0
        val body = progress(received = 4, total = 8)
        val active = transfer(AttachmentTransferState.Downloading, body, onCancel = { cancels++ })
        render(active)

        val control = composeRule.onNodeWithContentDescription("4.0 MB of 8.0 MB")
        control.assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertCountEquals(1)
        control.performClick()

        assertEquals(1, cancels)
    }

    /** An unknown length says only what was received, and a verification phase never reads as complete. */
    @Test
    fun anUnknownLengthNamesReceivedBytesAndAVerificationPhaseNamesItself() {
        render(transfer(AttachmentTransferState.Downloading, progress(received = 4, total = null)))
        composeRule.onNodeWithContentDescription("4.0 MB received").assertExists()
        composeRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertCountEquals(0)
    }

    /** While Cancel awaits acknowledgement the control says so and offers no second action. */
    @Test
    fun aPendingCancelOffersNoSecondAction() {
        render(transfer(AttachmentTransferState.Downloading, cancellation = AttachmentCancellationState.Pending))

        composeRule.onNodeWithContentDescription(string(R.string.media_cancelling_download)).assertHasNoClickAction()
    }

    /** A confirmed Cancel offers Download again on one tap and no Cancel. */
    @Test
    fun aConfirmedCancelOffersDownloadAgain() {
        val cancelled = transfer(AttachmentTransferState.Cancelled)
        assertOneRetryingTap(cancelled, R.string.media_download_cancelled)
    }

    /** A failed transfer offers Retry on one tap and no Cancel. */
    @Test
    fun aFailedTransferOffersRetry() {
        val failed = transfer(AttachmentTransferState.Failed)
        assertOneRetryingTap(failed, R.string.media_tap_to_retry)
    }

    /** A Cancel the engine did not confirm says so and offers Retry on one tap. */
    @Test
    fun anUnconfirmedCancelOffersRetry() =
        assertOneRetryingTap(
            transfer(AttachmentTransferState.Failed, cancellation = AttachmentCancellationState.Unconfirmed),
            R.string.media_cancel_unconfirmed,
        )

    /** The target stays at least 48 dp at the largest font scale on a narrow right-to-left screen. */
    @Test
    @Config(qualifiers = "ar-rEG-w320dp-h640dp-mdpi")
    fun theTargetStaysAtLeast48DpInRtl() {
        render(transfer(AttachmentTransferState.Downloading, progress(received = 4, total = 8)), fontScale = 1.6f)

        composeRule.onNode(hasClickAction()).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    /** Taps the control once and requires exactly one retry and no Cancel on screen. */
    private fun assertOneRetryingTap(
        transfer: TileTransfer,
        descriptionId: Int,
    ) {
        var retries = 0
        render(transfer, onRetry = { retries++ })

        composeRule.onNodeWithContentDescription(string(descriptionId)).assertWidthIsAtLeast(48.dp).performClick()

        assertEquals(1, retries)
        composeRule.onAllNodesWithContentDescription(string(R.string.media_cancel_download)).assertCountEquals(0)
    }

    /** Composes only the control, with a caption, so semantics and size are asserted without a tile around it. */
    private fun render(
        transfer: TileTransfer,
        onRetry: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                TileTransferControl(transfer, onRetry = onRetry, showCaption = true)
            }
        }
        composeRule.waitForIdle()
    }

    /** One tile transfer with the given step; Cancel defaults to a no-op. */
    private fun transfer(
        state: AttachmentTransferState,
        progress: NativeAttachmentProgress? = null,
        cancellation: AttachmentCancellationState = AttachmentCancellationState.None,
        failedLocally: Boolean = false,
        onCancel: () -> Unit = {},
    ) = TileTransfer(
        state,
        progress,
        cancellation,
        suppressed = false,
        failedLocally = failedLocally,
        onCancel = onCancel,
    )

    /** The native phase of a body that is still arriving: verifying once every byte is in, otherwise downloading. */
    private fun receiving(verifying: Boolean): AttachmentTransferStateFfi =
        if (verifying) {
            AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT
        } else {
            AttachmentTransferStateFfi.DOWNLOADING
        }

    /** A native observation of one body, in MB. */
    private fun progress(
        received: Long,
        total: Long?,
        verifying: Boolean = false,
        phase: AttachmentTransferStateFfi? = null,
    ) = NativeAttachmentProgress(
        phase ?: receiving(verifying),
        1u,
        (received * MB).toULong(),
        total?.let { (it * MB).toULong() },
        null,
        "body",
    )

    /** A localized string from the app under test. */
    private fun string(id: Int): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.getString(id)
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
