package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
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
import dev.ipf.whitenoise.android.ui.medialibrary.FileLibraryRow
import dev.ipf.whitenoise.android.ui.medialibrary.SharedMediaRow
import dev.ipf.whitenoise.android.ui.medialibrary.VoiceLibraryRow
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A reader's download only downloads. The image bubble, the image and video album tiles, the video and voice bubbles,
 * the file card and the Shared media file and voice rows each fetch an attachment in place from a tap, never open the
 * viewer, an external app or playback once it arrives, and open it only from a later tap, also when the surface that
 * started the download was disposed or recreated meanwhile. The composables are the production ones over a real
 * controller whose only replaced boundary is the native transfer, with automatic downloads off so every transfer is
 * the reader's own.
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
        awaitOpens(1, "a tap on the downloaded image must open it")
    }

    /** An image album tile downloads in place from its Download action and opens only from a later tap. */
    @Test
    fun anImageAlbumTileDownloadShowsTheImageInPlaceWithoutOpeningIt() {
        composeRule.setContent { WhiteNoiseTheme { ImageTile() } }

        tapDownloadAndDeliver(imageBytes())
        awaitDescribed(reference(0).fileName)

        assertStillClosed()
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        awaitOpens(1, "a tap on the downloaded tile must open it")
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
        // The tile checks its file off the main thread before it opens, so the open is awaited, then counted.
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        awaitOpens(1, "a tap on the downloaded clip must open it")
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
            assertFalse(hasOpenIntent())
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

    /**
     * A video bubble's download keeps going after the bubble leaves composition and, once it lands, the returning
     * bubble shows the clip without opening the viewer; a later tap opens it.
     */
    @Test
    fun aVideoBubbleDownloadSurvivesOriginDisposalAndNeverOpens() {
        var shown by mutableStateOf(true)
        composeRule.setContent { WhiteNoiseTheme { if (shown) VideoBubble() } }
        awaitDescribed(text(R.string.media_tap_to_download))

        composeRule.onNodeWithTag(videoAttachmentOpenTestTag(request(0).messageIdHex, 0)).performClick()
        awaitSingleTransfer()
        // The play disc's progress ring is named, so TalkBack never reads a nameless control while it downloads.
        awaitDescribed(text(R.string.media_downloading))
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { fixture.calls.single().succeed(byteArrayOf(1, 2, 3, 4)) }
        composeRule.runOnIdle { shown = true }
        awaitDescribed(text(R.string.reply_media_video))

        assertStillClosed()
        composeRule.onNodeWithTag(videoAttachmentOpenTestTag(request(0).messageIdHex, 0)).performClick()
        awaitOpens(1, "a tap on the downloaded clip must open it")
    }

    /**
     * A download armed by a tap survives recreation of the surface that started it: the recreated tile re-arms the
     * same transfer, shows the image in place once it lands, opens nothing and retires the download intent.
     */
    @Test
    fun aDownloadArmedBeforeRecreationLandsInPlaceWithoutOpening() {
        var generation by mutableStateOf(0)
        composeRule.setContent { WhiteNoiseTheme { key(generation) { ImageTile() } } }
        val download = text(R.string.media_tap_to_download)
        awaitDescribed(download)
        composeRule.onNodeWithContentDescription(download).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle {
            assertTrue(controller.hasAttachmentDownloadIntent(request(0).messageIdHex, 0))
            generation++
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { fixture.calls.single().succeed(imageBytes()) }
        awaitDescribed(reference(0).fileName)

        assertStillClosed()
    }

    /** A voice note's download finishes without starting playback; the Play tap that follows starts it once. */
    @Test
    fun aVoiceBubbleDownloadDoesNotPlayUntilTheNextTap() {
        val runtime = RecordingVoiceRuntime()
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalVoiceAttachmentPresentationRuntime provides runtime) { VoiceBubble() }
            }
        }

        tapDownloadAndDeliver(byteArrayOf(1, 2, 3, 4))
        awaitDescribed(text(R.string.voice_message_play))

        composeRule.runOnIdle { assertEquals("a finished download started playback", 0, runtime.plays) }
        assertStillClosed()
        composeRule.onNodeWithContentDescription(text(R.string.voice_message_play)).performClick()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            runtime.plays >= 1
        }
        composeRule.runOnIdle { assertEquals("the Play tap must start playback exactly once", 1, runtime.plays) }
    }

    /** A Shared media file row not on the device downloads on its first tap and attempts its open only on the next. */
    @Test
    fun aSharedMediaFileRowDownloadsFirstAndOpensOnlyOnTheNextTap() {
        val row = sharedMediaRow(reference(0).copy(mediaType = "application/pdf", fileName = "report.pdf"))
        composeRule.setContent {
            WhiteNoiseTheme {
                FileLibraryRow(
                    row = row,
                    controller = controller,
                    appState = fixture.state,
                    documentSaveFallback =
                        rememberDocumentSaveFallback(fixture.state, MediaDownloadIntegrationFixture.GROUP),
                )
            }
        }

        composeRule.onNodeWithText(row.reference.fileName).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(byteArrayOf(5, 6, 7, 8)) }
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            composeRule
                .onAllNodesWithContentDescription(text(R.string.shared_media_file_actions))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.runOnIdle { assertFalse("a finished download attempted an open", openAttempted()) }
        composeRule.onNodeWithText(row.reference.fileName).performClick()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            openAttempted()
        }
        assertEquals("the open downloaded again", 1, fixture.calls.size)
    }

    /**
     * A Shared media voice row not on the device offers Download rather than Play; its tap downloads without starting
     * playback, and only the following Play tap starts a playback session.
     */
    @Test
    fun aSharedMediaVoiceRowOffersDownloadAndPlaysOnlyOnTheNextTap() {
        val row = sharedMediaRow(reference(0).copy(mediaType = "audio/ogg", fileName = "note.ogg"))
        val initialSession = VoicePlaybackController.state.value.sessionId
        composeRule.setContent {
            WhiteNoiseTheme { VoiceLibraryRow(row, controller, fixture.state, onJumpToMessage = {}) }
        }
        val download = text(R.string.media_tap_to_download)
        awaitDescribed(download)

        composeRule.onNodeWithContentDescription(download).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(byteArrayOf(1, 2, 3, 4)) }
        awaitDescribed(text(R.string.voice_message_play))

        val sessionAfterDownload = VoicePlaybackController.state.value.sessionId
        assertEquals("a finished download started playback", initialSession, sessionAfterDownload)
        composeRule.onNodeWithContentDescription(text(R.string.voice_message_play)).performClick()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            VoicePlaybackController.state.value.sessionId != initialSession
        }
        composeRule.runOnIdle { VoicePlaybackController.stop() }
    }

    /**
     * A Shared media voice row for a clip already on the device never offers Download, not even for the frame before
     * its cache probe answers, so its first tap plays it.
     */
    @Test
    fun aSharedMediaVoiceRowForACachedClipNeverOffersDownload() {
        val row = sharedMediaRow(reference(0).copy(mediaType = "audio/ogg", fileName = "note.ogg"))
        var generation by mutableStateOf(0)
        composeRule.setContent {
            WhiteNoiseTheme {
                key(generation) {
                    VoiceLibraryRow(row, controller, fixture.state, onJumpToMessage = {})
                }
            }
        }
        val download = text(R.string.media_tap_to_download)
        awaitDescribed(download)
        composeRule.onNodeWithContentDescription(download).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(byteArrayOf(1, 2, 3, 4)) }
        awaitDescribed(text(R.string.voice_message_play))

        // Look at the recreated row's first frame, before its cache probe can have answered.
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { generation++ }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onAllNodesWithContentDescription(download).assertCountEquals(0)
        composeRule.mainClock.autoAdvance = true
        awaitDescribed(text(R.string.voice_message_play))
        composeRule.onAllNodesWithContentDescription(download).assertCountEquals(0)
    }

    /**
     * A video tile whose downloaded file the cache evicted while the tile stayed on screen checks the file again at
     * tap time: that tap downloads it again in place and opens nothing, and only the next tap opens it.
     */
    @Test
    fun aVideoTileWhoseFileWasEvictedDownloadsAgainInsteadOfOpening() {
        val clip = reference(0).copy(mediaType = "video/mp4", fileName = "clip.mp4")
        composeRule.setContent { WhiteNoiseTheme { VideoTile() } }
        awaitDescribed(text(R.string.media_tap_to_download))
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(byteArrayOf(1, 2, 3, 4)) }
        awaitDescribed(text(R.string.reply_media_video))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cached = requireNotNull(cachedVideoAttachmentFile(context, request(0).messageIdHex, 0, clip))

        composeRule.runOnIdle { assertTrue("the cached clip could not be evicted", cached.delete()) }
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            cachedVideoAttachmentFile(context, request(0).messageIdHex, 0, clip) != null
        }
        awaitDescribed(text(R.string.reply_media_video))
        composeRule.runOnIdle { assertEquals("a tap on an evicted clip opened the viewer", 0, opens) }

        composeRule.onNodeWithTag(TILE_TAG).performClick()
        awaitOpens(1, "a tap on the downloaded clip must open it")
    }

    /** The "+N" video tile opens the album even after its own download failed, instead of retrying that video. */
    @Test
    fun anOverflowVideoTileOpensTheAlbumAfterAFailedDownload() {
        var overflow by mutableStateOf(0)
        composeRule.setContent { WhiteNoiseTheme { VideoTile(overflowCount = overflow) } }
        awaitDescribed(text(R.string.media_tap_to_download))
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().fail(java.io.IOException("download failed")) }
        awaitDescribed(text(R.string.media_tap_to_retry))

        composeRule.runOnIdle { overflow = 2 }
        composeRule.onNodeWithTag(TILE_TAG).performTouchInput { click(Offset(CORNER_PX, CORNER_PX)) }
        awaitOpens(1, "the overflow tile did not open the album after a failed download")
        assertEquals("the overflow tap retried the tile's download", 0, fixture.retryDemands.get())
    }

    /** The "+N" video tile opens the album even when its own downloaded clip was evicted from the cache. */
    @Test
    fun anOverflowVideoTileOpensTheAlbumAfterItsClipWasEvicted() {
        val clip = reference(0).copy(mediaType = "video/mp4", fileName = "clip.mp4")
        var overflow by mutableStateOf(0)
        composeRule.setContent { WhiteNoiseTheme { VideoTile(overflowCount = overflow) } }
        awaitDescribed(text(R.string.media_tap_to_download))
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(byteArrayOf(1, 2, 3, 4)) }
        awaitDescribed(text(R.string.reply_media_video))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cached = requireNotNull(cachedVideoAttachmentFile(context, request(0).messageIdHex, 0, clip))

        composeRule.runOnIdle {
            assertTrue("the cached clip could not be evicted", cached.delete())
            overflow = 2
        }
        composeRule.onNodeWithTag(TILE_TAG).performClick()
        awaitOpens(1, "the overflow tile did not open the album after its clip was evicted")
    }

    /** Taps the idle Download action, then completes the one transfer it starts with [bytes]. */
    private fun tapDownloadAndDeliver(bytes: ByteArray) {
        val download = text(R.string.media_tap_to_download)
        awaitDescribed(download)
        composeRule.onNodeWithContentDescription(download).performClick()
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(bytes) }
    }

    /** Awaits durable intent retirement while checking that the finished download never requests an open. */
    private fun assertStillClosed() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.runOnIdle {
                assertEquals("a settling download opened the attachment", 0, opens)
                assertFalse("a settling download recorded an open", hasOpenIntent())
                !controller.hasAttachmentDownloadIntent(request(0).messageIdHex, 0)
            }
        }
        composeRule.runOnIdle {
            assertEquals("a completed download opened the attachment", 0, opens)
            assertFalse(
                "a download recorded an open",
                hasOpenIntent(),
            )
            assertFalse(
                "a completed download left its intent pending",
                controller.hasAttachmentDownloadIntent(request(0).messageIdHex, 0),
            )
        }
        assertEquals("the download started more than one transfer", 1, fixture.calls.size)
    }

    /** Whether the open coordinator holds an open intent for the first attachment, as a launch would claim. */
    private fun hasOpenIntent(): Boolean {
        val openRequest = controller.attachmentOpenRequest(request(0).messageIdHex, 0) ?: return false
        return fixture.state.attachmentOpens.hasIntent(openRequest)
    }

    /** Waits for [count] opens, which may land after an off-main-thread file check, then asserts exactly [count]. */
    private fun awaitOpens(
        count: Int,
        message: String,
    ) {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            opens >= count
        }
        composeRule.runOnIdle { assertEquals(message, count, opens) }
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

    /** The production video album tile for the first attachment, the "+N" tile when [overflowCount] is set. */
    @Composable
    private fun VideoTile(overflowCount: Int = 0) {
        MediaVideoGridTile(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0).copy(mediaType = "video/mp4", fileName = "clip.mp4"),
            controller = controller,
            appState = fixture.state,
            mine = false,
            onTap = { opens++ },
            overflowCount = overflowCount,
            modifier = Modifier.size(TILE_SIZE).testTag(TILE_TAG),
        )
    }

    /** The production single-video bubble for the fixture's first attachment. */
    @Composable
    private fun VideoBubble() {
        MediaVideoBubble(
            item = localTimelineMessage(timelineAppMessage(request(0).messageIdHex)),
            attachmentIndex = 0,
            reference = reference(0).copy(mediaType = "video/mp4", fileName = "clip.mp4"),
            controller = controller,
            appState = fixture.state,
            onOpenConversationMedia = { opens++ },
            mine = false,
        )
    }

    /** The production voice bubble for the fixture's first attachment. */
    @Composable
    private fun VoiceBubble() {
        MediaVoiceBubble(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0).copy(mediaType = "audio/ogg", fileName = "note.ogg"),
            controller = controller,
            appState = fixture.state,
            presentationOwner = this,
            mine = false,
        )
    }

    /** A received Shared media row for the fixture's first attachment. */
    private fun sharedMediaRow(reference: MediaAttachmentReferenceFfi) =
        SharedMediaRow(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference,
            mine = false,
            recordedAt = 1uL,
            sender = "sender",
        )

    /** Whether the app tried to hand a file to another app, or reported that it could not. */
    private fun openAttempted(): Boolean =
        fixture.state.toast != null ||
            shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).peekNextStartedActivity() !=
            null

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

    /**
     * Downloads a voice note through the production materializer and records playback requests, so no platform
     * player is needed.
     */
    private class RecordingVoiceRuntime : VoiceAttachmentPresentationRuntime {
        private val state = MutableStateFlow(VoicePlaybackController.PlaybackState())
        var plays = 0

        override val playbackState: StateFlow<VoicePlaybackController.PlaybackState> = state
        override val playbackFailures = MutableSharedFlow<VoicePlaybackController.PlaybackFailure>()

        /** Materializes through the real attachment cache and transfer. */
        override suspend fun materialize(request: VoiceAttachmentMaterializationRequest): File =
            materializeVoiceAttachment(
                context = request.context,
                controller = request.controller,
                messageIdHex = request.messageIdHex,
                attachmentIndex = request.attachmentIndex,
                reference = request.reference,
                mine = request.mine,
                priority = request.priority,
            )

        /** Reports a fixed duration without probing the synthetic bytes. */
        override suspend fun durationMs(file: File): Int = VOICE_DURATION_MS

        /** Records one playback request and reports it playing. */
        override suspend fun play(
            key: String,
            file: File,
            ownerKey: String,
            source: VoicePlaybackSource,
        ): VoicePlaybackController.PlaybackStartResult {
            plays++
            state.value =
                VoicePlaybackController.PlaybackState(key = key, isPlaying = true, durationMs = VOICE_DURATION_MS)
            return VoicePlaybackController.PlaybackStartResult.Started
        }

        /** Marks playback paused. */
        override fun pause() {
            state.value = state.value.copy(isPlaying = false)
        }

        /** Ignores seeks; no test here seeks. */
        override fun seekTo(
            key: String,
            positionMs: Int,
        ) = Unit

        /** Ignores speed changes; no test here changes speed. */
        override fun cycleSpeed() = Unit
    }

    private companion object {
        const val VOICE_DURATION_MS = 1_000
        const val CORNER_PX = 6f
        const val TILE_TAG = "download-never-opens-tile"
        const val WAIT_MILLIS = 20_000L
        const val IMAGE_EDGE = 16
        const val IMAGE_COLOR = 0xFF3366CC.toInt()
        val TILE_SIZE = 240.dp
    }
}
