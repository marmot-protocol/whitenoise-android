package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.viewerFitGeometry
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventImagePane
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real window multitouch through the same viewport used by attachment and public event image owners. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
class SharedImageViewerPinchDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun repeatedPinchExceedsFiveTimesAndPreservesItsOffCentreSourcePoint() {
        var scale by mutableStateOf(1f)
        var offset by mutableStateOf(Offset.Zero)
        var geometry: ViewerViewportGeometry? = null
        val compositions = AtomicInteger()
        val scaleTrail = mutableListOf<Float>()
        rule.setContent {
            val ownerScale = scale
            val ownerOffset = offset
            SideEffect { compositions.incrementAndGet() }
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    ImageViewerViewport(
                        "gesture-fixture",
                        4096,
                        4096,
                        ownerScale,
                        ownerOffset,
                        {
                            scaleTrail += it
                            scale = it
                        },
                        { offset = it },
                        {},
                        onViewportChange = { geometry = it },
                    ) { modifier -> Canvas(modifier) { drawRect(Color.White) } }
                }
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
        val bounds = node.fetchSemanticsNode().screenRect()
        val localFocal = Offset(bounds.width() * 0.55f, bounds.height() / 2f + bounds.width() * 0.05f)
        val fit = viewerFitGeometry(bounds.width().toFloat(), bounds.height().toFloat(), 4096, 4096)
        val sourceX = (localFocal.x - bounds.width() / 2f) / fit.baseWidth
        val sourceY = (localFocal.y - bounds.height() / 2f) / fit.baseHeight
        repeat(3) {
            injectPinchAndAwaitIdle(bounds, localFocal)
            rule.runOnIdle {
                val diagnostic =
                    "pinch=${it + 1}, compositions=${compositions.get()}, " +
                        "clock=${rule.mainClock.currentTime}, ownerOffset=$offset, " +
                        "geometry=$geometry, callbacks=$scaleTrail"
                assertPinchHandshake(it, scale, geometry, diagnostic)
            }
        }
        rule.runOnIdle {
            assertTrue("Repeated real pinch must exceed the old 5x ceiling: $scale", scale > 5f)
            assertTrue(scale.isFinite() && offset.x.isFinite() && offset.y.isFinite())
            assertTrue(abs(sourceX * fit.baseWidth * scale + bounds.width() / 2f + offset.x - localFocal.x) < 20f)
            assertTrue(abs(sourceY * fit.baseHeight * scale + bounds.height() / 2f + offset.y - localFocal.y) < 20f)
            assertTrue(requireNotNull(geometry).scale > 5f)
        }
        node.performTouchInput { doubleClick() }
        rule.runOnIdle {
            assertEquals(1f, scale)
            assertEquals(Offset.Zero, offset)
        }
    }

    @Test
    fun letterboxedImageStaysCenteredThenPreservesAnUnclampedFocalPoint() {
        var scale by mutableStateOf(1f)
        var offset by mutableStateOf(Offset.Zero)
        var geometry: ViewerViewportGeometry? = null
        rule.setContent {
            ImageViewerViewport(
                "letterboxed-gesture",
                4096,
                4096,
                scale,
                offset,
                { scale = it },
                { offset = it },
                {},
                onViewportChange = { geometry = it },
            ) { modifier -> Canvas(modifier) { drawRect(Color.White) } }
        }
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).fetchSemanticsNode().screenRect()
        rule.runOnIdle {
            assertTrue(bounds.height() > bounds.width())
            assertEquals(1f, scale, 0f)
            assertEquals(Offset.Zero, offset)
        }
        assertSmallPinchClampsLetterboxedPan(bounds, { scale }, { offset })
        injectPinchAndAwaitIdle(bounds, Offset(bounds.width() / 2f, bounds.height() / 2f))
        rule.runOnIdle {
            assertEquals(4f, scale, 0.001f)
            assertEquals(0f, offset.x, 0.01f)
            assertEquals(0f, offset.y, 0.01f)
        }
        val focalDelta = bounds.width() * 0.05f
        injectPinchAndAwaitIdle(
            bounds,
            Offset(bounds.width() / 2f + focalDelta, bounds.height() / 2f + focalDelta),
        )
        rule.runOnIdle {
            assertEquals(16f, scale, 0.001f)
            assertEquals(-3f * focalDelta, offset.x, 0.1f)
            assertEquals(-3f * focalDelta, offset.y, 0.1f)
            val fit = viewerFitGeometry(bounds.width().toFloat(), bounds.height().toFloat(), 4096, 4096)
            val maxY = (fit.baseHeight * scale - bounds.height()) / 2f
            assertTrue(abs(offset.y) <= maxY)
            assertEquals(scale, requireNotNull(geometry).scale, 0.001f)
        }
    }

    private fun assertSmallPinchClampsLetterboxedPan(
        bounds: Rect,
        readScale: () -> Float,
        readOffset: () -> Offset,
    ) {
        val delta = bounds.width() * 0.05f
        injectPinchAndAwaitIdle(
            bounds,
            Offset(bounds.width() / 2f + delta, bounds.height() / 2f + delta),
            endHalfSpanFraction = 0.03125f,
        )
        rule.runOnIdle {
            assertEquals(1.25f, readScale(), 0.001f)
            assertTrue(bounds.width() * readScale() < bounds.height())
            assertEquals(-0.25f * delta, readOffset().x, 0.1f)
            assertEquals(0f, readOffset().y, 0.01f)
        }
        rule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).performTouchInput { doubleClick() }
        rule.runOnIdle {
            assertEquals(1f, readScale(), 0f)
            assertEquals(Offset.Zero, readOffset())
        }
    }

    private fun injectPinchAndAwaitIdle(
        bounds: Rect,
        localFocal: Offset,
        endHalfSpanFraction: Float = 0.10f,
    ) {
        injectViewerPinch(
            bounds.left + localFocal.x,
            bounds.top + localFocal.y,
            bounds.width() * 0.025f,
            bounds.width() * endHalfSpanFraction,
        )
        rule.waitForIdle()
    }

    @Test
    fun actualPublicImageEntryUsesTheSharedPinchResetAndDismissPathWithoutReload() {
        var loads = 0
        var dismissals = 0
        rule.setContent {
            WhiteNoiseTheme {
                NostrEventImagePane(
                    url = "https://images.example/synthetic-no-network",
                    loadImage = { _, _ ->
                        loads++
                        ImageBitmap(512, 512)
                    },
                    initiallyRequested = true,
                    onFullscreenDismiss = { dismissals++ },
                )
            }
        }
        rule.waitForIdle()
        val node = rule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG)
        val fitted = node.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        val bounds = node.fetchSemanticsNode().screenRect()
        injectViewerPinch(bounds.exactCenterX(), bounds.exactCenterY(), bounds.width() * 0.025f, bounds.width() * 0.15f)
        rule.waitForIdle()
        assertNotEquals(fitted, node.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        node.performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(fitted, node.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val close = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.close)
        rule.onNodeWithContentDescription(close).assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(1, loads)
            assertEquals(1, dismissals)
        }
    }
}

