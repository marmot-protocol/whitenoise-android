package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AttachmentTransferCoordinatorTest {
    @Test
    fun availabilityPublishedAfterRegistrationBeforeSuspensionIsNotLost() =
        runBlocking {
            val signals = AttachmentAvailabilitySignals()
            val registeredSignal = signals.register("file")

            signals.onRefresh("file", available = false)
            signals.onRefresh("file", available = true)

            withTimeout(1_000) { registeredSignal.await() }
        }

    @Test
    fun availabilitySignalIsFreshAndAttachmentSpecific() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val coordinator = AttachmentTransferCoordinator(scope)
                coordinator.acquireState("file", initiallyAvailable = true)
                coordinator.refresh("file") { true }
                val availability =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        coordinator.awaitNextAvailability("file")
                    }

                assertFalse(availability.isCompleted)
                coordinator.acquireState("other", initiallyAvailable = false)
                coordinator.refresh("other") { true }
                assertFalse(availability.isCompleted)

                coordinator.refresh("file") { true }
                assertFalse(availability.isCompleted)
                coordinator.refresh("file") { false }
                coordinator.refresh("file") { true }
                availability.await()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun autoDownloadAndTapShareOneTransferOwner() =
        runBlocking {
            withTimeout(5_000) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                try {
                    val coordinator = AttachmentTransferCoordinator(scope)
                    val release = CompletableDeferred<Unit>()
                    val calls = AtomicInteger(0)
                    val load: suspend () -> ByteArray = {
                        calls.incrementAndGet()
                        release.await()
                        byteArrayOf(1)
                    }

                    val automatic = coordinator.request("file", load) { true }
                    val tapped = coordinator.request("file", load) { true }

                    assertSame(automatic, tapped)
                    assertEquals(1, calls.get())
                    assertEquals(AttachmentTransferState.Downloading, coordinator.state("file", false).value)
                    release.complete(Unit)
                    assertEquals(byteArrayOf(1).toList(), tapped.await().toList())
                    assertEquals(AttachmentTransferState.Available, coordinator.state("file", false).value)
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun cacheRefreshCannotCancelOrDemoteAnActiveDownload() =
        runBlocking {
            withTimeout(5_000) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                try {
                    val coordinator = AttachmentTransferCoordinator(scope)
                    val release = CompletableDeferred<Unit>()
                    val transfer =
                        coordinator.request("file", load = {
                            release.await()
                            byteArrayOf(2)
                        }) { true }

                    coordinator.refresh("file") { true }
                    assertEquals(AttachmentTransferState.Downloading, coordinator.state("file", false).value)
                    release.complete(Unit)
                    transfer.await()
                    assertEquals(AttachmentTransferState.Available, coordinator.state("file", false).value)
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun staleCacheMissCannotDemoteACompletedDownload() =
        runBlocking {
            withTimeout(5_000) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                try {
                    val coordinator = AttachmentTransferCoordinator(scope)
                    val probeStarted = CompletableDeferred<Unit>()
                    val releaseProbe = CompletableDeferred<Unit>()
                    val refresh =
                        async {
                            coordinator.refresh("file") {
                                probeStarted.complete(Unit)
                                releaseProbe.await()
                                false
                            }
                        }

                    probeStarted.await()
                    coordinator.request("file", load = { byteArrayOf(5) }) { true }.await()
                    assertEquals(AttachmentTransferState.Available, coordinator.state("file", false).value)

                    releaseProbe.complete(Unit)
                    refresh.await()
                    assertEquals(AttachmentTransferState.Available, coordinator.state("file", false).value)
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun failedInitialCacheProbeFallsBackToRemoteWithoutDemotingAvailableState() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val coordinator = AttachmentTransferCoordinator(scope)
                coordinator.state("new", false)
                coordinator.refresh("new") { error("cache unavailable") }
                assertEquals(AttachmentTransferState.Remote, coordinator.state("new", false).value)

                coordinator.request("cached", load = { byteArrayOf(6) }) { true }.await()
                coordinator.refresh("cached") { error("cache unavailable") }
                assertEquals(AttachmentTransferState.Available, coordinator.state("cached", false).value)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun releasedObserverStateIsRetiredWhenNoTransferIsActive() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val coordinator = AttachmentTransferCoordinator(scope)
            coordinator.acquireState("file", false)
            runBlocking { coordinator.refresh("file") { false } }
            assertEquals(AttachmentTransferState.Remote, coordinator.state("file", false).value)

            coordinator.releaseState("file")

            assertEquals(AttachmentTransferState.Resolving, coordinator.acquireState("file", false).value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun releasedObserverStateSurvivesUntilItsActiveTransferCompletes() =
        runBlocking {
            withTimeout(5_000) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
                try {
                    val coordinator = AttachmentTransferCoordinator(scope)
                    coordinator.acquireState("file", false)
                    val releaseTransfer = CompletableDeferred<Unit>()
                    val transfer =
                        coordinator.request("file", load = {
                            releaseTransfer.await()
                            byteArrayOf(7)
                        }) { true }

                    coordinator.releaseState("file")
                    assertEquals(AttachmentTransferState.Downloading, coordinator.state("file", false).value)

                    releaseTransfer.complete(Unit)
                    transfer.await()
                    assertEquals(AttachmentTransferState.Resolving, coordinator.state("file", false).value)
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun emptyMediaResultFailsTheMdkContract() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val coordinator = AttachmentTransferCoordinator(scope)

                val failure =
                    runCatching {
                        coordinator.request("file", load = { byteArrayOf() }) { false }.await()
                    }.exceptionOrNull()

                assertTrue(failure is IllegalStateException)
                assertEquals(AttachmentTransferState.Failed, coordinator.state("file", false).value)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun completedButUnretainedDownloadDoesNotPresentAsCached() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val coordinator = AttachmentTransferCoordinator(scope)
                coordinator.request("file", load = { byteArrayOf(3) }) { false }.await()

                assertEquals(AttachmentTransferState.NotRetained, coordinator.state("file", false).value)
                coordinator.refresh("file") { false }
                assertEquals(AttachmentTransferState.NotRetained, coordinator.state("file", false).value)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun failedDownloadCanBeRetriedManually() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val coordinator = AttachmentTransferCoordinator(scope)
                runCatching {
                    coordinator.request("file", load = { error("permanent") }) { false }.await()
                }
                assertEquals(AttachmentTransferState.Failed, coordinator.state("file", false).value)

                val bytes = coordinator.request("file", load = { byteArrayOf(4) }) { true }.await()
                assertEquals(byteArrayOf(4).toList(), bytes.toList())
                assertEquals(AttachmentTransferState.Available, coordinator.state("file", false).value)
            } finally {
                scope.cancel()
            }
        }
}
