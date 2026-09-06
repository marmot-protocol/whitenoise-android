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
    private val installerHandoffRecords = VolatileAttachmentInstallerHandoffRecordStore()

    /** Clears durable and volatile permission ownership between examples. */
    @Before
    fun reset() {
        intentStore().abandonInstallerPermissionHandoff(REQUEST)
        installerHandoffRecords.replaceAllDurably(emptyMap())
        preferences.edit().clear().commit()
    }

    /** Persistence leaves the UI thread, while enqueue still follows its durable commit. */
    @Test
    fun requestPersistsBeforeEnqueueAndExposesPendingState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val store = intentStore()
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

                coordinator.request(REQUEST) { error("persistence should succeed") }

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

    /** Hydrates once off the caller path, then serves pending identity reads from memory. */
    @Test
    fun pendingQueriesUseTheHydratedCacheWithoutRepeatedRecordReads() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val records = TrackingInstallerHandoffRecordStore()
            val store = AttachmentDownloadIntentStore(preferences, records)
            assertTrue(store.markInstallerHandoff(REQUEST))
            records.resetReadCount()
            try {
                val coordinator =
                    AttachmentInstallerHandoffCoordinator(
                        intentStore = store,
                        scope = scope,
                        enqueue = { _, _ -> },
                        foregroundEligible = { true },
                        persistence = dispatcher,
                    )

                assertNull(coordinator.pending())
                assertFalse(coordinator.hasPending(REQUEST))
                assertEquals(0, records.readCount)

                testScheduler.runCurrent()

                assertEquals(REQUEST, coordinator.pending())
                assertTrue(coordinator.hasPending(REQUEST))
                assertEquals(1, records.readCount)
                assertEquals(REQUEST, coordinator.pending())
                assertTrue(coordinator.hasPending(REQUEST))
                assertEquals(1, records.readCount)
            } finally {
                scope.cancel()
            }
        }

    /** Reports a durable-write failure and never admits the associated transfer. */
    @Test
    fun failedPersistenceReportsTheOpenFailureAndClearsPendingState() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val records = TrackingInstallerHandoffRecordStore().apply { writesSucceed = false }
            var enqueueCount = 0
            var failureCount = 0
            try {
                val coordinator =
                    AttachmentInstallerHandoffCoordinator(
                        intentStore = AttachmentDownloadIntentStore(preferences, records),
                        scope = scope,
                        enqueue = { _, _ -> enqueueCount++ },
                        foregroundEligible = { true },
                        persistence = dispatcher,
                    )

                coordinator.request(REQUEST) { failureCount++ }
                assertTrue(coordinator.hasPending(REQUEST))

                testScheduler.runCurrent()

                assertEquals(1, failureCount)
                assertEquals(0, enqueueCount)
                assertFalse(coordinator.hasPending(REQUEST))
                assertNull(coordinator.pending())
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
                coordinator.request(REQUEST) { error("persistence should succeed") }
                testScheduler.runCurrent()
                coordinator.cancel(TRANSFER)
                coordinator.request(REQUEST) { error("persistence should succeed") }

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
                    intentStore = intentStore(),
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
                coordinator.request(REQUEST) { error("persistence should succeed") }
                testScheduler.runCurrent()

                coordinator.cancel(TRANSFER)

                assertFalse(coordinator.hasPending(REQUEST))
                assertNull(coordinator.pending())
                assertFalse(coordinator.canDispatch(REQUEST))
                testScheduler.advanceUntilIdle()
                assertNull(intentStore().pendingInstallerHandoff())
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
            val store = intentStore()
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
                testScheduler.runCurrent()
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
            val store = intentStore()
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
                testScheduler.runCurrent()
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
            intentStore = intentStore(),
            scope = scope,
            enqueue = { _, _ -> },
            foregroundEligible = { true },
            persistence = dispatcher,
        )

    /** Creates a process owner over the test's shared durable installer record. */
    private fun intentStore(): AttachmentDownloadIntentStore =
        AttachmentDownloadIntentStore(
            preferences,
            installerHandoffRecords,
        )

    /** Record-store probe used to distinguish hydration reads from cached UI reads. */
    private class TrackingInstallerHandoffRecordStore : AttachmentInstallerHandoffRecordStore {
        private var values: Map<String, String> = emptyMap()

        var readCount: Int = 0
            private set

        var writesSucceed: Boolean = true

        /** Returns a defensive record snapshot while counting persistence access. */
        override fun readAll(): Map<String, String> =
            synchronized(this) {
                readCount++
                values.toMap()
            }

        /** Replaces the record only while durable writes are configured to succeed. */
        override fun replaceAllDurably(values: Map<String, String>): Boolean =
            synchronized(this) {
                if (!writesSucceed) return@synchronized false
                this.values = values.toMap()
                true
            }

        /** Resets only observation state, preserving the seeded durable record. */
        fun resetReadCount() {
            readCount = 0
        }
    }

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
