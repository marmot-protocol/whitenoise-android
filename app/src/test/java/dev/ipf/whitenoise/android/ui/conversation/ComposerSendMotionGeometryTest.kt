package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerSendMotionGeometryTest {
    private val source = Rect(16f, 546f, 344f, 774f)
    private val container = Rect(0f, 540f, 360f, 780f)
    private val destination = Rect(109f, 628f, 348f, 712f)
    private val finalHeight = 60f
    private val viewport = ComposerSendViewportProjection(Rect(0f, 64f, 360f, 780f), container.height)

    private fun measurement(
        progress: Float,
        generation: Long = 7L,
    ): ComposerSendLandingMeasurement {
        val remaining = (container.height - finalHeight) * (1f - progress)
        return ComposerSendLandingMeasurement(
            destination.translate(Offset(0f, -remaining)),
            destination.deflate(12f).translate(Offset(0f, -remaining)),
            Rect(container.left, container.bottom - finalHeight - remaining, container.right, container.bottom),
            ComposerSendComposerTarget(generation, finalHeight),
            Color.Black,
            viewport.copy(consumedHeight = finalHeight + remaining),
        )
    }

    @Test fun aMovingLiveEndpointNoLongerCreatesAReversalAndTheFinalRowIsExact() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        val frames =
            (0..20).map { step ->
                val progress = step / 20f
                checkNotNull(owner.resolve(measurement(progress), progress))
            }
        assertEquals(source, frames.first().bounds)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.bounds.bottom <= a.bounds.bottom })
        assertEquals(destination, frames.last().bounds)
        assertEquals(destination.deflate(12f), frames.last().textTarget)
        assertEquals(0f, frames.last().remainingCollapse, 0f)
        val oldMiddle =
            androidx.compose.ui.geometry
                .lerp(source, measurement(.5f).frame, .5f)
        assertTrue(
            "The original moving-endpoint lerp overshoots the final center",
            oldMiddle.center.y < destination.center.y,
        )
    }

    @Test fun aOneFrameOlderConsumedPaddingStillPredictsTheExactFinalLanding() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        val first = checkNotNull(owner.resolve(measurement(0f), 0f))
        val prior = measurement(.1f)
        val newerPhysicalColumn = measurement(.2f).composer
        val next = checkNotNull(owner.resolve(prior.copy(composer = newerPhysicalColumn), .5f))
        assertEquals(
            first.canonicalFrame.translate(Offset(0f, first.remainingCollapse)),
            next.canonicalFrame.translate(Offset(0f, next.remainingCollapse)),
        )
        assertEquals(.1f, next.progress, .0001f)
        assertTrue(
            "A shrinking object may move its center downward while its bottom moves up",
            destination.center.y > source.center.y,
        )
    }

    @Test fun anIncreasingOrUnboundConsumedOverlapCannotDriveTheMotionPhase() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNotNull(owner.resolve(measurement(.2f), .1f))
        assertNull(owner.resolve(measurement(.1f), .2f))
        val unbound =
            ComposerSendLanding(
                source,
                container,
                Color.White,
                7L,
                viewport.copy(consumedHeight = container.height + 4f),
            )
        assertNull(unbound.resolve(measurement(0f), 0f))
    }

    @Test fun anOldLayoutCannotBorrowTheNewAcceptedInputGeneration() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNull(owner.resolve(measurement(0f, generation = 6L), 0f))
        assertNotNull(owner.resolve(measurement(0f), 0f))
    }

    @Test fun independentViewportMotionCannotBeMistakenForComposerContraction() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNotNull(owner.resolve(measurement(0f), 0f))
        val next = measurement(.1f)
        assertNull(owner.resolve(next.copy(frame = next.frame.translate(Offset(0f, 3f))), .1f))
        assertNull(owner.resolve(next.copy(composer = next.composer.translate(Offset(0f, 3f))), .1f))
    }

    @Test fun anUnreachableUpwardLandingUsesTheOrdinaryPresentation() {
        val owner = ComposerSendLanding(Rect(16f, 100f, 344f, 140f), container, Color.White, 7L, viewport)
        assertNull(owner.resolve(measurement(0f), 0f))
        val validOwner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNull(validOwner.resolve(measurement(0f).copy(target = ComposerSendComposerTarget(7L, Float.NaN)), 0f))
    }
}
