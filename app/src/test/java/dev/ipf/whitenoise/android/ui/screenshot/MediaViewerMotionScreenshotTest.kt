package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.media.ConversationMediaViewerAttachmentId
import dev.ipf.whitenoise.android.ui.conversation.media.MEDIA_VIEWER_TOP_CHROME_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.MasonryImageLayout
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerMotion
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerThumbnailHost
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerThumbnailRegistry
import dev.ipf.whitenoise.android.ui.conversation.media.mediaViewerThumbnail
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationMessageMetrics
import dev.ipf.whitenoise.android.ui.conversation.messages.ConversationRichContentShape
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real production draw-layer handoff with synthetic already-rendered media; no controller or network mock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class MediaViewerMotionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun thumbnailExpandsAndReturnsToItsCurrentRegistration() = render(false, LayoutDirection.Ltr, 1f, "dark")

    @Test fun thumbnailMotionSupportsLightRtlAndLargeType() = render(true, LayoutDirection.Rtl, 1.3f, "light_rtl_large")

    @Test
    fun missingReturnThumbnailFallsBackAfterRealEntry() {
        render(false, LayoutDirection.Ltr, 1f, "missing_return", missingReturn = true)
    }

    @Suppress("LongMethod") // One native viewer lifecycle binds entry, return and source disposal.
    private fun render(
        light: Boolean,
        direction: LayoutDirection,
        fontScale: Float,
        name: String,
        missingReturn: Boolean = false,
    ) {
        val registry = MediaViewerThumbnailRegistry()
        val id = ConversationMediaViewerAttachmentId("synthetic-media", 0)
        var selected by mutableStateOf(id)
        var open by mutableStateOf(false)
        var closing by mutableStateOf(false)
        var visible by mutableStateOf(true)
        var closed = 0
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides direction,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                WhiteNoiseTheme(darkTheme = !light, amoled = !light) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Text(
                            "Media expansion · native Compose",
                            Modifier.offset(16.dp, 32.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        MediaViewerThumbnailHost(registry) {
                            if (visible) {
                                Canvas(
                                    Modifier
                                        .offset(24.dp, 150.dp)
                                        .size(144.dp, 104.dp)
                                        .clip(ConversationRichContentShape)
                                        .mediaViewerThumbnail(id.messageIdHex, id.attachmentIndex, 0uL)
                                        .testTag("motion-thumbnail")
                                        .clickable { open = true },
                                ) {
                                    drawRect(Color(0xff195563))
                                    drawCircle(
                                        Color(0xffffc45d),
                                        22.dp.toPx(),
                                        Offset(size.width * .7f, size.height * .3f),
                                    )
                                    drawLine(
                                        Color(0xff5bb89d),
                                        Offset.Zero,
                                        Offset(size.width, size.height),
                                        35.dp.toPx(),
                                    )
                                }
                            }
                        }
                        if (open) {
                            MediaViewerFrame(
                                senderLabel = "Alex",
                                recordedAtLabel = "Oct 25, 2026",
                                onDismiss = { closing = true },
                                onSave = {},
                                onShare = {},
                                snackbarHostState = remember { SnackbarHostState() },
                            ) {
                                MediaViewerMotion(registry, id, selected, closing, onClosed = {
                                    open = false
                                    closed++
                                }) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val imageHeight = size.width * 104f / 144f
                                        val top = (size.height - imageHeight) / 2f
                                        translate(0f, top) {
                                            clipRect(0f, 0f, size.width, imageHeight) {
                                                drawRect(
                                                    Color(0xff195563),
                                                    size =
                                                        androidx.compose.ui.geometry
                                                            .Size(size.width, imageHeight),
                                                )
                                                drawCircle(
                                                    Color(0xffffc45d),
                                                    size.width * 22f / 144f,
                                                    Offset(size.width * .7f, imageHeight * .3f),
                                                )
                                                drawLine(
                                                    Color(0xff5bb89d),
                                                    Offset.Zero,
                                                    Offset(size.width, imageHeight),
                                                    size.width * 35f / 144f,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        flushInitialNativeDraw()
        assertNotNull(registry.find(id))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("motion-thumbnail").performSemanticsAction(SemanticsActions.OnClick) { it() }
        flushQueuedComposition()
        // Recomposition creates the real viewer subtree, but Robolectric defers its native
        // measure/draw until requested. Draw at this same virtual instant before checking chrome.
        composeRule.onRoot().captureToImage()
        composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).assertIsDisplayed()
        var videoFrame = 0
        val recordFrames = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"
        val frames = File("build/media-viewer-motion/$name")
        if (recordFrames) frames.mkdirs()

        fun frame() {
            if (!recordFrames) return
            val filename = "frame-${(videoFrame++).toString().padStart(3, '0')}.png"
            composeRule.onRoot().captureRoboImage(File(frames, filename).path)
        }

        fun advance(milliseconds: Long) {
            repeat((milliseconds / 16).toInt()) {
                composeRule.mainClock.advanceTimeBy(16)
                composeRule.waitForIdle()
                frame()
            }
        }
        frame()
        capture("media_motion_${name}_first")
        advance(112)
        capture("media_motion_${name}_middle")
        advance(240)
        capture("media_motion_${name}_settled")
        composeRule.runOnIdle {
            if (missingReturn) selected = ConversationMediaViewerAttachmentId("offscreen-page", 1)
            closing = true
        }
        flushQueuedComposition()
        if (missingReturn) composeRule.runOnIdle { assertTrue(closed == 1) }
        advance(96)
        capture("media_motion_${name}_return")
        advance(240)
        composeRule.runOnIdle {
            assertTrue(closed == 1)
            visible = false
        }
        flushQueuedComposition()
        assertNull(registry.find(id))
    }

    @Test
    fun missingSelectedThumbnailDismissesWithoutWaiting() {
        val registry = MediaViewerThumbnailRegistry()
        val absent = ConversationMediaViewerAttachmentId("missing", 0)
        var closing by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var dismissed = 0
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            WhiteNoiseTheme {
                if (mounted) {
                    MediaViewerMotion(registry, absent, absent, closing, onClosed = {
                        mounted = false
                        dismissed++
                    }) {
                        Text("Immediate viewer")
                    }
                }
            }
        }
        flushInitialNativeDraw()
        composeRule.runOnIdle { closing = true }
        flushQueuedComposition()
        composeRule.runOnIdle { assertTrue(dismissed == 1) }
    }

    @Test
    fun disabledMotionDismissesOnceWithoutAFrameDelay() {
        val id = ConversationMediaViewerAttachmentId("disabled", 0)
        var closing by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var dismissed = 0
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            WhiteNoiseTheme {
                if (mounted) {
                    MediaViewerMotion(
                        null,
                        id,
                        id,
                        closing,
                        allowMotion = false,
                        onClosed = {
                            mounted = false
                            dismissed++
                        },
                    ) { Text("Motion disabled") }
                }
            }
        }
        flushInitialNativeDraw()
        composeRule.runOnIdle { closing = true }
        flushQueuedComposition()
        composeRule.runOnIdle { assertTrue(dismissed == 1) }
    }

    @Test
    fun duplicateVisibleRegistrationsDoNotChooseAnArbitrarySource() {
        val registry = MediaViewerThumbnailRegistry()
        val id = ConversationMediaViewerAttachmentId("duplicate", 0)
        var duplicate by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                MediaViewerThumbnailHost(registry) {
                    Box(Modifier.fillMaxSize()) {
                        Canvas(
                            Modifier
                                .offset(16.dp, 100.dp)
                                .size(80.dp)
                                .mediaViewerThumbnail(id.messageIdHex, id.attachmentIndex, 0uL),
                        ) { drawRect(Color.Red) }
                        if (duplicate) {
                            Canvas(
                                Modifier
                                    .offset(160.dp, 100.dp)
                                    .size(80.dp)
                                    .mediaViewerThumbnail(id.messageIdHex, id.attachmentIndex, 0uL),
                            ) { drawRect(Color.Blue) }
                        }
                    }
                }
            }
        }
        flushInitialNativeDraw()
        composeRule.runOnIdle {
            assertNull(registry.find(id))
            duplicate = false
        }
        flushInitialNativeDraw()
        composeRule.runOnIdle { assertNotNull(registry.find(id)) }
    }

    @Test
    fun threeTileAlbumKeepsOnlyItsPhysicalOuterCornersRounded() = renderAlbum(LayoutDirection.Ltr)

    @Test
    fun threeTileAlbumMirrorsItsPhysicalOuterCornersInRtl() = renderAlbum(LayoutDirection.Rtl)

    private fun renderAlbum(direction: LayoutDirection) {
        val registry = MediaViewerThumbnailRegistry()
        val messageId = "synthetic-album"
        var radius = 0f
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                WhiteNoiseTheme(darkTheme = true, amoled = true) {
                    radius = with(LocalDensity.current) { ConversationMessageMetrics.RichComponentCornerRadius.toPx() }
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Text("Three-tile album · native Compose", Modifier.offset(12.dp, 32.dp))
                        MediaViewerThumbnailHost(registry) {
                            // This is the production outer clip and actual masonry layout, including its
                            // explicit canvas host. Nested right-column tiles must not round interior corners.
                            Box(Modifier.offset(12.dp, 120.dp).width(320.dp).clip(ConversationRichContentShape)) {
                                MasonryImageLayout(visibleCount = 3) { index, tileModifier ->
                                    Canvas(tileModifier.mediaViewerThumbnail(messageId, index, 0uL, gridTile = true)) {
                                        val colors = listOf(Color(0xff195563), Color(0xff5bb89d), Color(0xffffc45d))
                                        drawRect(colors[index])
                                        drawCircle(Color.White, size.minDimension * 0.15f, center = center)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        flushInitialNativeDraw()
        composeRule.runOnIdle {
            val top = registry.find(ConversationMediaViewerAttachmentId(messageId, 1))
            val bottom = registry.find(ConversationMediaViewerAttachmentId(messageId, 2))
            assertNotNull(top)
            assertNotNull(bottom)
            val topCorners = requireNotNull(top).corners
            val bottomCorners = requireNotNull(bottom).corners
            val rtl = direction == LayoutDirection.Rtl
            assertEquals(if (rtl) radius else 0f, topCorners.topLeft, 0.01f)
            assertEquals(if (rtl) 0f else radius, topCorners.topRight, 0.01f)
            assertEquals(0f, topCorners.bottomLeft, 0.01f)
            assertEquals(0f, topCorners.bottomRight, 0.01f)
            assertEquals(0f, bottomCorners.topLeft, 0.01f)
            assertEquals(0f, bottomCorners.topRight, 0.01f)
            assertEquals(if (rtl) radius else 0f, bottomCorners.bottomLeft, 0.01f)
            assertEquals(if (rtl) 0f else radius, bottomCorners.bottomRight, 0.01f)
        }
        capture("media_motion_three_tile_album_${if (direction == LayoutDirection.Rtl) "rtl" else "ltr"}")
    }

    /** Robolectric may compose/layout without drawing until a screenshot is requested. The
     * production registry deliberately becomes ready only after actual content was drawn. */
    private fun flushInitialNativeDraw() {
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.waitForIdle()
        composeRule.onRoot().captureToImage()
        // Parent canvas registration and first-draw readiness can enqueue another composition.
        flushQueuedComposition()
        composeRule.onRoot().captureToImage()
    }

    /** Publish pending state invalidations before delivering their recomposition frame. With a
     * paused clock, advancing first can miss the newly scheduled frame entirely. */
    private fun flushQueuedComposition() {
        composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
