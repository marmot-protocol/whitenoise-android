package dev.ipf.darkmatter.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure [decideForegroundStart] truth table that
 * [NotificationStreamForegroundService.onStartCommand] routes through.
 *
 * Scope: these tests pin only the *decision mapping* (the two input booleans -> the
 * [ForegroundStartDecision] variant). They do NOT exercise the Android-bound side effects in
 * `onStartCommand` (calling `onBackgroundConnectionStartRejected()`, `stopSelf`, launching the
 * bootstrap, or the `runCatching` around `startForeground`); that wiring is verified by reviewer
 * inspection and is out of scope here (would need an instrumented test). What these tests guarantee
 * is that the decision branch a refactor selects cannot drift from the original truth table.
 *
 * The two contracts pinned at the decision layer:
 *
 * - **Rejection-notification contract (#164):** a failed `startForeground` MUST map to
 *   [ForegroundStartDecision.RejectAndStop] — the precondition for AppState being told the start
 *   failed so the toggle stops lying.
 * - **Bootstrap-dedupe idempotency contract:** a successful start with a bootstrap already in flight
 *   MUST map to [ForegroundStartDecision.KeepRunningExistingBootstrap] so repeated `onStartCommand`
 *   calls (which Android does) do not stack notification-runtime bootstraps.
 */
class ForegroundStartDecisionTest {
    @Test
    fun startForegroundFailedRejectsAndStops() {
        // Rejection-notification contract: a failed start (#164) must reject regardless of bootstrap state.
        assertEquals(
            ForegroundStartDecision.RejectAndStop,
            decideForegroundStart(startForegroundSucceeded = false, bootstrapInFlight = false),
        )
    }

    @Test
    fun startForegroundFailedRejectsAndStopsEvenWithBootstrapInFlight() {
        // start failed dominates: even if a stale bootstrap is somehow active, the start was rejected.
        assertEquals(
            ForegroundStartDecision.RejectAndStop,
            decideForegroundStart(startForegroundSucceeded = false, bootstrapInFlight = true),
        )
    }

    @Test
    fun startForegroundSucceededWithoutActiveBootstrapBootstrapsAndKeeps() {
        assertEquals(
            ForegroundStartDecision.BootstrapAndKeep,
            decideForegroundStart(startForegroundSucceeded = true, bootstrapInFlight = false),
        )
    }

    @Test
    fun startForegroundSucceededWithActiveBootstrapKeepsRunningExistingBootstrap() {
        // Idempotency contract: do not launch a second bootstrap when one is already in flight.
        assertEquals(
            ForegroundStartDecision.KeepRunningExistingBootstrap,
            decideForegroundStart(startForegroundSucceeded = true, bootstrapInFlight = true),
        )
    }

    @Test
    fun coversAllFourTruthTableCombinations() {
        // Exhaustive truth table for the two booleans, mapped 1:1 to the onStartCommand actions.
        val table: Map<Pair<Boolean, Boolean>, ForegroundStartDecision> =
            mapOf(
                (false to false) to ForegroundStartDecision.RejectAndStop,
                (false to true) to ForegroundStartDecision.RejectAndStop,
                (true to false) to ForegroundStartDecision.BootstrapAndKeep,
                (true to true) to ForegroundStartDecision.KeepRunningExistingBootstrap,
            )

        for ((inputs, expected) in table) {
            val (started, bootstrapInFlight) = inputs
            assertEquals(
                "started=$started, bootstrapInFlight=$bootstrapInFlight",
                expected,
                decideForegroundStart(startForegroundSucceeded = started, bootstrapInFlight = bootstrapInFlight),
            )
        }
    }
}
