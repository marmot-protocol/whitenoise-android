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

    /** Builds the landing measurement of a composer that has contracted to the given progress and input generation. */
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

    /** Resolves the geometry for a measurement and, when it is accepted, commits it as the timing owner would. */
    private fun committed(
        owner: ComposerSendLanding,
        measured: ComposerSendLandingMeasurement,
        progress: Float,
    ): ComposerSendFlightGeometry? = owner.resolve(measured, progress).also { if (it != null) owner.commit(it) }

    /**
     * Read-only probes latch neither the endpoint nor the consumed height, but a committed one rejects a later, larger
     * overlap.
     */
    @Test fun pureProbesCannotLatchAnEndpointOrConsumedHeight() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNotNull(owner.resolve(measurement(.2f), .2f))
        assertNotNull(owner.resolve(measurement(.1f), .2f))
        committed(owner, measurement(.2f), .2f)
        assertNull(owner.resolve(measurement(.1f), .3f))
    }

    /**
     * A finished timer only counts as settled once the native composer interval is clear and the row sits at its
     * canonical frame.
     */
    @Test fun timerCompletionCannotHandOffBeforeTheActualNativeIntervalIsClear() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        val remaining = checkNotNull(owner.resolve(measurement(.5f), 1f))
        org.junit.Assert.assertFalse(remaining.settled)
        val settled = checkNotNull(owner.resolve(measurement(1f), 1f))
        assertTrue(settled.settled)
        org.junit.Assert.assertFalse(settled.copy(composer = container).settled)
        org.junit.Assert.assertFalse(
            settled
                .copy(
                    bounds = destination.translate(Offset(0f, 20f)),
                    canonicalFrame = destination.translate(Offset(0f, 20f)),
                ).settled,
        )
    }

    /**
     * A flight starting from the cropped editor rect begins at those bounds and keeps the whole pill's upward
     * eligibility.
     */
    @Test fun editorCropRetainsTheOriginalWholePillUpwardEligibility() {
        val editor = Rect(20f, 546f, 340f, 580f)
        val owner = ComposerSendLanding(editor, container, Color.White, 7L, viewport, source.bottom)
        val initial = checkNotNull(owner.resolve(measurement(0f), 0f))
        assertEquals(editor, initial.bounds)
        assertEquals(0f, initial.progress, 0f)
        assertNotNull(owner.resolve(measurement(.5f), .25f))
    }

    /**
     * As the composer contracts the row never reverses and lands exactly on the final frame, where a naive endpoint
     * lerp overshoots.
     */
    @Test fun aMovingLiveEndpointNoLongerCreatesAReversalAndTheFinalRowIsExact() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        val frames =
            (0..20).map { step ->
                val progress = step / 20f
                checkNotNull(committed(owner, measurement(progress), progress))
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

    /**
     * A consumed padding one frame stale still predicts the exact final landing and keeps the visible timer's progress.
     */
    @Test fun aOneFrameOlderConsumedPaddingStillPredictsTheExactFinalLanding() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        val first = checkNotNull(committed(owner, measurement(0f), 0f))
        val prior = measurement(.1f)
        val newerPhysicalColumn = measurement(.2f).composer
        val next = checkNotNull(committed(owner, prior.copy(composer = newerPhysicalColumn), .5f))
        assertEquals(
            first.canonicalFrame.translate(Offset(0f, first.remainingCollapse)),
            next.canonicalFrame.translate(Offset(0f, next.remainingCollapse)),
        )
        assertEquals("Native contraction must not skip the visible timer", .5f, next.progress, .0001f)
        assertTrue(
            "A shrinking object may move its center downward while its bottom moves up",
            destination.center.y > source.center.y,
        )
    }

    /** A growing or out-of-range consumed overlap is rejected, so it cannot drive the motion phase. */
    @Test fun anIncreasingOrUnboundConsumedOverlapCannotDriveTheMotionPhase() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNotNull(committed(owner, measurement(.2f), .1f))
        assertNull(committed(owner, measurement(.1f), .2f))
        val unbound =
            ComposerSendLanding(
                source,
                container,
                Color.White,
                7L,
                viewport.copy(consumedHeight = container.height + 4f),
            )
        assertNull(committed(unbound, measurement(0f), 0f))
    }

    /**
     * A measurement from an earlier input generation is rejected, while the current generation's measurement is
     * accepted.
     */
    @Test fun anOldLayoutCannotBorrowTheNewAcceptedInputGeneration() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNull(committed(owner, measurement(0f, generation = 6L), 0f))
        assertNotNull(committed(owner, measurement(0f), 0f))
    }

    /**
     * Frame or composer movement that is not the composer contracting is rejected instead of being read as contraction.
     */
    @Test fun independentViewportMotionCannotBeMistakenForComposerContraction() {
        val owner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNotNull(committed(owner, measurement(0f), 0f))
        val next = measurement(.1f)
        assertNull(committed(owner, next.copy(frame = next.frame.translate(Offset(0f, 3f))), .1f))
        assertNull(committed(owner, next.copy(composer = next.composer.translate(Offset(0f, 3f))), .1f))
    }

    /**
     * A landing that cannot be reached upward, or has a non-finite composer height, falls back to the ordinary
     * presentation.
     */
    @Test fun anUnreachableUpwardLandingUsesTheOrdinaryPresentation() {
        val owner = ComposerSendLanding(Rect(16f, 100f, 344f, 140f), container, Color.White, 7L, viewport)
        assertNull(committed(owner, measurement(0f), 0f))
        val validOwner = ComposerSendLanding(source, container, Color.White, 7L, viewport)
        assertNull(committed(validOwner, measurement(0f).copy(target = ComposerSendComposerTarget(7L, Float.NaN)), 0f))
    }
}
