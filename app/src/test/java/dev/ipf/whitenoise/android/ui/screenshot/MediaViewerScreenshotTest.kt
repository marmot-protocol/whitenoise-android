package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerGallery
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerLoadFailed
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import dev.ipf.whitenoise.android.ui.conversation.media.visualMediaViewerGallery
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Deterministic baselines for the full-screen media viewer's gallery states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MediaViewerScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mediaViewerDefaultFrame() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                MediaViewerFrame(
                    senderLabel = "Alex",
                    recordedAtLabel = "Jul 16, 2026, 3:45 PM",
                    onDismiss = {},
                    onSave = {},
                    onShare = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_default_frame.png")
    }

    @Test
    fun directlyOpenedVideoOffersSaveAndShare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var saveClicked = false
        var shareClicked = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                MediaViewerFrame(
                    senderLabel = "Alex",
                    recordedAtLabel = "Aug 28, 2026, 11:24 AM",
                    onDismiss = {},
                    onSave = { saveClicked = true },
                    onShare = { shareClicked = true },
                    snackbarHostState = remember { SnackbarHostState() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(72.dp),
                    )
                }
            }
        }

        composeRule
            .onNodeWithContentDescription(context.getString(R.string.media_save))
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.share))
            .assertIsDisplayed()
            .performClick()
        assertTrue(saveClicked)
        assertTrue(shareClicked)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_direct_video_actions.png")
    }

    /** Verifies portrait large-type chrome respects status, navigation, and horizontal safe areas. */
    @Test
    fun mediaViewerPortraitKeepsActionsInsideSystemInsetsAtLargeType() {
        captureRotationFrame(
            contentWindowInsets = WindowInsets(left = 18.dp, top = 42.dp, right = 18.dp, bottom = 28.dp),
            snapshotName = "media_viewer_rotation_portrait_large_type.png",
        )
        assertViewerChromeInsideInsets(
            viewportWidth = 360.dp,
            viewportHeight = 780.dp,
            leftInset = 18.dp,
            topInset = 42.dp,
            rightInset = 18.dp,
            bottomInset = 28.dp,
        )
    }

    /** Verifies landscape large-type chrome remains reachable beside asymmetric display cutouts. */
    @Test
    @Config(sdk = [36], qualifiers = "w780dp-h360dp-land-mdpi")
    fun mediaViewerLandscapeKeepsActionsInsideCutoutInsetsAtLargeType() {
        captureRotationFrame(
            contentWindowInsets = WindowInsets(left = 56.dp, top = 18.dp, right = 28.dp, bottom = 22.dp),
            snapshotName = "media_viewer_rotation_landscape_cutout_large_type.png",
        )
        assertViewerChromeInsideInsets(
            viewportWidth = 780.dp,
            viewportHeight = 360.dp,
            leftInset = 56.dp,
            topInset = 18.dp,
            rightInset = 28.dp,
            bottomInset = 22.dp,
        )
    }

    @Test
    fun mediaViewerFailedFrameOffersRetry() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                MediaViewerFrame(
                    senderLabel = "Alex",
                    recordedAtLabel = "Jul 16, 2026, 3:45 PM",
                    onDismiss = {},
                    onSave = {},
                    onShare = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    MediaViewerLoadFailed(
                        onRetry = {},
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_failed_frame.png")
    }

    @Test
    fun mediaViewerFallbackGalleryUsesTheTappedPageMetadata() {
        val tapped = page("tapped", sender = "Blair", recordedAt = 200uL)
        val gallery =
            visualMediaViewerGallery(
                conversationVisualPages = emptyList(),
                messagePages = listOf(tapped),
                tappedAttachmentIndex = tapped.attachmentIndex,
            )

        assertEquals(listOf(tapped), gallery.pages)
        assertEquals(0, gallery.startIndex)
        captureGallery(
            gallery = gallery,
            currentPageIndex = gallery.startIndex,
            recordedAtLabel = "Jul 16, 2026, 3:45 PM",
            mediaColor = TAPPED_MEDIA_COLOR,
            snapshotName = "media_viewer_fallback_gallery.png",
        )
    }

    @Test
    fun mediaViewerLoadedGalleryPreservesTheTappedPageAndMetadata() {
        val gallery = loadedConversationGallery()

        assertEquals(1, gallery.startIndex)
        assertEquals("tapped", gallery.pages[gallery.startIndex].messageIdHex)
        captureGallery(
            gallery = gallery,
            currentPageIndex = gallery.startIndex,
            recordedAtLabel = "Jul 16, 2026, 3:45 PM",
            mediaColor = TAPPED_MEDIA_COLOR,
            snapshotName = "media_viewer_loaded_gallery_current_page.png",
        )
    }

    @Test
    fun mediaViewerPagedGalleryShowsTheCurrentPagesMetadata() {
        val gallery = loadedConversationGallery()
        val oldestPageIndex = gallery.pages.lastIndex

        assertEquals("oldest", gallery.pages[oldestPageIndex].messageIdHex)
        captureGallery(
            gallery = gallery,
            currentPageIndex = oldestPageIndex,
            recordedAtLabel = "Jul 14, 2026, 9:15 AM",
            mediaColor = OLDEST_MEDIA_COLOR,
            snapshotName = "media_viewer_paged_gallery_current_page.png",
        )
    }

    private fun loadedConversationGallery(): MediaViewerGallery {
        val newest = page("newest", sender = "Alex", recordedAt = 300uL)
        val tapped = page("tapped", sender = "Blair", recordedAt = 200uL)
        val oldest = page("oldest", sender = "Casey", recordedAt = 100uL)
        return visualMediaViewerGallery(
            conversationVisualPages = listOf(newest, tapped, oldest),
            messagePages = listOf(tapped),
            tappedAttachmentIndex = tapped.attachmentIndex,
        )
    }

    private fun captureGallery(
        gallery: MediaViewerGallery,
        currentPageIndex: Int,
        recordedAtLabel: String,
        mediaColor: Color,
        snapshotName: String,
    ) {
        val currentPage = gallery.pages[currentPageIndex]
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                MediaViewerFrame(
                    senderLabel = currentPage.sender,
                    recordedAtLabel = recordedAtLabel,
                    onDismiss = {},
                    onSave = {},
                    onShare = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(240.dp)
                                .background(mediaColor),
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshotName")
    }

    /** Captures a deterministic video-like viewer frame with the supplied synthetic safe-area insets. */
    private fun captureRotationFrame(
        contentWindowInsets: WindowInsets,
        snapshotName: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, fontScale = 1.6f) {
                MediaViewerFrame(
                    senderLabel = "Alexandria Example",
                    recordedAtLabel = "Sep 5, 2026, 11:24 PM",
                    onDismiss = {},
                    onSave = {},
                    onShare = {},
                    snackbarHostState = remember { SnackbarHostState() },
                    contentWindowInsets = contentWindowInsets,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .height(240.dp)
                                .background(TAPPED_MEDIA_COLOR),
                    )
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(72.dp),
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$snapshotName")
    }

    /** Asserts all interactive chrome has safe placement and at least a 48dp touch target. */
    private fun assertViewerChromeInsideInsets(
        viewportWidth: Dp,
        viewportHeight: Dp,
        leftInset: Dp,
        topInset: Dp,
        rightInset: Dp,
        bottomInset: Dp,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val close =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.close))
                .assertHasClickAction()
                .touchTargetBoundsInRoot()
        val save =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.media_save))
                .assertHasClickAction()
                .touchTargetBoundsInRoot()
        val share =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.share))
                .assertHasClickAction()
                .touchTargetBoundsInRoot()
        val metadata = composeRule.onNodeWithText("Sep 5, 2026, 11:24 PM").getUnclippedBoundsInRoot()

        assertTrue("Close overlaps the left cutout", close.left >= leftInset)
        assertTrue("Close overlaps the status bar", close.top >= topInset)
        assertTrue("Save overlaps the status bar", save.top >= topInset)
        assertTrue("Share overlaps the right cutout", share.right <= viewportWidth - rightInset)
        assertTrue("Metadata overlaps the navigation bar", metadata.bottom <= viewportHeight - bottomInset)
        listOf("Close" to close, "Save" to save, "Share" to share).forEach { (label, bounds) ->
            assertTrue("$label touch target is narrower than 48dp", bounds.right - bounds.left >= 48.dp)
            assertTrue("$label touch target is shorter than 48dp", bounds.bottom - bounds.top >= 48.dp)
        }
    }

    /** Measures the actual expanded touch target with its layout density, not just the painted control bounds. */
    private fun SemanticsNodeInteraction.touchTargetBoundsInRoot(): DpRect {
        val node = fetchSemanticsNode()
        val bounds = node.touchBoundsInRoot
        return with(node.layoutInfo.density) {
            DpRect(bounds.left.toDp(), bounds.top.toDp(), bounds.right.toDp(), bounds.bottom.toDp())
        }
    }

    private fun page(
        messageIdHex: String,
        sender: String,
        recordedAt: ULong,
    ) = MediaViewerPage(
        messageIdHex = messageIdHex,
        attachmentIndex = 0,
        reference =
            MediaAttachmentReferenceFfi(
                locators = emptyList(),
                ciphertextSha256 = "aa".repeat(32),
                plaintextSha256 = "bb".repeat(32),
                nonceHex = "cc".repeat(12),
                fileName = "$messageIdHex.jpg",
                mediaType = "image/jpeg",
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 1uL,
                dim = null,
                thumbhash = null,
            ),
        mine = false,
        sender = sender,
        recordedAt = recordedAt,
    )

    private companion object {
        val TAPPED_MEDIA_COLOR = Color(0xFF315C7D)
        val OLDEST_MEDIA_COLOR = Color(0xFF7D4B31)
    }
}
