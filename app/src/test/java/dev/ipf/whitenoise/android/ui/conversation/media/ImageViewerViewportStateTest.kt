package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Layout measurement must not erase a source owner's restored transform before positive constraints arrive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ImageViewerViewportStateTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun zeroSizedParentPreservesRestoredTransformUntilPositiveMeasurement() {
        var parentSize by mutableIntStateOf(0)
        var scale by mutableFloatStateOf(8f)
        var offset by mutableStateOf(Offset(40f, 20f))
        var writes = 0
        var measured: ViewerViewportGeometry? = null
        rule.setContent {
            Box(Modifier.size(parentSize.dp)) {
                ImageViewerViewport(
                    sourceKey = "restored",
                    imageWidth = 1000,
                    imageHeight = 1000,
                    scale = scale,
                    offset = offset,
                    onScaleChange = {
                        writes++
                        scale = it
                    },
                    onOffsetChange = {
                        writes++
                        offset = it
                    },
                    onChromeToggle = {},
                    onViewportChange = { measured = it },
                ) { imageModifier -> Box(imageModifier) }
            }
        }
        rule.runOnIdle {
            assertEquals(0, writes)
            assertEquals(8f, scale, 0f)
            assertEquals(Offset(40f, 20f), offset)
            assertEquals(0, checkNotNull(measured).width)
            parentSize = 300
        }
        rule.waitForIdle()
        rule.runOnIdle {
            val geometry = checkNotNull(measured)
            assertTrue(writes > 0)
            assertEquals(300, geometry.width)
            assertEquals(300, geometry.height)
            assertEquals(8f, scale, 0f)
            assertEquals(Offset(40f, 20f), offset)
            assertEquals(8f, geometry.scale, 0f)
            assertEquals(Offset(40f, 20f), geometry.offset)
            assertTrue(geometry.sourcePixelScale > 0f)
        }
    }

    @Test
    fun sourceKeyChangeRetainsTheMeasuredParentViewport() {
        var sourceKey by mutableStateOf("first")
        var scale by mutableFloatStateOf(8f)
        var offset by mutableStateOf(Offset(40f, 20f))
        val dimensions = mutableListOf<IntSize>()
        rule.setContent {
            Box(Modifier.size(300.dp)) {
                ImageViewerViewport(
                    sourceKey = sourceKey,
                    imageWidth = 1000,
                    imageHeight = 1000,
                    scale = scale,
                    offset = offset,
                    onScaleChange = { scale = it },
                    onOffsetChange = { offset = it },
                    onChromeToggle = {},
                    onViewportChange = { dimensions += IntSize(it.width, it.height) },
                ) { imageModifier -> Box(imageModifier) }
            }
        }
        rule.runOnIdle {
            assertEquals(IntSize(300, 300), dimensions.last())
            scale = 8f
            offset = Offset(40f, 20f)
            dimensions.clear()
            sourceKey = "second"
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(dimensions.isNotEmpty())
            assertTrue(dimensions.all { it == IntSize(300, 300) })
            assertEquals(8f, scale, 0f)
            assertEquals(Offset(40f, 20f), offset)
        }
    }

    @Test
    fun batchedPinchAccumulatesEachMoveWithoutAnOwnerRecomposition() {
        var scale by mutableFloatStateOf(1f)
        var offset by mutableStateOf(Offset.Zero)
        val ownerCompositions = AtomicInteger()
        rule.setContent {
            SideEffect { ownerCompositions.incrementAndGet() }
            Box(Modifier.size(300.dp)) {
                ImageViewerViewport(
                    sourceKey = "batched-pinch",
                    imageWidth = 1000,
                    imageHeight = 1000,
                    scale = scale,
                    offset = offset,
                    onScaleChange = { scale = it },
                    onOffsetChange = { offset = it },
                    onChromeToggle = {},
                ) { imageModifier -> Box(imageModifier) }
            }
        }
        rule.waitForIdle()
        val composedBeforeBatch = ownerCompositions.get()
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).performTouchInput {
                down(0, Offset(170f, 165f))
                down(1, Offset(190f, 165f))
                for (span in listOf(20f, 30f, 40f, 60f)) {
                    updatePointerTo(0, Offset(180f - span, 165f))
                    updatePointerTo(1, Offset(180f + span, 165f))
                    move(delayMillis = 0L)
                }
                up(0)
                up(1)
            }
            assertEquals(composedBeforeBatch, ownerCompositions.get())
        } finally {
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(6f, scale, 0.001f)
            assertEquals(-150f, offset.x, 0.01f)
            assertEquals(-75f, offset.y, 0.01f)
            assertEquals(180f, 30f * scale + 150f + offset.x, 0.01f)
            assertEquals(165f, 15f * scale + 150f + offset.y, 0.01f)
        }
    }
}
