package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * A reader's download only downloads. The image bubble, the image and video album tiles and the file card each fetch
 * an attachment in place from a tap, never open the viewer or an external app once it arrives, and open it only from a
 * later tap. The composables are the production ones over a real controller whose only replaced boundary is the
 * native transfer, with automatic downloads off so every transfer is the reader's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadNeverOpensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private var opens = 0

    /** Uses the real controller with only its native download boundary replaced, on a visible conversation. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        // A durable download intent is persisted only for the conversation that is on screen.
        fixture.state.attachmentOpens.setDestination(
            AttachmentOpenDestination(
                accountRef = MediaDownloadIntegrationFixture.ACCOUNT,
                groupIdHex = MediaDownloadIntegrationFixture.GROUP,
                navigationGeneration = 1L,
            ),
        )
        assertFalse(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
        assertFalse(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Document))
        opens = 0
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** The bubble's Download decodes the image in place without opening the viewer; a later tap opens it. */
    @Test
    fun anImageBubbleDownloadShowsTheImageInPlaceWithoutOpeningIt() {
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }

        tapDownloadAndDeliver(imageBytes())
        awaitDescribed(reference(0).fileName)

        assertStillClosed()
        composeRule.onNodeWithContentDescription(reference(0).fileName).performClick()
        composeRule.runOnIdle { assertEquals("a tap on the downloaded image must open it", 1, opens) }
    }

    /** An image album tile downloads in place from its Download action and opens only from a later tap. */
    @Test
    fun anImageAlbumTileDownloadShowsTheImageInPlaceWithoutOpeningIt() {
        composeRule.setContent { WhiteNoiseTheme { ImageTile() } }

        tapDownloadAndDeliver(imageBytes())
        awaitDescribed(reference(0).fileName)

        assertStillClosed()
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        composeRule.runOnIdle { assertEquals("a tap on the downloaded tile must open it", 1, opens) }
    }

    /** A video album tile tapped before its clip is on the device downloads it in place and opens on the next tap. */
    @Test
    fun aVideoAlbumTileTapDownloadsInPlaceWithoutOpeningIt() {
        composeRule.setContent { WhiteNoiseTheme { VideoTile() } }
        awaitDescribed(text(R.string.media_tap_to_download))

        composeRule.onNodeWithTag(TILE_TAG).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { assertEquals("a download tap opened the viewer", 0, opens) }
        composeRule.runOnIdle { fixture.calls.single().succeed(byteArrayOf(1, 2, 3, 4)) }
        awaitDescribed(text(R.string.reply_media_video))

        assertStillClosed()
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        composeRule.runOnIdle { assertEquals("a tap on the downloaded clip must open it", 1, opens) }
    }

    /**
     * A file card tapped before its file is on the device only downloads it: no open is requested, so no reader,
     * installer or external app can launch once it arrives. The next tap requests the open.
     */
    @Test
    fun aFileCardTapDownloadsTheFileWithoutRequestingAnOpen() {
        composeRule.setContent { WhiteNoiseTheme { FileCard() } }
        awaitDescribed(text(R.string.media_tap_to_download))
        val revision = composeRule.runOnIdle { fixture.state.attachmentOpens.revision }

        composeRule.onNodeWithTag(fileAttachmentCardTestTag(request(0).messageIdHex, 0)).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(byteArrayOf(5, 6, 7, 8)) }
        // The card's type glyph replaces its Download action once the file is on the device.
        awaitDescribed(text(R.string.attachment_type_pdf_description))

        composeRule.runOnIdle {
            assertEquals("a download tap requested an open", revision, fixture.state.attachmentOpens.revision)
            assertFalse(controller.hasAttachmentOpenIntent(request(0).messageIdHex, 0))
        }
        composeRule.onNodeWithTag(fileAttachmentCardTestTag(request(0).messageIdHex, 0)).performClick()
        composeRule.runOnIdle {
            assertEquals(
                "a tap on the downloaded file must request its open",
                revision + 1,
                fixture.state.attachmentOpens.revision,
            )
        }
    }

    /** Taps the idle Download action, then completes the one transfer it starts with [bytes]. */
    private fun tapDownloadAndDeliver(bytes: ByteArray) {
        val download = text(R.string.media_tap_to_download)
        awaitDescribed(download)
        composeRule.onNodeWithContentDescription(download).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(bytes) }
    }

    /** The finished download left nothing open and no download intent behind. */
    private fun assertStillClosed() {
        composeRule.runOnIdle {
            assertEquals("a completed download opened the attachment", 0, opens)
            assertFalse(
                "a completed download left its intent pending",
                controller.hasAttachmentOpenIntent(request(0).messageIdHex, 0),
            )
        }
        assertEquals("the download started more than one transfer", 1, fixture.calls.size)
    }

    /** Waits until the production surface has asked the native boundary once. */
    private fun awaitSingleTransfer() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            fixture.calls.size == 1
        }
    }

    /** Waits until some node is described exactly as [description]. */
    private fun awaitDescribed(description: String) {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The production single-image bubble for the fixture's first attachment. */
    @Composable
    private fun Bubble() {
        MediaImageBubble(
            item = localTimelineMessage(timelineAppMessage(request(0).messageIdHex)),
            reference = reference(0),
            attachmentIndex = 0,
            controller = controller,
            appState = fixture.state,
            onOpenConversationMedia = { opens++ },
            mine = false,
        )
    }

    /** The production image album tile for the fixture's first attachment. */
    @Composable
    private fun ImageTile() {
        MediaImageGridTile(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0),
            controller = controller,
            appState = fixture.state,
            mine = false,
            onTap = { opens++ },
            overflowCount = 0,
            modifier = Modifier.size(TILE_SIZE).testTag(TILE_TAG),
        )
    }

    /** The production video album tile for the fixture's first attachment. */
    @Composable
    private fun VideoTile() {
        MediaVideoGridTile(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0).copy(mediaType = "video/mp4", fileName = "clip.mp4"),
            controller = controller,
            appState = fixture.state,
            mine = false,
            onTap = { opens++ },
            overflowCount = 0,
            modifier = Modifier.size(TILE_SIZE).testTag(TILE_TAG),
        )
    }

    /** The production file card for the fixture's first attachment, as a document. */
    @Composable
    private fun FileCard() {
        MediaFileBubble(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0).copy(mediaType = "application/pdf", fileName = "report.pdf"),
            controller = controller,
            appState = fixture.state,
            senderKey = "sender",
            senderDisplayName = "Sender",
            mine = false,
        )
    }

    /** Localized text for a string resource. */
    private fun text(id: Int): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.getString(id)
    }

    /** Generates valid pixels so the image surfaces exercise the real off-main decoder. */
    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(IMAGE_EDGE, IMAGE_EDGE, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(IMAGE_COLOR)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray().also { bytes ->
                    val decoded =
                        runBlocking {
                            decodeMessageAttachmentImage(bytes, "image/png", MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                        }
                    assertNotNull("Synthetic PNG must decode before exercising the UI", decoded)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TILE_TAG = "download-never-opens-tile"
        const val WAIT_MILLIS = 20_000L
        const val IMAGE_EDGE = 16
        const val IMAGE_COLOR = 0xFF3366CC.toInt()
        val TILE_SIZE = 240.dp
    }
}
