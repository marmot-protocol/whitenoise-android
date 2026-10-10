package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import dev.ipf.whitenoise.android.notifications.NotificationRouteTrace
import dev.ipf.whitenoise.android.notifications.NotificationRouteTraceSection

/**
 * Marks the window in which the root drew a loading surface while a notification route was in flight.
 * With the retained route owning the shell this should never open, so any slice is a regression (#586).
 * The request id is only an in-process ownership token and never appears in a section name.
 */
@Composable
@Suppress("FunctionNaming")
internal fun NotificationRouteStartupSwapTrace(requestId: Long) {
    DisposableEffect(requestId) {
        NotificationRouteTrace.beginPhase(requestId, NotificationRouteTraceSection.STARTUP_SWAP)
        onDispose { NotificationRouteTrace.endPhase(requestId, NotificationRouteTraceSection.STARTUP_SWAP) }
    }
}

/**
 * Keeps human unlock time out of the route's total: while the staged tap waits behind app lock the total
 * slice is closed and the wait is its own slice, then the total resumes for the same request on unlock.
 */
@Composable
@Suppress("FunctionNaming")
internal fun NotificationRouteAppLockTrace(
    requestId: Long,
    routePending: Boolean,
    locked: Boolean,
) {
    DisposableEffect(requestId, routePending, locked) {
        val waiting = routePending && locked
        if (waiting) NotificationRouteTrace.pauseForAppLock(requestId)
        onDispose { if (waiting) NotificationRouteTrace.resumeAfterAppLock(requestId) }
    }
}