/** One bounded two-pointer touch sequence through Android's actual input dispatch; no host ADB gesture API. */
private fun injectViewerPinch(
    centerX: Float,
    centerY: Float,
    startHalfSpan: Float,
    endHalfSpan: Float,
) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    val properties =
        Array(2) { id ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
    val coordinates =
        Array(2) {
            MotionEvent.PointerCoords().apply {
                pressure = 1f
                size = 1f
            }
        }

    fun send(
        action: Int,
        count: Int,
        span: Float,
    ) {
        coordinates[0].x = centerX - span
        coordinates[1].x = centerX + span
        coordinates.forEach { it.y = centerY }
        val event =
            MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                count,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        try {
            check(automation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }
    send(MotionEvent.ACTION_DOWN, 1, startHalfSpan)
    send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, startHalfSpan)
    for (step in 1..12) {
        SystemClock.sleep(16)
        send(MotionEvent.ACTION_MOVE, 2, startHalfSpan + (endHalfSpan - startHalfSpan) * step / 12f)
    }
    send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, endHalfSpan)
    send(MotionEvent.ACTION_UP, 1, endHalfSpan)
}

private fun assertPinchHandshake(
    index: Int,
    scale: Float,
    geometry: ViewerViewportGeometry?,
    diagnostic: String,
) {
    val expected = listOf(4f, 16f, 64f)[index]
    assertEquals(diagnostic, expected, scale, 0.001f)
    assertEquals(diagnostic, scale, requireNotNull(geometry).scale, 0.001f)
}
