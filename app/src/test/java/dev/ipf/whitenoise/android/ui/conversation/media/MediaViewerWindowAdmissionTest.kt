package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.ipf.whitenoise.android.ui.conversation.LocalConversationMotionEnabled
import dev.ipf.whitenoise.android.ui.conversation.PlaybackDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real window admission and thumbnail lookup budgets, independent of pixel golden rendering. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class MediaViewerWindowAdmissionTest {
    @get:Rule val composeRule = createComposeRule()

    /** The real dialog delegates motion to its content without changing its host window. */
    @Test
    @Suppress("LongMethod") // One real window lifecycle verifies content-owned motion and host isolation.
    fun thumbnailTravelDisablesTheDialogsAdditionalPlatformAnimation() {
        var hostAnimation: Int? = null
        var dialogAnimation: Int? = null
        var currentHostAnimation: Int? = null
        val registry = MediaViewerThumbnailRegistry()
        val id = ConversationMediaViewerAttachmentId("dialog-admission", 0)
        composeRule.setContent {
            val hostView = LocalView.current
            val activity =
                generateSequence(hostView.context) { (it as? android.content.ContextWrapper)?.baseContext }
                    .filterIsInstance<android.app.Activity>()
                    .first()
            SideEffect { if (hostAnimation == null) hostAnimation = activity.window.attributes.windowAnimations }
            var shown by remember { mutableStateOf(false) }
            CompositionLocalProvider(LocalConversationMotionEnabled provides true) {
                WhiteNoiseTheme {
                    MediaViewerThumbnailHost(registry) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .testTag("window-motion-source")
                                .clickable { shown = true }
                                .mediaViewerThumbnail(id.messageIdHex, id.attachmentIndex, 7uL)
                                .background(Color.Red),
                        )
                    }
                    if (shown) {
                        PlaybackDialog(
                            onDismissRequest = { shown = false },
                            properties =
                                DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    decorFitsSystemWindows = false,
                                ),
                        ) {
                            val view = LocalView.current
                            MediaViewerMotion(
                                registry = registry,
                                opening = id,
                                selected = id,
                                closing = false,
                                onClosed = {},
                                attachmentAspectRatio = 1f,
                            ) {
                                Box(it.fillMaxSize())
                            }
                            SideEffect {
                                dialogAnimation =
                                    (view.parent as DialogWindowProvider).window.attributes.windowAnimations
                                currentHostAnimation = activity.window.attributes.windowAnimations
                            }
                        }
                    }
                }
            }
        }
        flushInitialNativeDraw()
        composeRule.runOnIdle { assertNotNull(registry.find(id)) }
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNodeWithTag("window-motion-source").performClick()
            composeRule.submitMediaViewerDialogFrame()
            composeRule.runOnIdle {
                assertEquals(0, checkNotNull(dialogAnimation))
                assertEquals(hostAnimation, currentHostAnimation)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** Resolving one animation frame must not traverse layout once per bounds/corner property. */
    @Test
    fun thumbnailResolutionMeasuresOnceAndNeverCachesAcrossLookups() {
        lateinit var layer: GraphicsLayer
        composeRule.setContent {
            val ownedLayer = rememberGraphicsLayer()
            SideEffect { layer = ownedLayer }
        }
        composeRule.runOnIdle {
            val registry = MediaViewerThumbnailRegistry()
            val id = ConversationMediaViewerAttachmentId("geometry-budget", 0)
            var bounds = Rect(10f, 20f, 110f, 80f)
            val corners = MediaThumbnailCorners(8f, 0f, 8f, 0f)
            var geometryReads = 0
            val thumbnail =
                MediaViewerThumbnailRegistry.Thumbnail(layer, 7uL) {
                    geometryReads++
                    bounds to corners
                }
            thumbnail.ready = true
            registry.register(id, thumbnail)

            val first = checkNotNull(registry.resolve(id))
            assertEquals(1, geometryReads)
            assertEquals(bounds, first.bounds)
            assertEquals(corners, first.corners)
            assertEquals(1, geometryReads)

            bounds = Rect(30f, 40f, 130f, 100f)
            assertEquals(bounds, checkNotNull(registry.resolve(id)).bounds)
            assertEquals(2, geometryReads)
            bounds = Rect.Zero
            assertNull(registry.resolve(id))
            assertEquals(3, geometryReads)
            registry.remove(id, thumbnail)
            assertNull(registry.resolve(id))
            assertEquals(3, geometryReads)
        }
    }

    /** Settled frames have no source-measurement budget; active frames must still resolve current eligibility. */
    @Test
    fun settledAndDisabledViewerBodiesNeverProbeThumbnailGeometry() {
        var reads = 0
        val available = {
            reads++
            true
        }
        assertEquals(1f, mediaViewerContentAlpha(false, 0.5f, available), 0f)
        assertEquals(1f, mediaViewerContentAlpha(true, 1f, available), 0f)
        assertEquals(0, reads)
        assertEquals(0f, mediaViewerContentAlpha(true, 0.5f, available), 0f)
        assertEquals(1, reads)
        assertEquals(1f, mediaViewerContentAlpha(true, 0.5f) { false }, 0f)
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
}
