package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure [decidePushTokenRotation] truth table that
 * [MarmotFirebaseMessagingService.onNewToken] routes through.
 *
 * The bug this guards (#755): the old fallback branch (taken when
 * `applicationContext` does not resolve to `WhiteNoiseApplication`) only
 * persisted the rotated FCM token and never scheduled native push
 * re-registration, so the MIP-05 push server kept the stale token until a
 * later foreground sync. The invariant pinned here is that token persistence
 * can NEVER diverge from scheduling re-registration: whichever route handles
 * the rotation, both effects happen together.
 */
class PushTokenRotationDispatchTest {
    @Test
    fun runtimeReachableRoutesThroughAppStateOnly() {
        // When the runtime is reachable, AppState.onPushTokenRotated already
        // persists + schedules atomically, so the dispatcher delegates wholly.
        assertEquals(
            PushTokenRotationDispatch.ToAppRuntime,
            decidePushTokenRotation(appRuntimeReachable = true),
        )
    }

    @Test
    fun runtimeUnreachablePersistsAndSchedulesReRegistration() {
        // The #755 fix: the fallback must do BOTH — persist the token and
        // schedule re-registration — never persist alone.
        assertEquals(
            PushTokenRotationDispatch.PersistAndScheduleReRegistration,
            decidePushTokenRotation(appRuntimeReachable = false),
        )
    }

    @Test
    fun fallbackNeverPersistsWithoutSchedulingReRegistration() {
        // Regression guard for the exact #755 divergence: there is no decision
        // value that persists the token without also scheduling re-registration.
        val unreachable = decidePushTokenRotation(appRuntimeReachable = false)
        assertEquals(PushTokenRotationDispatch.PersistAndScheduleReRegistration, unreachable)
        assertEquals(
            "fallback must couple persistence with re-registration",
            true,
            unreachable.persistsToken && unreachable.schedulesReRegistration,
        )
    }
}
