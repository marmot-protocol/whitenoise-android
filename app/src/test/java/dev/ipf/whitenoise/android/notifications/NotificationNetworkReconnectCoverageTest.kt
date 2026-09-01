package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.state.NotificationJobSlot
import dev.ipf.whitenoise.android.state.awaitActiveNotificationReceiver
import dev.ipf.whitenoise.android.state.runNotificationReconnectOnNetworkRestore
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
    fun initialNetworkSeedEstablishesBaselineWithoutReconnect() {
        val listener = appStateSource().readText().functionBody("registerActiveNetworkListener")
        val seed = listener.substringBefore("cm.registerDefaultNetworkCallback")

        assertTrue(
            "the one-shot seed must establish the current availability baseline directly",
            "hasActiveNetworkSnapshot = activeDefaultNetwork.seed(network?.networkHandle)" in seed,
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
            val recoverySteps = mutableListOf<String>()

            try {
                runNotificationReconnectOnNetworkRestore(
                    wakeDurableOutbound = { recoverySteps += "wake-outbound" },
                    ensureNotificationReceiverActive = { passiveReceiver.ensureReceiverForReconnect() },
                    catchUpAccounts = {
                        recoverySteps += "catch-up"
                        passiveReceiver.emitRelayBacklog("offline-window-message")
                        true
                    },
                )

                assertEquals(listOf("wake-outbound", "catch-up"), recoverySteps)
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
            var outboundWakeRan = false
            var catchUpRan = false

            runNotificationReconnectOnNetworkRestore(
                wakeDurableOutbound = { outboundWakeRan = true },
                ensureNotificationReceiverActive = { false },
                catchUpAccounts = {
                    catchUpRan = true
                    true
                },
            )

            assertTrue("durable outbound recovery must not depend on the receiver", outboundWakeRan)
            assertFalse("catch-up must not run without a confirmed receiver", catchUpRan)
        }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One source-ordering contract is clearer together.
    @Test
    fun offlineToOnlineTransitionReconnectsNotificationRuntimeWithoutPushWakeMarker() {
        val appState = appStateSource().readText()
        val networkListener = appState.functionBody("registerActiveNetworkListener")
        val networkSnapshot = appState.functionBody("noteActiveNetworkSnapshot")
        val validatedListener = appState.functionBody("registerValidatedInternetListener")
        val usableRecovery = appState.functionBody("noteUsableValidatedInternetSnapshot")
        val reconnect = appState.functionBody("scheduleNotificationReconnectOnNetworkRestore")
        val attempt = appState.functionBody("runNotificationNetworkRecoveryAttempt")
        val outboundWake = appState.functionBody("wakeDurableOutboundForNetworkRecovery")
        val receiverRecovery = appState.functionBody("ensureNotificationReceiverForNetworkRecovery")
        val accountCatchUp = appState.functionBody("catchUpAccountsForNetworkRecovery")
        val receiver = appState.functionBody("ensureNotificationReceiverForNetworkReconnect")
        val listenerLoop = appState.functionBody("runNotificationListenerLoop")

        assertTrue(
            "connectivity callbacks must funnel through the shared snapshot helper",
            "noteActiveNetworkSnapshot(" in networkListener,
        )
        assertTrue(
            "both callback streams must feed the aggregate usable-internet edge",
            "noteUsableValidatedInternetSnapshot()" in networkSnapshot &&
                "noteUsableValidatedInternetSnapshot()" in validatedListener,
        )
        assertTrue(
            "only validated aggregate recovery may wake pending sends and schedule reconnect",
            "if (!recovery.restored) return" in usableRecovery &&
                "validatedConnectivityRecoveryGenerationMutable.update" in usableRecovery &&
                "beginNotificationNetworkRecoveryTrace" in usableRecovery &&
                "scheduleNotificationReconnectOnNetworkRestore()" in usableRecovery,
        )
        assertTrue(
            "notification reconnect must reuse the current listener and await its shared receiver state",
            "awaitNotificationReceiverForStartupWithin(" in receiver &&
                "notificationJob.cancelAndJoin()" !in receiver,
        )
        assertTrue(
            "a reconnect wake during subscribe failure or cleanup must skip the pending backoff",
            listenerLoop.indexOf("val retryWakeGeneration") in 0 until
                listenerLoop.indexOf("notificationSubscriber(marmot)") &&
                "awaitNotificationRetryWindow(notificationReceiverRetryWake, retryWakeGeneration" in listenerLoop,
        )
        assertTrue(
            "notification reconnect must wake durable outbound work before catch-up",
            "wakeDurableOutboundForNetworkRecovery" in attempt &&
                "ensureNotificationReceiverForNetworkRecovery" in attempt &&
                "catchUpAccountsForNetworkRecovery" in attempt &&
                "notifyConnectivityRestored()" in outboundWake &&
                "ensureNotificationReceiverForNetworkReconnect" in receiverRecovery &&
                "PerformancePhase.NOTIFICATION_RECEIVER_RETRY" in receiverRecovery &&
                "catchUpAccountsBestEffort()" in accountCatchUp &&
                "PerformancePhase.ACCOUNT_CATCH_UP_RETRY" in accountCatchUp &&
                "pushWakeCatchUpPending()" !in accountCatchUp,
        )
        assertTrue(
            "bootstrap failure must not imply a receiver is ready for reconnect catch-up",
            "if (!bootstrapCompleted) bootstrap()" in receiver &&
                "if (!bootstrapCompleted || networkNotificationRecoverySuppressed) return false" in receiver,
        )
        assertTrue(
            "reconnect requests must be coalesced while in flight",
            "notificationReconnectRequestedGeneration.accumulateAndGet" in reconnect &&
                "notificationReconnectJob.startIfInactive" in reconnect &&
                "drainNotificationNetworkRecovery" in reconnect,
        )
        assertTrue(
            "a receiver timeout or catch-up failure must remain queued after the current job settles",
            "notificationReconnectRequestedGeneration.get()" in reconnect &&
                "notificationReconnectCompletedGeneration.get()" in reconnect &&
                "scheduleNotificationReconnectOnNetworkRestore()" in reconnect.substringAfter("invokeOnCompletion"),
        )
    }

    @Test
    fun foregroundCatchUpWakesDurableOutboundBeforeAccountCatchUp() {
        val body = appStateSource().readText().functionBody("catchUpAfterForegroundActivation")
        val wake = body.indexOf("notifyConnectivityRestored()")
        val catchUp = body.indexOf("catchUpAccountsBestEffort()")

        assertTrue(
            "foreground recovery must wake retained outbound work before account catch-up",
            "hasValidatedInternet()" in body && wake >= 0 && catchUp > wake,
        )
    }

    @Suppress("LongMethod") // One source-ordering scenario is clearer as a single regression contract.
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
            "backgroundConnectionEnabled" in prepare &&
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
            "destructive teardown must invalidate captured service retries before suppression or suspension",
            prepare.indexOf("notificationRuntimeRecovery.advance()") in 0 until
                prepare.indexOf("networkNotificationRecoverySuppressed = true"),
        )
        assertTrue(
            "service retries must require both an unchanged wipe generation and active recovery",
            Regex(
                """fun\s+notificationRuntimeRecoveryAllowed\(generation:\s*Long\):\s*Boolean\s*=\s*""" +
                    """!networkNotificationRecoverySuppressed\s*&&\s*""" +
                    """notificationRuntimeRecovery\.isCurrent\(generation\)""",
            ).containsMatchIn(appState),
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
        val post = appState.functionBody("enrichPostedNotificationUpdate")
        val notificationGates =
            appState
                .substringAfter("private fun isNotificationGenerationPostAllowed")
                .substringBefore("/**")
        assertTrue(
            "a wipe that starts during notification enrichment must block the final presenter write",
            "isNotificationEnrichmentAllowed(update, firstPost.epoch, firstPost.engineMuted)" in post &&
                "isNotificationGenerationPostAllowed(" in notificationGates &&
                "!networkNotificationRecoverySuppressed" in notificationGates,
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
