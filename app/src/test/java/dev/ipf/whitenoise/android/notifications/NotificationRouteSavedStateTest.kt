package dev.ipf.whitenoise.android.notifications

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationRouteSavedStateTest {
    @Test
    fun pendingExactMessageRouteSurvivesActivityStateRoundTrip() {
        val target =
            NotificationTarget(
                accountRef = "secondary-account",
                groupIdHex = "group-586",
                messageIdHex = "exact-message-586",
                kind = NotificationTargetKind.MESSAGE,
            )
        val bundle = Bundle()

        bundle.putNotificationRouteState(
            latestRequestId = 17L,
            pendingRoute = PendingNotificationRoute(target = target, requestId = 17L),
        )

        assertEquals(
            RestoredNotificationRouteState(
                latestRequestId = 17L,
                pendingRoute = PendingNotificationRoute(target = target, requestId = 17L),
            ),
            bundle.restoreNotificationRouteState(),
        )
    }

    @Test
    fun settledRouteKeepsRequestCounterWithoutReplayingTarget() {
        val bundle = Bundle()

        bundle.putNotificationRouteState(
            latestRequestId = 17L,
            pendingRoute = null,
        )

        val restored = bundle.restoreNotificationRouteState()
        assertEquals(17L, restored.latestRequestId)
        assertNull(restored.pendingRoute)
    }
}
