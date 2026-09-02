package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentInstallerHandoffCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences by lazy {
        context.getSharedPreferences("attachment-installer-coordinator-test", Context.MODE_PRIVATE)
    }

    /** Clears durable and volatile permission ownership between examples. */
    @Before
    fun reset() {
        AttachmentDownloadIntentStore(preferences).abandonInstallerPermissionHandoff(REQUEST)
        preferences.edit().clear().commit()
    }

    /** Persistence leaves the UI thread, while enqueue still follows its durable commit. */
    @Test
    fun requestPersistsBeforeEnqueueAndExposesPendingState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val store = AttachmentDownloadIntentStore(preferences)
            var enqueueCount = 0
            try {
                val coordinator =
                    AttachmentInstallerHandoffCoordinator(
                        intentStore = store,
                        scope = scope,
                        enqueue = { request, priority ->
                            assertEquals(REQUEST, store.pendingInstallerHandoff())
                            assertEquals(AttachmentDownloadPriority.Interactive, priority)
                            assertEquals(TRANSFER, request)
                            enqueueCount++
                        },
                        foregroundEligible = { true },
                        persistence = dispatcher,
                    )

                assertTrue(coordinator.request(REQUEST))

                assertEquals(0, enqueueCount)
                assertNull(store.pendingInstallerHandoff())
                assertTrue(coordinator.hasPending(REQUEST))

                testScheduler.runCurrent()

                assertEquals(1, enqueueCount)
                assertTrue(coordinator.hasPending(REQUEST))
            } finally {
                scope.cancel()
            }
        }

    /** A same-identity re-tap survives cancellation that was already queued. */
    @Test
    fun freshTapSupersedesAsynchronousCancellation() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val coordinator = coordinator(scope, dispatcher)
            try {
                assertTrue(coordinator.request(REQUEST))
                testScheduler.runCurrent()
                coordinator.cancel(TRANSFER)
                assertTrue(coordinator.request(REQUEST))

                testScheduler.advanceUntilIdle()

                assertTrue(coordinator.hasPending(REQUEST))
                assertEquals(AttachmentOpenIntentClaim.Fresh, coordinator.claim(REQUEST))
                assertNull(coordinator.claim(REQUEST))
            } finally {
                scope.cancel()
            }
        }

    /** Foreground eligibility is evaluated at the final dispatch boundary. */
    @Test
    fun foregroundGateTracksTheLatestAppLifecycleState() {
        val scope = CoroutineScope(SupervisorJob())
        var foreground = false
        try {
            val coordinator =
                AttachmentInstallerHandoffCoordinator(
                    intentStore = AttachmentDownloadIntentStore(preferences),
                    scope = scope,
                    enqueue = { _, _ -> },
                    foregroundEligible = { foreground },
                )

            assertFalse(coordinator.canDispatch(REQUEST))
            foreground = true
            assertTrue(coordinator.canDispatch(REQUEST))
        } finally {
            scope.cancel()
        }
    }

    /** Cancel fences a near-dispatch owner before the disk cleanup settles. */
    @Test
    fun cancellationImmediatelyRevokesPendingAndDispatchEligibility() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val coordinator = coordinator(scope, dispatcher)
            try {
                assertTrue(coordinator.request(REQUEST))
                testScheduler.runCurrent()

                coordinator.cancel(TRANSFER)

                assertFalse(coordinator.hasPending(REQUEST))
                assertNull(coordinator.pending())
                assertFalse(coordinator.canDispatch(REQUEST))
                testScheduler.advanceUntilIdle()
                assertNull(AttachmentDownloadIntentStore(preferences).pendingInstallerHandoff())
            } finally {
                scope.cancel()
            }
        }

    /** A replacement process restarts persisted work once without duplicate enqueue. */
    @Test
    fun restoredHandoffEnsuresInteractiveTransferExactlyOncePerProcess() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val store = AttachmentDownloadIntentStore(preferences)
            assertTrue(store.markInstallerHandoff(REQUEST))
            var enqueueCount = 0
            val coordinator =
                AttachmentInstallerHandoffCoordinator(
                    intentStore = store,
                    scope = scope,
                    enqueue = { request, priority ->
                        assertEquals(TRANSFER, request)
                        assertEquals(AttachmentDownloadPriority.Interactive, priority)
                        enqueueCount++
                    },
                    foregroundEligible = { true },
                    persistence = dispatcher,
                )
            try {
                coordinator.ensureTransfer(REQUEST)
                coordinator.ensureTransfer(REQUEST)

                assertEquals(1, enqueueCount)
            } finally {
                scope.cancel()
            }
        }

    /** Restoring a claimed launch reuses its completed transfer in this process. */
    @Test
    fun restoredClaimDoesNotEnqueueTheTransferAgain() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val store = AttachmentDownloadIntentStore(preferences)
            assertTrue(store.markInstallerHandoff(REQUEST))
            var enqueueCount = 0
            val coordinator =
                AttachmentInstallerHandoffCoordinator(
                    intentStore = store,
                    scope = scope,
                    enqueue = { _, _ -> enqueueCount++ },
                    foregroundEligible = { true },
                    persistence = dispatcher,
                )
            try {
                coordinator.ensureTransfer(REQUEST)
                assertEquals(AttachmentOpenIntentClaim.Fresh, coordinator.claim(REQUEST))
                coordinator.restore(REQUEST)
                coordinator.ensureTransfer(REQUEST)

                assertEquals(1, enqueueCount)
            } finally {
                scope.cancel()
            }
        }

    /** Creates a coordinator whose persistence is controlled by the test scheduler. */
    private fun coordinator(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ): AttachmentInstallerHandoffCoordinator =
        AttachmentInstallerHandoffCoordinator(
            intentStore = AttachmentDownloadIntentStore(preferences),
            scope = scope,
            enqueue = { _, _ -> },
            foregroundEligible = { true },
            persistence = dispatcher,
        )

    private companion object {
        val TRANSFER =
            AttachmentTransferRequest(
                accountRef = "account-a",
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
        val REQUEST = AttachmentInstallerHandoffRequest(TRANSFER, sourceEpoch = 7uL)
    }
}
