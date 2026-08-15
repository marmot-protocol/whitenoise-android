package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationRuntimeBootstrapDecisionTest {
    @Test
    fun terminalSuccessClearsThePendingUserOwnedStart() {
        assertEquals(
            NotificationRuntimeBootstrapDecision(
                action = NotificationRuntimeBootstrapAction.Finish,
                completedPushWakeGeneration = 4L,
                pendingUserOwnedStart = false,
            ),
            decide(
                outcome = NotificationRuntimeSupervisionOutcome.Started(attempts = 1),
                snapshot =
                    idleSnapshot.copy(
                        completedPushWakeGeneration = 4L,
                        pendingUserOwnedStart = true,
                    ),
            ),
        )
    }

    @Test
    fun successfulPushAttemptCompletesOnlyItsCapturedGeneration() {
        assertEquals(
            NotificationRuntimeBootstrapDecision(
                action = NotificationRuntimeBootstrapAction.Continue,
                completedPushWakeGeneration = 8L,
                pendingUserOwnedStart = false,
            ),
            decide(
                outcome = NotificationRuntimeSupervisionOutcome.Started(attempts = 2),
                snapshot =
                    idleSnapshot.copy(
                        attemptedPushWakeGeneration = 8L,
                        pendingPushWakeGeneration = 9L,
                        completedPushWakeGeneration = 7L,
                        pendingUserOwnedStart = true,
                    ),
            ),
        )
    }

    @Test
    fun successfulPreWrapAttemptCannotConsumeThePostWrapWake() {
        assertEquals(
            NotificationRuntimeBootstrapDecision(
                action = NotificationRuntimeBootstrapAction.Continue,
                completedPushWakeGeneration = 0L,
                pendingUserOwnedStart = false,
            ),
            decide(
                outcome = NotificationRuntimeSupervisionOutcome.Started(attempts = 1),
                snapshot =
                    idleSnapshot.copy(
                        attemptedStartId = 31,
                        latestStartId = 32,
                        attemptedPushWakeGeneration = Long.MAX_VALUE,
                        pendingPushWakeGeneration = 1L,
                    ),
            ),
        )
    }

    @Test
    fun exhaustionGivesAPushQueuedDuringBackoffItsOwnBoundedRound() {
        assertEquals(
            NotificationRuntimeBootstrapDecision(
                action = NotificationRuntimeBootstrapAction.Continue,
                completedPushWakeGeneration = 5L,
                pendingUserOwnedStart = false,
            ),
            decide(
                outcome = exhausted(),
                snapshot =
                    idleSnapshot.copy(
                        attemptedPushWakeGeneration = 6L,
                        pendingPushWakeGeneration = 7L,
                        completedPushWakeGeneration = 5L,
                    ),
            ),
        )
    }

    @Test
    fun exhaustionDoesNotConsumeANewerServiceStartId() {
        assertEquals(
            NotificationRuntimeBootstrapAction.Continue,
            decide(
                outcome = exhausted(),
                snapshot = idleSnapshot.copy(attemptedStartId = 12, latestStartId = 13),
            ).action,
        )
    }

    @Test
    fun terminalUserOwnedExhaustionStopsAndReconciles() {
        assertEquals(
            NotificationRuntimeBootstrapDecision(
                action = NotificationRuntimeBootstrapAction.StopAfterExhaustion,
                completedPushWakeGeneration = 3L,
                pendingUserOwnedStart = false,
                reconcileUserOwnedFailure = true,
            ),
            decide(
                outcome = exhausted(),
                snapshot =
                    idleSnapshot.copy(
                        pendingPushWakeGeneration = 3L,
                        completedPushWakeGeneration = 3L,
                        pendingUserOwnedStart = true,
                    ),
            ),
        )
    }

    @Test
    fun priorSuccessPreventsLaterSystemWakeExhaustionFromDisablingThePreference() {
        val success =
            decide(
                outcome = NotificationRuntimeSupervisionOutcome.Started(attempts = 1),
                snapshot = idleSnapshot.copy(pendingUserOwnedStart = true),
            )

        val laterExhaustion =
            decide(
                outcome = exhausted(),
                snapshot = idleSnapshot.copy(pendingUserOwnedStart = success.pendingUserOwnedStart),
            )

        assertEquals(false, laterExhaustion.reconcileUserOwnedFailure)
    }

    @Test
    fun successfulRuntimeKeepsGoingWhenNativePushSyncWasQueued() {
        assertEquals(
            NotificationRuntimeBootstrapAction.Continue,
            decide(
                outcome = NotificationRuntimeSupervisionOutcome.Started(attempts = 1),
                snapshot = idleSnapshot.copy(pendingNativePushRegistrationSync = true),
            ).action,
        )
    }

    @Test
    fun destructiveRecoveryBoundaryAlwaysFinishesWithoutReconciliation() {
        assertEquals(
            NotificationRuntimeBootstrapDecision(
                action = NotificationRuntimeBootstrapAction.Finish,
                completedPushWakeGeneration = 2L,
                pendingUserOwnedStart = false,
            ),
            decide(
                outcome = NotificationRuntimeSupervisionOutcome.RecoveryBoundaryChanged(attempts = 1),
                snapshot =
                    idleSnapshot.copy(
                        attemptedStartId = 20,
                        latestStartId = 21,
                        pendingPushWakeGeneration = 3L,
                        completedPushWakeGeneration = 2L,
                        pendingUserOwnedStart = true,
                    ),
            ),
        )
    }

    private fun decide(
        outcome: NotificationRuntimeSupervisionOutcome,
        snapshot: NotificationRuntimeBootstrapSnapshot = idleSnapshot,
    ): NotificationRuntimeBootstrapDecision =
        decideNotificationRuntimeBootstrap(
            outcome = outcome,
            snapshot = snapshot,
        )

    private fun exhausted() =
        NotificationRuntimeSupervisionOutcome.Exhausted(
            attempts = 4,
            failureClass = "RelayFailure",
        )

    private companion object {
        val idleSnapshot =
            NotificationRuntimeBootstrapSnapshot(
                attemptedStartId = 10,
                latestStartId = 10,
                attemptedPushWakeGeneration = null,
                pendingPushWakeGeneration = 0L,
                completedPushWakeGeneration = 0L,
                pendingNativePushRegistrationSync = false,
                pendingUserOwnedStart = false,
            )
    }
}
