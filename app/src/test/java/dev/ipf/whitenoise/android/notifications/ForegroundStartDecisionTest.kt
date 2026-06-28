package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure [decideForegroundStart] truth table that
 * [NotificationStreamForegroundService.onStartCommand] routes through.
 *
 * Scope: these tests pin only the *decision mapping* (start result + bootstrap
 * state + sync-only action -> the [ForegroundStartDecision] variant). They do
 * NOT exercise the Android-bound side effects in `onStartCommand` (calling
 * `onBackgroundConnectionStartRejected()`, `stopSelf`, launching the bootstrap,
 * or the `runCatching` around `startForeground`); that wiring is verified by
 * reviewer inspection and is out of scope here (would need an instrumented test).
 * What these tests guarantee is that the decision branch a refactor selects
 * cannot drift from the intended truth table.
 *
 * The two contracts pinned at the decision layer:
 *
 * - **Rejection-notification contract (#164):** a failed background-connection
 *   `startForeground` MUST map to
 *   [ForegroundStartDecision.RejectBackgroundConnectionStartAndStop] — the
 *   precondition for AppState being told the start failed so the toggle stops
 *   lying.
 * - **Sync-only rejection contract (#755):** a failed native-push sync nudge
 *   MUST map to [ForegroundStartDecision.RejectNativePushSyncAndStop] so token
 *   rotation retry does not disable Keep Connected or show its toast.
 * - **Bootstrap-dedupe idempotency contract:** a successful start with a bootstrap already in flight
 *   MUST map to [ForegroundStartDecision.KeepRunningExistingBootstrap] so repeated `onStartCommand`
 *   calls (which Android does) do not stack notification-runtime bootstraps.
 */
class ForegroundStartDecisionTest {
    @Test
    fun backgroundStartForegroundFailedRejectsBackgroundConnectionAndStops() {
        // Rejection-notification contract: a failed background-connection start (#164)
        // must reject regardless of bootstrap state.
        assertEquals(
            ForegroundStartDecision.RejectBackgroundConnectionStartAndStop,
            decideForegroundStart(
                startForegroundSucceeded = false,
                bootstrapInFlight = false,
                syncNativePushRegistrationRequested = false,
            ),
        )
    }

    @Test
    fun backgroundStartForegroundFailedRejectsBackgroundConnectionEvenWithBootstrapInFlight() {
        // start failed dominates: even if a stale bootstrap is somehow active, the start was rejected.
        assertEquals(
            ForegroundStartDecision.RejectBackgroundConnectionStartAndStop,
            decideForegroundStart(
                startForegroundSucceeded = false,
                bootstrapInFlight = true,
                syncNativePushRegistrationRequested = false,
            ),
        )
    }

    @Test
    fun syncOnlyStartForegroundFailureStopsWithoutRejectingBackgroundConnection() {
        // #755 review fix: a token-rotation re-registration nudge is not a user
        // request to keep a background connection alive. If Android rejects the
        // foreground start, the service must stop without calling
        // onBackgroundConnectionStartRejected(), otherwise Keep Connected is
        // disabled and the wrong toast is shown.
        assertEquals(
            ForegroundStartDecision.RejectNativePushSyncAndStop,
            decideForegroundStart(
                startForegroundSucceeded = false,
                bootstrapInFlight = false,
                syncNativePushRegistrationRequested = true,
            ),
        )
    }

    @Test
    fun startForegroundSucceededWithoutActiveBootstrapBootstrapsAndKeeps() {
        assertEquals(
            ForegroundStartDecision.BootstrapAndKeep,
            decideForegroundStart(
                startForegroundSucceeded = true,
                bootstrapInFlight = false,
                syncNativePushRegistrationRequested = false,
            ),
        )
    }

    @Test
    fun startForegroundSucceededWithActiveBootstrapKeepsRunningExistingBootstrap() {
        // Idempotency contract: do not launch a second bootstrap when one is already in flight.
        assertEquals(
            ForegroundStartDecision.KeepRunningExistingBootstrap,
            decideForegroundStart(
                startForegroundSucceeded = true,
                bootstrapInFlight = true,
                syncNativePushRegistrationRequested = false,
            ),
        )
    }

    @Test
    fun coversAllFourTruthTableCombinations() {
        // Exhaustive truth table for the two booleans, mapped 1:1 to the onStartCommand actions.
        val table: Map<Pair<Boolean, Boolean>, ForegroundStartDecision> =
            mapOf(
                (false to false) to ForegroundStartDecision.RejectBackgroundConnectionStartAndStop,
                (false to true) to ForegroundStartDecision.RejectBackgroundConnectionStartAndStop,
                (true to false) to ForegroundStartDecision.BootstrapAndKeep,
                (true to true) to ForegroundStartDecision.KeepRunningExistingBootstrap,
            )

        for ((inputs, expected) in table) {
            val (started, bootstrapInFlight) = inputs
            assertEquals(
                "started=$started, bootstrapInFlight=$bootstrapInFlight",
                expected,
                decideForegroundStart(
                    startForegroundSucceeded = started,
                    bootstrapInFlight = bootstrapInFlight,
                    syncNativePushRegistrationRequested = false,
                ),
            )
        }
    }

    @Test
    fun onlyUserToggleRejectionReconcilesBackgroundConnectionPreference() {
        assertEquals(true, shouldReconcileBackgroundConnectionRejection(ForegroundStartTrigger.UserToggle))
        assertEquals(false, shouldReconcileBackgroundConnectionRejection(ForegroundStartTrigger.PushWake))
        assertEquals(false, shouldReconcileBackgroundConnectionRejection(ForegroundStartTrigger.SystemWake))
    }

    @Test
    fun nativePushSyncActionRequestsOneShotSync() {
        assertEquals(
            true,
            shouldSyncNativePushRegistration(
                "dev.ipf.whitenoise.android.notifications.SYNC_NATIVE_PUSH_REGISTRATION",
            ),
        )
        assertEquals(false, shouldSyncNativePushRegistration(null))
        assertEquals(
            false,
            shouldSyncNativePushRegistration(
                "dev.ipf.whitenoise.android.notifications.START_STREAM_FOREGROUND_SERVICE",
            ),
        )
    }

    @Test
    fun oneShotNativePushSyncStopsUnlessKeepConnectedIsEnabled() {
        assertEquals(
            true,
            shouldStopAfterNativePushRegistrationSync(
                syncRequested = true,
                backgroundConnectionEnabled = false,
            ),
        )
        assertEquals(
            false,
            shouldStopAfterNativePushRegistrationSync(
                syncRequested = true,
                backgroundConnectionEnabled = true,
            ),
        )
        assertEquals(
            false,
            shouldStopAfterNativePushRegistrationSync(
                syncRequested = false,
                backgroundConnectionEnabled = false,
            ),
        )
    }
}
