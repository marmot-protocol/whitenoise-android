package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodePolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ViewerImageDetailLifetimeTest {
    @get:Rule val rule = createComposeRule()

    /** A source-owned fallback survives viewport changes and is retired when the viewer closes. */
    @Test
    fun panAndFitCannotCancelOrRepeatTheSourcesPendingWholeImageFallback() {
        val release = CompletableDeferred<Unit>()
        val bitmap = Bitmap.createBitmap(4096, 256, Bitmap.Config.ARGB_8888)
        var geometry by mutableStateOf(ViewerViewportGeometry(400, 400, 16000, 1000, 10f, Offset.Zero))
        var active by mutableStateOf(true)
        var regions = 0
        var fallbacks = 0
        var completed = 0
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(16000, 1000, "image/bmp", 1))
        rule.setContent {
            ViewerImageDetailLayer(
                source,
                geometry,
                active,
                fallback = {
                    fallbacks++
                    try {
                        release.await()
                        ViewerImageDetail(
                            bitmap,
                            dev.ipf.whitenoise.android.media.sandbox
                                .MediaImageRegion(0, 0, 16000, 1000),
                        )
                    } finally {
                        completed++
                    }
                },
                decode = { _, _ ->
                    regions++
                    null
                },
            )
        }
        rule.waitUntil(10_000) { fallbacks == 1 }
        repeat(3) { step ->
            rule.runOnIdle { geometry = geometry.copy(offset = Offset(100f * (step + 1), 0f)) }
            rule.mainClock.advanceTimeBy(100)
        }
        rule.runOnIdle { geometry = geometry.copy(scale = 1f, offset = Offset.Zero) }
        rule.mainClock.advanceTimeBy(100)
        rule.runOnIdle {
            assertEquals(1, regions)
            assertEquals(1, fallbacks)
            assertEquals(0, completed)
        }
        release.complete(Unit)
        rule.waitUntil(10_000) { completed == 1 }
        rule.onNodeWithTag("conversation.media.viewer.detail").assertDoesNotExist()
        rule.runOnIdle { geometry = geometry.copy(scale = 10f) }
        rule.onNodeWithTag("conversation.media.viewer.detail").assertExists()
        rule.runOnIdle {
            assertEquals(1, regions)
            assertEquals(1, fallbacks)
            active = false
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(bitmap.isRecycled) }
    }

    /** An actually sharper full-source bitmap is reused through fit and rezoom without spending another decode. */
    @Test
    fun anImprovingFallbackSurvivesFitAndRezoomWithoutAnotherDecode() {
        val bitmap = Bitmap.createBitmap(4096, 256, Bitmap.Config.ARGB_8888)
        var geometry by mutableStateOf(ViewerViewportGeometry(400, 400, 16000, 1000, 10f, Offset.Zero))
        var active by mutableStateOf(true)
        var regions = 0
        var fallbacks = 0
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(16000, 1000, "image/bmp", 1))
        rule.setContent {
            ViewerImageDetailLayer(
                source,
                geometry,
                active,
                fallback = {
                    fallbacks++
                    ViewerImageDetail(
                        bitmap,
                        dev.ipf.whitenoise.android.media.sandbox
                            .MediaImageRegion(0, 0, 16000, 1000),
                    )
                },
                decode = { _, _ ->
                    regions++
                    null
                },
            )
        }
        rule.waitUntil(10_000) { fallbacks == 1 }
        rule.onNodeWithTag("conversation.media.viewer.detail").assertExists()
        rule.runOnIdle { geometry = geometry.copy(scale = 1f) }
        rule.waitForIdle()
        rule.onNodeWithTag("conversation.media.viewer.detail").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue(!bitmap.isRecycled)
            geometry = geometry.copy(scale = 10f)
        }
        rule.onNodeWithTag("conversation.media.viewer.detail").assertExists()
        rule.runOnIdle {
            assertEquals(1, regions)
            assertEquals(1, fallbacks)
            active = false
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(bitmap.isRecycled) }
    }

    /** A replacement waits for the old codec and recycles results returned after cancellation. */
    @Test
    fun aNewViewportJoinsTheCancelledCodecAndReleasesItsLateBitmap() {
        val release = CompletableDeferred<Unit>()
        val oldBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val newestBitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var geometry by mutableStateOf(ViewerViewportGeometry(400, 400, 4000, 4000, 10f, Offset.Zero))
        var active by mutableStateOf(true)
        var calls = 0
        var running = 0
        var peak = 0
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(4000, 4000, "image/png", 1))
        rule.setContent {
            ViewerImageDetailLayer(source, geometry, active) { _, region ->
                calls++
                running++
                peak = maxOf(peak, running)
                try {
                    if (calls == 1) {
                        withContext(NonCancellable) { release.await() }
                        ViewerImageDetail(oldBitmap, region)
                    } else {
                        ViewerImageDetail(newestBitmap, region)
                    }
                } finally {
                    running--
                }
            }
        }
        rule.waitUntil(10_000) { calls == 1 }
        rule.runOnIdle { geometry = geometry.copy(offset = Offset(600f, 0f)) }
        // A non-cooperative native codec cannot be replaced by a second simultaneous decode.
        rule.mainClock.advanceTimeBy(200)
        rule.runOnIdle { assertEquals(1, calls) }
        release.complete(Unit)
        rule.waitUntil(10_000) { calls == 2 }
        rule.runOnIdle {
            assertEquals(1, peak)
            assertTrue(oldBitmap.isRecycled)
            active = false
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(newestBitmap.isRecycled) }
    }

    /** A resized vector viewport must replace a same-region tile whose old pixel demand is too small. */
    @Test
    fun vectorViewportResizeRequestsNewResolutionAndRecyclesRetiredPixels() {
        var geometry by mutableStateOf(ViewerViewportGeometry(400, 400, 24, 24, 1024f, Offset.Zero))
        var active by mutableStateOf(true)
        val requests = mutableListOf<ViewerDetailRegion.Vector>()
        val pixels = mutableListOf<Bitmap>()
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(24, 24, "image/svg+xml", 1))
        rule.setContent {
            ViewerImageDetailLayer(
                source,
                geometry,
                active,
                decode = { _, region ->
                    requests += region as ViewerDetailRegion.Vector
                    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                    pixels += bitmap
                    ViewerImageDetail(bitmap, region)
                },
            )
        }
        rule.waitUntil(10_000) { requests.size == 1 }
        rule.runOnIdle { geometry = geometry.copy(width = 800, height = 800) }
        rule.waitForIdle()
        rule.waitUntil(10_000) { requests.size == 2 }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(requests[0].region, requests[1].region)
            assertTrue(requests[1].edge > requests[0].edge)
            assertTrue(pixels[0].isRecycled)
            active = false
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(pixels[1].isRecycled) }
    }

    /** A low-density codec result is hidden without treating its source as an unsupported region format. */
    @Test
    fun undersampledRasterDoesNotCoverTheBaseOrDisableLaterZoomRequests() {
        var geometry by mutableStateOf(ViewerViewportGeometry(400, 400, 4000, 4000, 10f, Offset.Zero))
        val zoomed = geometry.copy(scale = 20f)
        assertTrue(MediaDecodePolicy.sourceAllowed(geometry.sourceWidth, geometry.sourceHeight))
        val initialRequest = geometry.detailRequest(false) as ViewerDetailRegion.Raster
        val zoomedRequest = zoomed.detailRequest(false) as ViewerDetailRegion.Raster
        assertTrue(initialRequest != zoomedRequest)
        val requests = mutableListOf<ViewerDetailRegion>()
        var active by mutableStateOf(true)
        var calls = 0
        var fallbacks = 0
        val pixels = mutableListOf<Bitmap>()
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(4000, 4000, "image/png", 1))
        rule.setContent {
            ViewerImageDetailLayer(
                source,
                geometry,
                active,
                fallback = {
                    fallbacks++
                    null
                },
                decode = { _, region ->
                    calls++
                    requests += region
                    val raster = region as ViewerDetailRegion.Raster
                    val target = MediaPipeline.targetDimensions(raster.region.width, raster.region.height, raster.edge)
                    val bitmap =
                        if (calls == 1) {
                            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                        } else {
                            Bitmap.createBitmap(target.first, target.second, Bitmap.Config.ARGB_8888)
                        }
                    pixels += bitmap
                    ViewerImageDetail(bitmap, region)
                },
            )
        }
        rule.waitUntil(10_000) { calls == 1 }
        rule.waitForIdle()
        rule.onNodeWithTag("conversation.media.viewer.detail").assertDoesNotExist()
        rule.runOnIdle { geometry = zoomed }
        rule.waitForIdle()
        rule.waitUntil(10_000) { calls == 2 }
        rule.onNodeWithTag("conversation.media.viewer.detail").assertExists()
        rule.runOnIdle {
            assertEquals(listOf(initialRequest, zoomedRequest), requests)
            assertEquals(2, calls)
            assertEquals(0, fallbacks)
            assertTrue(pixels[0].isRecycled)
            active = false
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(pixels[1].isRecycled) }
    }

    /** Fully sampled small photos keep their existing pixels instead of decoding an equally sharp replacement. */
    @Test
    fun nativeSizedRasterKeepsTheBaseWithoutARegionDecode() {
        var calls = 0
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(100, 100, "image/png", 1))
        rule.setContent {
            ViewerImageDetailLayer(
                source,
                ViewerViewportGeometry(400, 400, 100, 100, 10f, Offset.Zero),
                true,
                decode = { _, _ ->
                    calls++
                    null
                },
                fallback = {
                    calls++
                    null
                },
            )
        }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, calls) }
        rule.onNodeWithTag("conversation.media.viewer.detail").assertDoesNotExist()
    }
}
