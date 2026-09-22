package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
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
import org.robolectric.annotation.GraphicsMode

/**
 * A video keeps a video's presentation while it is being sent (#2732).
 *
 * The optimistic bubble used to route every non-image attachment — the video included — to a generic
 * file pill, so a send replaced the preview the composer had just shown with file details. These
 * cases pin the classification, the per-tile treatment and the document/visual split instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class PendingVideoPreviewTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A queued video is visual media; only genuine documents are not. */
    @Test
    fun pendingClassificationSeparatesVisualMediaFromDocuments() {
        assertTrue(video().isPendingVideo)
        assertTrue(video().isPendingVisualMedia)
        assertTrue(image().isPendingVisualMedia)
        assertFalse(image().isPendingVideo)
        assertFalse(document().isPendingVisualMedia)
        assertFalse(document().isPendingVideo)
    }

    /** A sending video keeps a video bubble with its status overlay, never a file pill. */
    @Test
    fun pendingSingleVideoKeepsAVideoBubbleWithItsStatus() {
        render(listOf(video()))

        composeRule.onNodeWithTag(PENDING_VIDEO_FALLBACK_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.media_uploading)).assertExists()
        composeRule.onNodeWithText(VIDEO_NAME).assertExists()
        composeRule.onNodeWithText(VIDEO_DIMENSIONS).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.media_upload_failed)).assertDoesNotExist()
    }

    /** A failed video offers retry over the same video treatment rather than collapsing to a pill. */
    @Test
    fun failedSingleVideoOffersRetryOverTheVideoTreatment() {
        var retried = 0
        render(listOf(video()), failed = true, onRetry = { retried++ })

        composeRule.onNodeWithTag(PENDING_VIDEO_FALLBACK_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.media_upload_failed)).assertExists()
        composeRule.onNodeWithContentDescription(context.getString(R.string.retry)).performClick()
        assertEquals(1, retried)
    }

    /** A video-only album gives every tile the video badge, in album geometry. */
    @Test
    fun videoOnlyAlbumBadgesEveryTile() {
        render(listOf(video("one.mp4"), video("two.mp4"), video("three.mp4")))

        composeRule.onAllNodesWithTag(PENDING_VIDEO_BADGE_TAG, useUnmergedTree = true).assertCountEquals(3)
    }

    /** A mixed album badges only its videos, and keeps every visual tile in one bubble. */
    @Test
    fun mixedImageAndVideoAlbumBadgesOnlyItsVideos() {
        render(listOf(image("photo.jpg"), video("clip.mp4")))

        composeRule.onAllNodesWithTag(PENDING_VIDEO_BADGE_TAG, useUnmergedTree = true).assertCountEquals(1)
    }

    /** A document sent beside visual media stays a file pill under the visual bubble. */
    @Test
    fun documentsBesideVisualMediaStayFilePills() {
        render(listOf(video(), document()))

        composeRule.onNodeWithTag(PENDING_VIDEO_FALLBACK_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(DOCUMENT_NAME).assertExists()
    }

    /** A document sent on its own is unchanged: a file pill, with no visual bubble above it. */
    @Test
    fun documentOnlySendKeepsItsFilePillAlone() {
        render(listOf(document()))

        composeRule.onNodeWithText(DOCUMENT_NAME).assertExists()
        composeRule.onNodeWithTag(PENDING_VIDEO_FALLBACK_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(PENDING_VIDEO_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    /** Bytes that carry no decodable video yield an empty frame instead of throwing. */
    @Test
    fun posterExtractionFailsClosedOnUndecodableBytes() {
        assertNull(pendingVideoPosterFrame(ByteArray(0), extractPoster = true).bitmap)
        assertEquals(0L, pendingVideoPosterFrame(ByteArray(0), extractPoster = true).durationMs)
        assertNull(pendingVideoPosterFrame(byteArrayOf(1, 2, 3, 4), extractPoster = true).bitmap)
    }

    /** One frame pinning the staged-composer card and the optimistic bubble side by side. */
    @Test
    fun stagedCompositionAndOptimisticBubbleShareTheirVideoTreatment() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxWidth().testTag(TRANSITION_TAG)) {
                        ComposerAttachmentShelfCard(
                            label = VIDEO_NAME,
                            kind = ComposerAttachmentCardKind.Visual,
                            video = true,
                            bitmap = null,
                            onPreview = {},
                            onRemove = {},
                        )
                        MediaPendingPlaceholder(
                            pendingAttachments = listOf(video()),
                            failed = false,
                            showStatus = true,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(TRANSITION_TAG)
            .captureRoboImage("src/test/snapshots/conversation_pending_video_transition_light.png")
    }

    /** Composes the optimistic placeholder for [attachments]. */
    private fun render(
        attachments: List<PendingAttachment>,
        failed: Boolean = false,
        onRetry: (() -> Unit)? = null,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                MediaPendingPlaceholder(
                    pendingAttachments = attachments,
                    failed = failed,
                    onRetry = onRetry,
                    showStatus = true,
                    status = if (failed) MessageStatus.Failed else MessageStatus.Pending,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun video(fileName: String = VIDEO_NAME) =
        PendingAttachment(
            plaintextBytes = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112),
            mediaType = "video/mp4",
            fileName = fileName,
            dim = VIDEO_DIMENSIONS,
        )

    private fun image(fileName: String = "photo.jpg") =
        PendingAttachment(
            plaintextBytes = byteArrayOf(1, 2, 3, 4),
            mediaType = "image/jpeg",
            fileName = fileName,
            dim = "1920x1080",
        )

    private fun document() =
        PendingAttachment(
            plaintextBytes = byteArrayOf(5, 6, 7, 8),
            mediaType = "application/pdf",
            fileName = DOCUMENT_NAME,
        )

    private companion object {
        const val VIDEO_NAME = "clip.mp4"
        const val VIDEO_DIMENSIONS = "1280x720"
        const val DOCUMENT_NAME = "notes.pdf"
        const val TRANSITION_TAG = "conversation.pending.video.transition"
    }
}
