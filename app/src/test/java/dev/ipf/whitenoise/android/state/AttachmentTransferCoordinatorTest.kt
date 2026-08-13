package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AttachmentTransferCoordinatorTest {
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
