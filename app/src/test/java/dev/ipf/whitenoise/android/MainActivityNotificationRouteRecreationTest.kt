package dev.ipf.whitenoise.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationRouteTrace
import dev.ipf.whitenoise.android.notifications.NotificationTapTokens
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.PendingNotificationRoute
import dev.ipf.whitenoise.android.notifications.RestoredNotificationRouteState
import dev.ipf.whitenoise.android.notifications.restoreNotificationRouteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityNotificationRouteRecreationTest {
    @Test
    fun notificationRouteRearmsAfterRecreationAndClearsOnlyAfterHandledFrameSettles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationKey = "activity-recreation-route"
        val tapTokens = NotificationTapTokens.create(context)
        val target =
            NotificationTarget(
                accountRef = "secondary-account",
                groupIdHex = "group-586",
                messageIdHex = "exact-message-586",
                kind = NotificationTargetKind.MESSAGE,
            )
        val tapIntent =
            Intent(context, MainActivity::class.java).also { intent ->
                NotificationNavigation.applyToIntent(
                    intent = intent,
                    target = target,
                    notificationKey = notificationKey,
                    tapToken = tapTokens.tokenFor(notificationKey),
                )
            }
        val expectedPending =
            RestoredNotificationRouteState(
                latestRequestId = 1L,
                pendingRoute = PendingNotificationRoute(target = target, requestId = 1L),
            )
        val firstController = Robolectric.buildActivity(MainActivity::class.java, tapIntent)
        var firstDestroyed = false
        var recreatedController: ActivityController<MainActivity>? = null
        try {
            firstController.create()
            val savedState = Bundle()
            firstController.saveInstanceState(savedState)
            assertEquals(expectedPending, savedState.restoreNotificationRouteState())
            firstController.destroy()
            firstDestroyed = true

            recreatedController =
                Robolectric
                    .buildActivity(
                        MainActivity::class.java,
                        Intent(context, MainActivity::class.java),
                    ).create(savedState)
            val rearmedState = Bundle()
            recreatedController.saveInstanceState(rearmedState)
            assertEquals(expectedPending, rearmedState.restoreNotificationRouteState())

            recreatedController.get().handleNotificationTarget(target, handledRequestId = 1L)
            recreatedController.get().handleNotificationRouteSettled(requestId = 1L)
            val settledState = Bundle()
            recreatedController.saveInstanceState(settledState)
            val restoredSettled = settledState.restoreNotificationRouteState()
            assertEquals(1L, restoredSettled.latestRequestId)
            assertNull(restoredSettled.pendingRoute)
        } finally {
            if (!firstDestroyed) firstController.destroy()
            recreatedController?.destroy()
            NotificationRouteTrace.finishRequest(1L)
            tapTokens.remove(notificationKey)
        }
    }
}
