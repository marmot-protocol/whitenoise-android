package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import dev.ipf.whitenoise.android.ui.conversation.media.MEDIA_VIEWER_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.MEDIA_VIEWER_TOP_CHROME_TAG
import dev.ipf.whitenoise.android.ui.conversation.media.MasonryImageLayout
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerFrame
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerMotion
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerThumbnailHost
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerThumbnailRegistry
import dev.ipf.whitenoise.android.ui.conversation.media.mediaViewerPlatformMotionAllowed
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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real production draw-layer handoff with synthetic already-rendered media; no controller or network mock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class MediaViewerMotionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** The thumbnail expands into the viewer and returns to its current registration on the AMOLED dark theme. */
    @Test fun thumbnailExpandsAndReturnsToItsCurrentRegistration() = render(false, LayoutDirection.Ltr, 1f, "dark")

    /** Thumbnail expansion and return also hold on the light theme under RTL at 1.3x font scale. */
    @Test fun thumbnailMotionSupportsLightRtlAndLargeType() = render(true, LayoutDirection.Rtl, 1.3f, "light_rtl_large")

    /** A viewer whose selected page has no return thumbnail enters normally, then dismisses at once on close. */
    @Test
    fun missingReturnThumbnailFallsBackAfterRealEntry() {
        render(false, LayoutDirection.Ltr, 1f, "missing_return", missingReturn = true)
    }

    /**
     * Mounts a thumbnail and the real viewer over one synthetic image, asserting the first frame sits at the source and
     * the settled frame shows the fitted image, and captures entry, settled and return frames. With [missingReturn] the
     * selection moves to an unregistered page before closing, and hiding the source must clear its registration.
     */
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
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(top = 64.dp)) {
                        Text(
                            "Media expansion · native Compose",
                            Modifier.offset(16.dp, 32.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        MediaViewerThumbnailHost(registry) {
                            if (visible) {
                                Image(
                                    painter = sharedFixturePainter,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier =
                                        Modifier
                                            .offset(24.dp, 8.dp)
                                            .size(144.dp, 104.dp)
                                            .clip(ConversationRichContentShape)
                                            .testTag("motion-thumbnail")
                                            .clickable { open = true }
                                            .mediaViewerThumbnail(id.messageIdHex, id.attachmentIndex, 0uL),
                                )
                            }
                        }
                        if (open) {
                            MediaViewerMotion(
                                registry,
                                id,
                                selected,
                                closing,
                                attachmentAspectRatio = 16f / 9f,
                                onClosed = {
                                    open = false
                                    closed++
                                },
                            ) { motionBodyModifier ->
                                MediaViewerFrame(
                                    senderLabel = "Alex",
                                    recordedAtLabel = "Oct 25, 2026",
                                    onDismiss = { closing = true },
                                    onSave = {},
                                    onShare = {},
                                    snackbarHostState = remember { SnackbarHostState() },
                                    bodyModifier = motionBodyModifier,
                                    actionsEnabled = !closing,
                                ) {
                                    Image(
                                        painter = sharedFixturePainter,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
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
        val sourceBounds = composeRule.onNodeWithTag("motion-thumbnail").fetchSemanticsNode().boundsInRoot
        val chromeBounds = composeRule.onNodeWithTag(MEDIA_VIEWER_TOP_CHROME_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the real viewer owner starts below the window origin", chromeBounds.top >= 64f)
        assertTrue("the source crosses the visible chrome boundary", sourceBounds.top < chromeBounds.bottom)
        assertTrue("the source also reaches below chrome", sourceBounds.bottom > chromeBounds.bottom)
        assertTrue("the asserted first pixel is inside chrome", sourceBounds.top + 50f < chromeBounds.bottom)
        val firstPixels = composeRule.onRoot().captureToImage().toPixelMap()
        assertTrue(
            "The source has a real rounded top-right clip",
            checkNotNull(registry.find(id)).corners.topRight > 4f,
        )
        val cornerY = (sourceBounds.top + 1f).toInt()
        assertEquals(
            "The opaque copy preserves the rounded corner over the viewer chrome",
            firstPixels[(sourceBounds.right + 1f).toInt(), cornerY],
            firstPixels[(sourceBounds.right - 1f).toInt(), cornerY],
        )
        assertEquals(
            "the first copy is at the actual source, not shifted by the viewer's header",
            Color(0xff195563),
            firstPixels[
                (sourceBounds.left + sourceBounds.width * 10f / 144f).toInt(),
                (sourceBounds.top + sourceBounds.height * 50f / 104f).toInt(),
            ],
        )
        var videoFrame = 0
        val recordFrames = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"
        val frames = File("build/media-viewer-motion/$name")
        if (recordFrames) frames.mkdirs()

        /** Saves the current root as the next numbered diagnostic frame when frame recording is enabled. */
        fun frame() {
            if (!recordFrames) return
            val filename = "frame-${(videoFrame++).toString().padStart(3, '0')}.png"
            composeRule.onRoot().captureRoboImage(File(frames, filename).path)
        }

        /** Advances the clock in 16 ms steps for [milliseconds], recording a diagnostic frame after each step. */
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
        assertCroppedCopyCoversTravel(sourceBounds)
        advance(96)
        assertSharedImageLandmarks(settled = false)
        advance(144)
        assertSharedImageLandmarks(settled = true)
        capture("media_motion_${name}_settled")
        composeRule.runOnIdle {
            if (missingReturn) selected = ConversationMediaViewerAttachmentId("offscreen-page", 1)
            closing = true
        }
        flushQueuedComposition()
        if (missingReturn) composeRule.runOnIdle { assertTrue(closed == 1) }
        composeRule.onNodeWithTag("conversation.media.viewer.share").assertIsNotEnabled()
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

    // Image puts its own rectangular clip inside the recorder, just like InlineAttachmentImage.
    // Both owners draw the same intrinsic scene; neither decodes or fetches another image.
    private val sharedFixturePainter =
        object : Painter() {
            override val intrinsicSize = Size(144f, 81f)

            /** Paints the shared 16:9 fixture scene into the draw scope. */
            override fun DrawScope.onDraw() = drawSharedFixtureImage()
        }

    /** Image owns Crop/Fit; the painter only draws one intrinsic16:9 scene. */
    private fun DrawScope.drawSharedFixtureImage() {
        val xScale = size.width / 144f
        val yScale = size.height / 81f
        val factor = minOf(xScale, yScale)
        translate((size.width - 144f * factor) / 2f, (size.height - 81f * factor) / 2f) {
            scale(factor, factor, pivot = Offset.Zero) {
                clipRect(0f, 0f, 144f, 81f) {
                    drawRect(
                        Color(0xff195563),
                        size =
                            androidx.compose.ui.geometry
                                .Size(144f, 81f),
                    )
                    drawCircle(Color(0xffffc45d), 22f, Offset(100.8f, 24.3f))
                    drawLine(Color(0xff5bb89d), Offset.Zero, Offset(144f, 81f), 35f)
                    drawRect(
                        Color(0xffffc45d),
                        topLeft = Offset(79.2f, 16f),
                        size =
                            androidx.compose.ui.geometry
                                .Size(43.2f, 24f),
                    )
                }
            }
        }
    }

    /** Asserts the shared image keeps its alpha and, once [settled], shows its yellow landmark aligned. */
    private fun assertSharedImageLandmarks(settled: Boolean) {
        val body = composeRule.onNodeWithTag(MEDIA_VIEWER_CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        val imageHeight = body.width * 9f / 16f
        val y = (body.top + (body.height - imageHeight) / 2f + imageHeight * .3f).toInt()
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val center = pixels[(body.left + body.width * .7f).toInt(), y]
        val cropEdge = pixels[(body.left + body.width * .555f).toInt(), y]
        assertEquals("the shared image keeps its alpha", center.alpha, cropEdge.alpha)
        if (settled) {
            // Only the fitted full image can reveal pixels discarded by the source's center crop.
            // Native compositing can round one channel by one8-bit step at these two positions.
            val centerChannels = listOf(center.red, center.green, center.blue).map { (it * 255).roundToInt() }
            val edgeChannels = listOf(cropEdge.red, cropEdge.green, cropEdge.blue).map { (it * 255).roundToInt() }
            centerChannels.zip(edgeChannels).forEach { (expected, actual) ->
                assertTrue("the settled full image keeps its landmark aligned", abs(expected - actual) <= 1)
            }
            assertEquals("the real full image owns the same landmark", Color(0xffffc45d), center)
        }
    }

    /** Before blending, actual scene ink must cover the whole interpolated clip, including its width. */
    private fun assertCroppedCopyCoversTravel(source: Rect) {
        val body = composeRule.onNodeWithTag(MEDIA_VIEWER_CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        val pixels = composeRule.onRoot().captureToImage().toPixelMap()
        val palette = setOf(Color(0xff195563), Color(0xff5bb89d), Color(0xffffc45d))
        var left = pixels.width
        var top = pixels.height
        var right = -1
        var bottom = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] in palette) {
                    left = minOf(left, x)
                    top = minOf(top, y)
                    right = maxOf(right, x)
                    bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue("the opaque traveling scene has real painted pixels", right >= left && bottom >= top)
        val ink = Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
        val fittedHeight = body.width * 9f / 16f
        val progress = (ink.height - source.height) / (fittedHeight - source.height)
        assertTrue("the measured copy is in motion before the blend starts", progress > .1f && progress < .85f)
        val expectedWidth = source.width + (body.width - source.width) * progress
        assertEquals("the clipped source fills the moving width without letterbox bands", expectedWidth, ink.width, 3f)
        val expectedCenter = source.center + (body.center - source.center) * progress
        assertEquals("the moving scene stays horizontally centered", expectedCenter.x, ink.center.x, 3f)
        assertEquals("the moving scene stays vertically centered", expectedCenter.y, ink.center.y, 3f)
        // The recorded144x104 tile has already cropped the intrinsic144x81 image. Its intrinsic
        // center remains centered as the tile covers wider bounds; discarded side pixels cannot return.
        val factor = ink.width / source.width
        val sceneScale = source.height / 81f * factor

        /** Reads the captured pixel for scene point ([x], [y]), mapped through the moving copy's scale and center. */
        fun scenePixel(
            x: Float,
            y: Float,
        ): Color =
            pixels[
                (ink.center.x + (x - 72f) * sceneScale).roundToInt(),
                (ink.center.y + (y - 40.5f) * sceneScale).roundToInt(),
            ]
        assertEquals("the cropped copy retains its yellow landmark", Color(0xffffc45d), scenePixel(100.8f, 24.3f))
        assertEquals("the cropped copy retains its diagonal landmark", Color(0xff5bb89d), scenePixel(72f, 40.5f))
        assertEquals("the cropped copy stays opaque", 1f, scenePixel(72f, 40.5f).alpha, 0f)
    }

    /** Video motion is disallowed below API 34 to keep the surface fallback, while image motion is always allowed. */
    @Test
    fun videoMotionPreservesThePreAndroid14SurfaceFallback() {
        for (sdk in 30..33) assertTrue(!mediaViewerPlatformMotionAllowed(isVideo = true, sdk = sdk))
        for (sdk in 34..36) assertTrue(mediaViewerPlatformMotionAllowed(isVideo = true, sdk = sdk))
        for (sdk in 30..36) assertTrue(mediaViewerPlatformMotionAllowed(isVideo = false, sdk = sdk))
    }

    /** A thumbnail registered under a different epoch is not flown from, so the viewer body shows at once. */
    @Test
    fun openingEpochMismatchUsesDirectPresentation() = verifyReturnOwnership(mismatchedEpoch = true)

    /** When the return source is lost without any layout callback, the closing viewer dismisses on the next frame. */
    @Test
    fun aDrawOnlyLostReturnDismissesOnTheNextFrame() = verifyReturnOwnership()

    /** Removing the motion owner mid-return never fires its dismissal, so a replacement viewer is not closed. */
    @Test
    fun removingTheOwnerDuringReturnCannotDismissItsReplacement() = verifyReturnOwnership(disposeOwner = true)

    /**
     * Mounts a viewer over a registered thumbnail and closes it. [mismatchedEpoch] expects direct presentation,
     * otherwise losing the source dismisses exactly once, unless [disposeOwner] removes the owner first, which must
     * never dismiss.
     */
    private fun verifyReturnOwnership(
        mismatchedEpoch: Boolean = false,
        disposeOwner: Boolean = false,
    ) {
        val registry = MediaViewerThumbnailRegistry()
        val id = ConversationMediaViewerAttachmentId("return-owner", 0)
        val visibleBounds = arrayOf(Rect(0f, 0f, 360f, 640f))
        var open by mutableStateOf(false)
        var closing by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var closed = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Box(Modifier.fillMaxSize()) {
                    MediaViewerThumbnailHost(registry, readingViewport = { visibleBounds[0] }) {
                        Canvas(
                            Modifier.offset(24.dp, 24.dp).size(64.dp).mediaViewerThumbnail(id.messageIdHex, 0, 0uL),
                        ) {
                            drawRect(Color.Blue)
                        }
                    }
                    if (open && mounted) {
                        MediaViewerMotion(
                            registry,
                            id,
                            id,
                            closing,
                            onClosed = { closed++ },
                            selectedSourceEpoch = if (mismatchedEpoch) 1uL else 0uL,
                            attachmentAspectRatio = 1f,
                        ) { modifier -> Box(Modifier.fillMaxSize().then(modifier).background(Color.Red)) }
                    }
                }
            }
        }
        flushInitialNativeDraw()
        assertNotNull(registry.find(id))
        composeRule.runOnIdle { open = true }
        flushQueuedComposition()
        if (mismatchedEpoch) {
            assertEquals(Color.Red, composeRule.onRoot().captureToImage().toPixelMap()[40, 40])
            return
        }
        composeRule.mainClock.advanceTimeBy(320)
        composeRule.runOnIdle { closing = true }
        flushQueuedComposition()
        assertEquals(0, closed)
        if (disposeOwner) composeRule.runOnIdle { mounted = false } else visibleBounds[0] = Rect.Zero
        composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals(if (disposeOwner) 0 else 1, closed)
        composeRule.mainClock.advanceTimeBy(320)
        composeRule.waitForIdle()
        assertEquals(if (disposeOwner) 0 else 1, closed)
    }

    /** Eligibility is read lazily from the foreground composer's actual clear interval. */
    @Test
    fun partlyOccludedSourceBecomesEligibleWithoutAnotherLayoutCallback() {
        val registry = MediaViewerThumbnailRegistry()
        val id = ConversationMediaViewerAttachmentId("viewport-media", 0)
        var bottom by mutableStateOf(200f)
        composeRule.setContent {
            val density = LocalDensity.current
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize()) {
                    MediaViewerThumbnailHost(registry, readingViewport = {
                        Rect(0f, 0f, with(density) { 360.dp.toPx() }, with(density) { bottom.dp.toPx() })
                    }) {
                        Canvas(
                            Modifier
                                .offset(24.dp, 150.dp)
                                .size(144.dp, 104.dp)
                                .mediaViewerThumbnail(id.messageIdHex, id.attachmentIndex, 0uL),
                        ) {
                            drawRect(Color.Blue)
                        }
                    }
                }
            }
        }
        flushInitialNativeDraw()
        composeRule.runOnIdle {
            assertNull("a tile partly beneath the composer is ineligible", registry.find(id))
            bottom = 300f
            assertNotNull("a fresh viewport is used without remeasuring the tile", registry.find(id))
        }
    }

    /** Closing a viewer whose selected thumbnail was never registered dismisses at once, with no return flight. */
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

    /** With motion disallowed, closing dismisses exactly once without waiting for a frame. */
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

    /** Two visible thumbnails registered under one id leave it unresolved until the duplicate is removed. */
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

    /** In LTR a three-tile album rounds only its outer physical corners, never the interior ones. */
    @Test
    fun threeTileAlbumKeepsOnlyItsPhysicalOuterCornersRounded() = renderAlbum(LayoutDirection.Ltr)

    /** In RTL a three-tile album mirrors its rounded outer corners to the physical left side. */
    @Test
    fun threeTileAlbumMirrorsItsPhysicalOuterCornersInRtl() = renderAlbum(LayoutDirection.Rtl)

    /** Lays out a three-tile masonry album in [direction], asserts which tile corners are rounded, and captures it. */
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

    /** Records the root node as the Roborazzi baseline named [name]. */
    private fun capture(name: String) {
        composeRule.onRoot().captureRoboImage("src/test/snapshots/$name.png")
    }
}
