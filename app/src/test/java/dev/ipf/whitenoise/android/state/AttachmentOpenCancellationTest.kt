package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cancelling a transfer also revokes its persisted viewer handoff. That
 * revocation needs disk work, so it lands after the click returns, and a user
 * who taps again in the meantime must keep the intent that second tap created.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentOpenCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences("attachment-open-cancellation-test", Context.MODE_PRIVATE)
    }

    @Before
    fun reset() {
        preferences.edit().clear().commit()
    }

    @Test
    fun cancellingAPendingOpenRevokesItsPersistedHandoff() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = AttachmentDownloadIntentStore(preferences)
                val coordinator = coordinator(store, scope, dispatcher)
                coordinator.setDestination(DESTINATION)
                assertTrue(coordinator.requestOpen(REQUEST))
                assertTrue(store.hasOpenIntent(openRequest()))

                coordinator.cancelOpen(openRequest())
                testScheduler.advanceUntilIdle()

                assertFalse(store.hasOpenIntent(openRequest()))
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun aTapDuringAnInFlightRevocationKeepsItsFreshHandoff() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = AttachmentDownloadIntentStore(preferences)
                val coordinator = coordinator(store, scope, dispatcher)
                coordinator.setDestination(DESTINATION)
                assertTrue(coordinator.requestOpen(REQUEST))

                // The revocation is queued but has not reached disk yet.
                coordinator.cancelOpen(openRequest())
                assertTrue(coordinator.requestOpen(REQUEST))
                testScheduler.advanceUntilIdle()

                assertTrue(
                    "the second tap's handoff must survive the earlier cancel",
                    store.hasOpenIntent(openRequest()),
                )
            } finally {
                scope.cancel()
            }
        }

    /** Reserving native Retry must not rescue an older cancelled intent before any new intent exists. */
    @Test
    fun pendingRetryReservationDoesNotPreventOldIntentRevocation() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = AttachmentDownloadIntentStore(preferences)
                val coordinator = coordinator(store, scope, dispatcher)
                coordinator.setDestination(DESTINATION)
                coordinator.requestOpen(REQUEST)
                coordinator.cancelOpen(openRequest())
                val pending = coordinator.beginUserAction()
                testScheduler.advanceUntilIdle()
                assertTrue(coordinator.isCurrentUserAction(pending))
                assertFalse(store.hasOpenIntent(openRequest()))
            } finally {
                scope.cancel()
            }
        }

    /** A newer file tap or navigation fences delayed retry handoff from the older gesture. */
    @Test
    fun delayedRetryCannotSupersedeANewerOpenOrDestination() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = AttachmentDownloadIntentStore(preferences)
                val coordinator = coordinator(store, scope, dispatcher)
                coordinator.setDestination(DESTINATION)
                val older = coordinator.beginUserAction()
                assertTrue(coordinator.requestOpen(REQUEST.copy(messageIdHex = "ef".repeat(32))))
                assertFalse(coordinator.isCurrentUserAction(older))
                val latest = coordinator.beginUserAction()
                coordinator.setDestination(DESTINATION.copy(navigationGeneration = 2L))
                assertFalse(coordinator.isCurrentUserAction(latest))
            } finally {
                scope.cancel()
            }
        }

    /** Cancellation fences an already-claimed viewer before asynchronous persistence runs. */
    @Test
    fun cancelImmediatelyFencesClaimedDispatchAndLateRestore() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = AttachmentDownloadIntentStore(preferences)
                val coordinator = coordinator(store, scope, dispatcher)
                coordinator.setDestination(DESTINATION)
                coordinator.requestOpen(REQUEST)
                val canDispatch = coordinator.captureDispatchGuard(openRequest())
                assertTrue(canDispatch())
                assertTrue(store.claimOpenIntent(openRequest()) != null)
                coordinator.cancelOpen(openRequest())
                assertFalse(canDispatch())
                coordinator.restore(openRequest())
                assertFalse(store.hasOpenIntent(openRequest()))
                coordinator.requestOpen(REQUEST)
                assertFalse("a newer tap must not revive the old claim", canDispatch())
                assertTrue(coordinator.captureDispatchGuard(openRequest())())
                testScheduler.advanceUntilIdle()
                assertTrue(store.hasOpenIntent(openRequest()))
            } finally {
                scope.cancel()
            }
        }

    /** An unrelated file tap cannot preserve the cancelled intent or revoke the other file's dispatch. */
    @Test
    fun cancellingOneFileDoesNotFenceAnotherOrLetItsTapRescueCancelledIntent() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val store = AttachmentDownloadIntentStore(preferences)
                val coordinator = coordinator(store, scope, dispatcher)
                coordinator.setDestination(DESTINATION)
                coordinator.requestOpen(REQUEST)
                val other = REQUEST.copy(messageIdHex = "ef".repeat(32))
                coordinator.requestOpen(other)
                val otherOpen = requireNotNull(coordinator.openRequest(other))
                val otherCanDispatch = coordinator.captureDispatchGuard(otherOpen)
                coordinator.cancelOpen(openRequest())
                assertFalse(coordinator.hasIntent(openRequest()))
                assertTrue(otherCanDispatch())
                coordinator.requestOpen(other)
                testScheduler.advanceUntilIdle()
                assertFalse(store.hasOpenIntent(openRequest()))
                assertTrue(store.hasOpenIntent(otherOpen))
            } finally {
                scope.cancel()
            }
        }

    /** Navigation invalidates the captured gesture even if the same destination is later recreated. */
    @Test
    fun leavingAndReturningCannotReviveOldDispatchGuard() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val coordinator = coordinator(AttachmentDownloadIntentStore(preferences), scope, dispatcher)
                coordinator.setDestination(DESTINATION)
                coordinator.requestOpen(REQUEST)
                val canDispatch = coordinator.captureDispatchGuard(openRequest())
                coordinator.setDestination(null)
                coordinator.setDestination(DESTINATION)
                assertFalse(canDispatch())
            } finally {
                scope.cancel()
            }
        }

    /** Explicit transfer cancellation also revokes the app-scoped APK handoff. */
    @Test
    fun cancellingATransferRevokesItsInstallerHandoff() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val store = AttachmentDownloadIntentStore(preferences)
            val installerRequest = AttachmentInstallerHandoffRequest(REQUEST, sourceEpoch = 7uL)
            try {
                assertTrue(store.markInstallerHandoff(installerRequest))
                val coordinator =
                    AttachmentInstallerHandoffCoordinator(
                        intentStore = store,
                        scope = scope,
                        enqueue = { _, _ -> },
                        foregroundEligible = { true },
                        persistence = dispatcher,
                    )

                coordinator.cancel(REQUEST)
                assertFalse(coordinator.hasPending(installerRequest))
                testScheduler.advanceUntilIdle()

                assertFalse(store.hasInstallerHandoff(installerRequest))
            } finally {
                scope.cancel()
            }
        }

    private fun coordinator(
        store: AttachmentDownloadIntentStore,
        scope: CoroutineScope,
        persistence: CoroutineDispatcher,
    ): AttachmentOpenCoordinator =
        AttachmentOpenCoordinator(
            intentStore = store,
            scope = scope,
            enqueue = { _, _ -> },
            visibility = { _, _ -> true },
            persistence = persistence,
        )

    private fun openRequest(): AttachmentOpenRequest =
        AttachmentOpenRequest(
            REQUEST,
            navigationGeneration = DESTINATION.navigationGeneration,
        )

    private companion object {
        val REQUEST =
            AttachmentTransferRequest(
                accountRef = "account-a",
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
        val DESTINATION =
            AttachmentOpenDestination(
                accountRef = REQUEST.accountRef,
                groupIdHex = REQUEST.groupIdHex,
                navigationGeneration = 1L,
            )
    }
}
