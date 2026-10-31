package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.media.sandbox.ImageInspection
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

    @Test
    fun panAndFitCannotCancelOrRepeatTheSourcesPendingWholeImageFallback() {
        val release = CompletableDeferred<Unit>()
        val bitmap = Bitmap.createBitmap(40, 10, Bitmap.Config.ARGB_8888)
        var geometry by mutableStateOf(ViewerViewportGeometry(400, 400, 16000, 1000, 10f, Offset.Zero))
        var active by mutableStateOf(true)
        var regions = 0
        var fallbacks = 0
        var completed = 0
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(16000, 1000, "image/bmp", 1))
        rule.setContent {
            ViewerImageDetailOverlay(
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

    @Test
    fun anImprovingFallbackSurvivesFitAndRezoomWithoutAnotherDecode() {
        val bitmap = Bitmap.createBitmap(40, 10, Bitmap.Config.ARGB_8888)
        var geometry by mutableStateOf(ViewerViewportGeometry(400, 400, 16000, 1000, 10f, Offset.Zero))
        var active by mutableStateOf(true)
        var regions = 0
        var fallbacks = 0
        val source = ViewerOriginalImage(byteArrayOf(1), ImageInspection(16000, 1000, "image/bmp", 1))
        rule.setContent {
            ViewerImageDetailOverlay(
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
            ViewerImageDetailOverlay(source, geometry, active) { _, region ->
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
}
