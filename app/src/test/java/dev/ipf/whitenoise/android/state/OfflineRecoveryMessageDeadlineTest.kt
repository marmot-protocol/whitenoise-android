package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The offline-recovery deadline is measured to the controlled message, never to a generic frame (#3723). */
class OfflineRecoveryMessageDeadlineTest {
    private val edge = OfflineReconnectEdge(generationBaseline = 7L, atMillis = 10_000L)

    /** An unrelated frame at one second cannot hide a target that only appears at fourteen seconds. */
    @Test
    fun unrelatedEarlyFrameDoesNotCompleteALateTarget() {
        val samples = listOf(frame(generation = 8L, elapsedMillis = 1_000L))

        assertEquals(8L, recoveryGenerationAfter(samples, edge))
        assertFailed(outcome(seen(1_000L, 0), seen(14_000L, 1)))
    }

    /** One target row inside five seconds completes the cycle with its own latency. */
    @Test
    fun targetWithinTheCeilingCompletes() {
        assertEquals(ControlledMessageOutcome.Completed(4_200L), outcome(seen(-500L, 0), seen(4_200L, 1)))
        assertEquals(ControlledMessageOutcome.Completed(5_000L), outcome(seen(5_000L, 1)))
    }

    /** A target that never appears, or appears twice, fails under the deadline. */
    @Test
    fun missingOrDuplicateTargetFails() {
        assertFailed(outcome(seen(1_000L, 0), seen(15_000L, 0)))
        assertFailed(outcome(seen(1_000L, 2)))
        assertFailed(outcome(seen(1_000L, 1), seen(1_050L, 2)))
    }

    /** An earlier flap or obsolete generation cannot complete the final reconnect's cycle. */
    @Test
    fun obsoleteReconnectCannotCompleteTheCycle() {
        val samples = listOf(frame(generation = 6L, elapsedMillis = 300L), frame(generation = 7L, elapsedMillis = 400L))

        assertNull(recoveryGenerationAfter(samples, edge))
        assertFailed(outcome(seen(-200L, 1), seen(1_000L, 1)))
    }

    /** A row delivered during a rapid flap, just before the final reconnect, fails instead of completing fast. */
    @Test
    fun rowDeliveredDuringTheFlapFails() {
        assertFailed(outcome(seen(-3_000L, 0), seen(-50L, 1), seen(20L, 1)))
    }

    /** A background cycle whose foreground return lands after the ceiling still fails. */
    @Test
    fun lateBackgroundReturnFails() {
        assertFailed(outcome(seen(-1_000L, 0), seen(6_000L, 0), seen(6_100L, 1)))
    }

    /** Evaluates [observations] against the shared edge and the five-second ceiling. */
    private fun outcome(vararg observations: ControlledMessageObservation): ControlledMessageOutcome =
        controlledMessageOutcome(edge, observations.toList(), ceilingMillis = 5_000L)

    /** Builds an observation [afterEdgeMillis] after the reconnect edge, negative meaning before it. */
    private fun seen(
        afterEdgeMillis: Long,
        visibleCount: Int,
    ) = ControlledMessageObservation(edge.atMillis + afterEdgeMillis, visibleCount)

    /** Builds a successful first-visible-frame marker for [generation]. */
    private fun frame(
        generation: Long,
        elapsedMillis: Long,
    ) = NotificationNetworkRecoverySample(
        generation = generation,
        phase = PerformancePhase.RECOVERY_FIRST_VISIBLE_FRAME,
        elapsedMillis = elapsedMillis,
        result = PerformanceResult.SUCCESS,
        layer = PerformanceLayer.ANDROID,
        attempt = null,
        count = null,
    )

    /** Asserts that [outcome] is a failure. */
    private fun assertFailed(outcome: ControlledMessageOutcome) {
        assertTrue("Expected failure, got $outcome", outcome is ControlledMessageOutcome.Failed)
    }
}
