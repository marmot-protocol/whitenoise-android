@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.messages

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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.ui.conversation.media.MediaFileBubbleContent
import dev.ipf.whitenoise.android.ui.conversation.media.TileTransferControl
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceAttachmentContent
import dev.ipf.whitenoise.android.ui.conversation.media.resolveAttachmentPresentation
import dev.ipf.whitenoise.android.ui.conversation.media.storageBlockedTileTransfer
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A received body MDK parked for lack of free space (#3289) presents as storage, not as an ordinary failure, on every
 * attachment surface: the file card, a captioned image tile, an album grid tile and the voice row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h1400dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class StorageBlockedAttachmentScreenshotTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    /** Light theme at the default font scale. */
    @Test
    fun storageBlockedLight() = capture("light")

    /** Dark theme keeps the error role on the file card and the scrim on tiles. */
    @Test
    fun storageBlockedDark() = capture("dark", dark = true)

    /** Large type stays inside the existing chrome on a narrow RTL surface. */
    @Test
    fun storageBlockedLargeRtl() = capture("large-rtl", rtl = true, fontScale = 1.6f)

    /** The same large RTL layout in dark mode. */
    @Test
    fun storageBlockedDarkLargeRtl() = capture("dark-large-rtl", dark = true, rtl = true, fontScale = 1.6f)

    /** Renders every surface, checks what a reader sees and hears, then records the baseline. */
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
        composeRule.onAllNodesWithText("Not enough storage").assertCountEquals(1)
        composeRule.onAllNodesWithText("Not enough storage. Free up space, then tap to retry.").assertCountEquals(1)
        // The photo tile, the grid tile and the voice action are heard as storage; the file card shows it as text.
        composeRule
            .onAllNodesWithContentDescription("Not enough storage. Free up space, then tap to retry.")
            .assertCountEquals(3)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/storage-blocked-attachment-$name.png")
    }

    /** One column: the file card, a captioned photo tile beside a grid tile, and the voice row. */
    @Composable
    private fun Gallery() {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(8.dp),
        ) {
            val reference = fileReference("report.pdf", "application/pdf")
            MediaFileBubbleContent(
                reference,
                resolveAttachmentPresentation(reference.mediaType, reference.fileName),
                AttachmentTransferState.StorageBlocked,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(width = 190.dp, height = 110.dp).background(PLACEHOLDER),
                ) {
                    TileTransferControl(storageBlockedTileTransfer(), onRetry = {}, showCaption = true)
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(80.dp).background(PLACEHOLDER)) {
                    TileTransferControl(storageBlockedTileTransfer(), onRetry = {})
                }
            }
            VoiceAttachmentContent(
                loading = false,
                failed = true,
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
                storageBlocked = true,
            )
        }
    }

    private companion object {
        val PLACEHOLDER = Color(0xFF6B7A8F)
    }
}
