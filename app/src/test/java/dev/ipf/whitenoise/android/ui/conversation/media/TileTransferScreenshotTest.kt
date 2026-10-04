@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.state.AttachmentCancellationState
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pins every transfer step of the image, video and voice tile controls across themes, RTL and large fonts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h1400dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class TileTransferScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    /** Light theme with the default font scale. */
    @Test
    fun tileTransferLight() = capture("light")

    /** Dark theme keeps the same controls, captions and 48 dp targets. */
    @Test
    fun tileTransferDark() = capture("dark", dark = true)

    /** Large type stays bounded on a narrow right-to-left surface. */
    @Test
    fun tileTransferLargeRtl() = capture("large-rtl", rtl = true, fontScale = 1.6f)

    /** The same large right-to-left states remain readable in dark mode. */
    @Test
    fun tileTransferDarkLargeRtl() = capture("dark-large-rtl", dark = true, rtl = true, fontScale = 1.6f)

    /** Renders the gallery, checks the step semantics a reader hears, then records the baseline. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        fontScale: Float = 1f,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, fontScale = fontScale) {
                val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) { Gallery() }
            }
        }
        // Each step is heard once per context: the photo tile, the grid tile and the voice row.
        composeRule.onAllNodesWithContentDescription("Cancelling download").assertCountEquals(3)
        composeRule.onAllNodesWithContentDescription("3.0 MB of 8.0 MB").assertCountEquals(3)
        composeRule.onAllNodesWithContentDescription("3.0 MB received").assertCountEquals(3)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/tile-transfer-$name.png")
    }

    /** One column per context: a photo tile with a caption, a small grid tile and the voice row. */
    @Composable
    private fun Gallery() {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(8.dp),
        ) {
            for (transfer in steps()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhotoTile(transfer)
                    GridTile(transfer)
                }
                VoiceRow(transfer)
            }
        }
    }

    /** A photo tile: placeholder pixels with the control and its caption centered, as the real tile draws them. */
    @Composable
    private fun PhotoTile(transfer: TileTransfer) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(width = 190.dp, height = 110.dp).background(PLACEHOLDER),
        ) {
            TileTransferControl(transfer, onRetry = {}, showCaption = true)
        }
    }

    /** An album grid tile: the control without a caption over a small placeholder. */
    @Composable
    private fun GridTile(transfer: TileTransfer) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp).background(PLACEHOLDER)) {
            TileTransferControl(transfer, onRetry = {})
        }
    }

    /** The production voice row for a clip that is not local, in the given transfer step. */
    @Composable
    private fun VoiceRow(transfer: TileTransfer) {
        VoiceAttachmentContent(
            loading = true,
            failed = false,
            startDownload = true,
            localFileAvailable = false,
            isPlaying = false,
            isPaused = false,
            activePositionMs = 0,
            activeDurationMs = 0,
            totalDurationMs = 0,
            progressFraction = 0f,
            outgoing = false,
            onLongPress = {},
            onActionClick = {},
            transfer = transfer,
        )
    }

    /** Every step a tile can show: known and unknown length, verifying, pending, confirmed and failed. */
    private fun steps(): List<TileTransfer> =
        listOf(
            tile(AttachmentTransferState.Downloading, body(received = 3, total = 8)),
            tile(AttachmentTransferState.Downloading, body(received = 3, total = null)),
            tile(
                AttachmentTransferState.Downloading,
                body(received = 8, total = 8, phase = AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT),
            ),
            tile(AttachmentTransferState.Downloading, null, AttachmentCancellationState.Pending),
            tile(AttachmentTransferState.Cancelled, null),
            tile(AttachmentTransferState.Failed, null),
        )

    /** One tile transfer in the given step with Cancel as a no-op. */
    private fun tile(
        state: AttachmentTransferState,
        progress: NativeAttachmentProgress?,
        cancellation: AttachmentCancellationState = AttachmentCancellationState.None,
    ) = TileTransfer(state, progress, cancellation, suppressed = false, onCancel = {})

    /** A native observation of one body, in MB. */
    private fun body(
        received: Long,
        total: Long?,
        phase: AttachmentTransferStateFfi = AttachmentTransferStateFfi.DOWNLOADING,
    ) = NativeAttachmentProgress(
        phase,
        1u,
        (received * MB).toULong(),
        total?.let { (it * MB).toULong() },
        null,
        "body",
    )

    private companion object {
        const val MB = 1024L * 1024L
        val PLACEHOLDER = Color(0xFF8A8F98)
    }
}
