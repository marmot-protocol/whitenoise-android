package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.state.NotificationJobSlot
import dev.ipf.whitenoise.android.state.awaitActiveNotificationReceiver
import dev.ipf.whitenoise.android.state.runNotificationReconnectOnNetworkRestore
import dev.ipf.whitenoise.android.state.shouldReconnectNotificationsOnNetworkRestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun networkAvailableWithoutCapabilitiesClearsStaleTransportTypes() {
        val networkSnapshot = appStateSource().readText().functionBody("noteActiveNetworkSnapshot")

        assertTrue(
            "onAvailable must conservatively block auto-download until the new network capabilities arrive",
            "activeNetworkTypesSnapshot = networkTypes ?: emptySet()" in networkSnapshot,
        )
    }

    @Test
    fun offlineToOnlineRecoveryPostsMissedNotificationWithoutPushWakeMarker() =
        runTest {
            val passiveReceiver = PassiveNotificationBroadcastFake(this)
            passiveReceiver.startInitialListener()

            try {
                runNotificationReconnectOnNetworkRestore(
                    ensureNotificationReceiverActive = { passiveReceiver.ensureReceiverForReconnect() },
                    catchUpAccounts = { passiveReceiver.emitRelayBacklog("offline-window-message") },
                )

                assertEquals(
                    "relay backlog must be posted only after the receiver is active",
                    listOf("offline-window-message"),
                    passiveReceiver.postedNotifications,
                )
                assertTrue(
                    "catch-up must not run before the receiver is listening",
                    passiveReceiver.catchUpStartedAfterReceiver,
                )
            } finally {
                passiveReceiver.close()
            }
        }

    @Test
    fun offlineToOnlineRecoverySkipsCatchUpWhenReceiverCannotBeEstablished() =
        runTest {
            var catchUpRan = false

            runNotificationReconnectOnNetworkRestore(
                ensureNotificationReceiverActive = { false },
                catchUpAccounts = { catchUpRan = true },
            )

            assertFalse("catch-up must not run without a confirmed receiver", catchUpRan)
        }

    @Test
    fun offlineToOnlineTransitionReconnectsNotificationRuntimeWithoutPushWakeMarker() {
        val appState = appStateSource().readText()
        val networkListener = appState.functionBody("registerActiveNetworkListener")
        val networkSnapshot = appState.functionBody("noteActiveNetworkSnapshot")
        val reconnect = appState.functionBody("scheduleNotificationReconnectOnNetworkRestore")
        val receiver = appState.functionBody("ensureNotificationReceiverForNetworkReconnect")
        val listenerLoop = appState.functionBody("runNotificationListenerLoop")

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
            "notification reconnect must reuse the current listener and await its shared receiver state",
            "awaitNotificationReceiverForStartup(" in receiver &&
                "notificationJob.cancelAndJoin()" !in receiver,
        )
        assertTrue(
            "a reconnect wake during subscribe failure or cleanup must skip the pending backoff",
            listenerLoop.indexOf("val retryWakeGeneration") in 0 until
                listenerLoop.indexOf("notificationSubscriber(marmot)") &&
                "awaitNotificationRetryWindow(notificationReceiverRetryWake, retryWakeGeneration" in listenerLoop,
        )
        assertTrue(
            "notification reconnect must establish receiver before catch-up without push-wake gating",
            "ensureNotificationReceiverForNetworkReconnect" in reconnect &&
                "catchUpAccountsBestEffort()" in reconnect &&
                reconnect.indexOf("ensureNotificationReceiverForNetworkReconnect") <
                reconnect.indexOf("catchUpAccountsBestEffort()") &&
                "pushWakeCatchUpPending()" !in reconnect,
        )
        assertTrue(
            "bootstrap failure must not imply a receiver is ready for reconnect catch-up",
            "if (!bootstrapCompleted) bootstrap()" in receiver &&
                "if (!bootstrapCompleted || networkNotificationRecoverySuppressed) return false" in receiver,
        )
        assertTrue(
            "reconnect requests must be coalesced while in flight",
            "notificationReconnectJob.startIfInactive" in reconnect,
        )
    }

    @Test
    fun accountTeardownCancelsReconnectOwnersBeforeTheListener() {
        val appState = appStateSource().readText()
        val prepare = appState.functionBody("prepareForDestructiveAccountWipe")
        val restore = appState.functionBody("restoreAfterFailedDestructiveAccountWipe")
        val teardown = appState.functionBody("stopNotificationListenerForAccountTeardown")
        val wipe = appState.functionBody("signOutAndWipeActiveAccount")
        val reconnect = appState.functionBody("scheduleNotificationReconnectOnNetworkRestore")
        val pendingPushDrain = appState.functionBody("schedulePendingPushWakeCatchUpDrain")

        assertTrue(
            "failed-wipe recovery must remember every notification runtime owner",
            "notificationJob.isActive()" in prepare &&
                "notificationReconnectJob.isActive()" in prepare &&
                "pushWakeCatchUpDrainJob.isActive()" in prepare,
        )
        assertTrue(
            "teardown must cancel producers before they can reinstall the notification listener",
            teardown.indexOf("notificationReconnectJob.cancelAndJoin()") in 0 until
                teardown.indexOf("pushWakeCatchUpDrainJob.cancelAndJoin()") &&
                teardown.indexOf("pushWakeCatchUpDrainJob.cancelAndJoin()") <
                teardown.indexOf("notificationJob.cancelAndJoin()"),
        )
        assertTrue(
            "teardown must suppress connectivity callbacks before its first suspension",
            prepare.indexOf("networkNotificationRecoverySuppressed = true") in 0 until
                prepare.indexOf("closeLiveSubscriptionsForAccountTeardown"),
        )
        assertTrue(
            "connectivity callbacks must not reinstall notification work during account teardown",
            "if (networkNotificationRecoverySuppressed) return" in reconnect &&
                "if (networkNotificationRecoverySuppressed) return@launch" in reconnect &&
                "if (networkNotificationRecoverySuppressed ||" in pendingPushDrain,
        )
        assertTrue(
            "failed and successful wipes must release reconnect suppression before listener restart",
            restore.indexOf("networkNotificationRecoverySuppressed = false") in 0 until
                restore.indexOf("startNotificationListener()") &&
                wipe.indexOf("networkNotificationRecoverySuppressed = false") in 0 until
                wipe.lastIndexOf("startNotificationListener()"),
        )
        assertTrue(
            "the wipe must clear reconnect suppression in a finally backstop so a throw cannot latch it",
            wipe.indexOf("prepareForDestructiveAccountWipe(wipedRef)") > wipe.indexOf("try {") &&
                wipe.indexOf("prepareForDestructiveAccountWipe(wipedRef)") < wipe.lastIndexOf("} finally {") &&
                wipe.lastIndexOf("networkNotificationRecoverySuppressed = false") > wipe.lastIndexOf("} finally {"),
        )
        val post = appState.functionBody("postNotificationUpdate")
        assertTrue(
            "a wipe that starts during notification enrichment must block the final presenter write",
            "!networkNotificationRecoverySuppressed" in post,
        )
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")

    /**
     * Models [subscribeNotifications] at the pinned MDK revision: passive broadcast
     * with no replay — events emitted while no subscriber is attached are lost.
     */
    private class PassiveNotificationBroadcastFake(
        private val scope: CoroutineScope,
    ) {
        private val slot = NotificationJobSlot()
        private var subscriberCount = 0
        val postedNotifications = mutableListOf<String>()
        var catchUpStartedAfterReceiver = false
            private set

        fun startInitialListener() {
            slot.startIfInactive {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    subscriberCount += 1
                    try {
                        awaitCancellation()
                    } finally {
                        subscriberCount -= 1
                    }
                }
            }
        }

        suspend fun ensureReceiverForReconnect(): Boolean {
            val listenerJob =
                slot.currentOrStart {
                    error("the already-active passive receiver should be reused")
                } ?: return false
            return awaitActiveNotificationReceiver(
                isReceiverActive = { subscriberCount > 0 },
                listenerJob = listenerJob,
                awaitReceiverActive = { error("an active receiver must not suspend for readiness") },
            )
        }

        fun emitRelayBacklog(message: String) {
            catchUpStartedAfterReceiver = subscriberCount > 0
            if (subscriberCount > 0) {
                postedNotifications += message
            }
        }

        suspend fun close() {
            slot.cancelAndJoin()
        }
    }
}
