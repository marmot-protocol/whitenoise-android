package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.state.shouldReconnectNotificationsOnNetworkRestore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationNetworkReconnectCoverageTest {
    @Test
    fun reconnectOnlyAfterOfflineToOnlineTransition() {
        assertFalse(shouldReconnectNotificationsOnNetworkRestore(wasOnline = false, isOnline = false))
        assertFalse(shouldReconnectNotificationsOnNetworkRestore(wasOnline = true, isOnline = true))
        assertFalse(shouldReconnectNotificationsOnNetworkRestore(wasOnline = true, isOnline = false))
        assertTrue(shouldReconnectNotificationsOnNetworkRestore(wasOnline = false, isOnline = true))
    }

    @Test
    fun initialNetworkSeedEstablishesBaselineWithoutReconnect() {
        val listener = appStateSource().readText().functionBody("registerActiveNetworkListener")
        val seed = listener.substringBefore("cm.registerDefaultNetworkCallback")

        assertTrue(
            "the one-shot seed must establish the current availability baseline directly",
            "hasActiveNetworkSnapshot = network != null" in seed,
        )
        assertFalse(
            "an initially online process must not be mistaken for an offline -> online transition",
            "noteActiveNetworkSnapshot(" in seed,
        )
        assertTrue(
            "an armed push catch-up must still drain when the initial snapshot is online",
            "if (network != null) schedulePendingPushWakeCatchUpDrain()" in seed,
        )
    }

    @Test
    fun offlineToOnlineTransitionReconnectsNotificationRuntimeWithoutPushWakeMarker() {
        val appState = appStateSource().readText()
        val networkListener = appState.functionBody("registerActiveNetworkListener")
        val networkSnapshot = appState.functionBody("noteActiveNetworkSnapshot")
        val reconnect = appState.functionBody("scheduleNotificationReconnectOnNetworkRestore")

        assertTrue(
            "connectivity callbacks must funnel through the shared snapshot helper",
            "noteActiveNetworkSnapshot(" in networkListener,
        )
        assertTrue(
            "offline -> online must schedule notification reconnect",
            "shouldReconnectNotificationsOnNetworkRestore(wasOnline" in networkSnapshot &&
                "scheduleNotificationReconnectOnNetworkRestore()" in networkSnapshot,
        )
        assertTrue(
            "notification reconnect must interrupt the active subscription/retry loop",
            "notificationJob.cancelAndJoin()" in reconnect,
        )
        assertTrue(
            "notification reconnect must bootstrap runtime without push-wake gating",
            "ensureNotificationRuntimeStarted()" in reconnect &&
                "pushWakeCatchUpPending()" !in reconnect,
        )
        assertTrue(
            "reconnect requests must be coalesced while in flight",
            "notificationReconnectJob.startIfInactive" in reconnect,
        )
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")
}
