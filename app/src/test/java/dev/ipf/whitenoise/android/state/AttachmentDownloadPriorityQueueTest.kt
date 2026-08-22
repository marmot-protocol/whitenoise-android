package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadPriorityQueueTest {
    @Test
    fun tappingLastOfTenAutomaticDownloadsAdmitsItNext() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 3)
                val release = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val jobs =
                    (0 until 10).map { index ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            gate.withPermit("file-$index", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                                started += "file-$index"
                                release.await()
                            }
                        }
                    }

                assertEquals(listOf("file-0", "file-1", "file-2"), started)
                assertTrue(gate.promote("file-9"))
                assertTrue(gate.promote("file-0"))
                release.complete(Unit)
                jobs.awaitAll()
                assertEquals("file-9", started[3])
            }
        }

    @Test
    fun lanesAreFifoDuplicatePromotionIsIdempotentAndAutomaticWorkCannotStarve() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val firstRelease = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val first =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("active", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "active"
                            firstRelease.await()
                        }
                    }
                val automatic =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("automatic", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "automatic"
                        }
                    }
                val interactive =
                    (0 until 4).map { index ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            gate.withPermit("tap-$index", ACCOUNT, AttachmentDownloadPriority.Interactive) {
                                started += "tap-$index"
                            }
                        }
                    }

                assertTrue(gate.promote("tap-0"))
                assertTrue(gate.promote("tap-0"))
                firstRelease.complete(Unit)
                (listOf(first, automatic) + interactive).awaitAll()

                assertEquals(listOf("active", "tap-0", "tap-1", "tap-2", "automatic", "tap-3"), started)
            }
        }

    @Test
    fun stoppingAutomaticQueueLeavesPromotedAndActiveWorkAlone() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val release = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val active =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("active", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "active"
                            release.await()
                        }
                    }
                val cancelled =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("cancelled", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "cancelled"
                        }
                    }
                val promoted =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("promoted", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "promoted"
                        }
                    }
                val otherAccount =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("other-account", "account-b", AttachmentDownloadPriority.Automatic) {
                            started += "other-account"
                        }
                    }
                gate.promote("promoted")

                assertEquals(1, gate.cancelQueuedAutomatic(ACCOUNT))
                release.complete(Unit)
                active.await()
                promoted.await()
                otherAccount.await()
                val cancellation = runCatching { cancelled.await() }.exceptionOrNull()
                assertTrue(cancelled.isCancelled)
                assertTrue(cancellation is AutomaticBacklogStoppedException)
                assertFalse("cancelled" in started)
                assertEquals(listOf("active", "promoted", "other-account"), started)
            }
        }

    @Test
    fun replacementForAStaleDuplicateKeyWaitsWithoutBlockingOtherKeys() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 2)
                val releaseFirst = CompletableDeferred<Unit>()
                val releaseOther = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val first =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("same-key", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "first"
                            releaseFirst.await()
                        }
                    }
                val replacement =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("same-key", ACCOUNT, AttachmentDownloadPriority.Interactive) {
                            started += "replacement"
                        }
                    }
                val other =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("other-key", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "other"
                            releaseOther.await()
                        }
                    }

                assertEquals(listOf("first", "other"), started)
                assertFalse(replacement.isCompleted)

                first.cancel()
                first.join()
                replacement.await()
                assertEquals(listOf("first", "other", "replacement"), started)

                releaseOther.complete(Unit)
                other.await()
            }
        }

    @Test
    fun promotionBeforeAutomaticGateRegistrationIsStickyAndDoesNotDuplicateTheTransfer() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                val releaseActive = CompletableDeferred<Unit>()
                val published = CompletableDeferred<Unit>()
                val allowRegistration = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                var promotedExecutions = 0
                val active =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("active", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "active"
                            releaseActive.await()
                        }
                    }
                val promoted =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        // Models AppState publishing the memoized Deferred before
                        // its async body reaches gate registration.
                        published.complete(Unit)
                        allowRegistration.await()
                        gate.withPermit("late-tap", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            promotedExecutions++
                            started += "late-tap"
                        }
                    }

                published.await()
                assertTrue(gate.promote("late-tap"))
                val earlierAutomatic =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("automatic", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "automatic"
                        }
                    }
                allowRegistration.complete(Unit)
                releaseActive.complete(Unit)

                active.await()
                promoted.await()
                earlierAutomatic.await()
                assertEquals(listOf("active", "late-tap", "automatic"), started)
                assertEquals(1, promotedExecutions)
            }
        }

    @Test
    fun interactiveOnlyAdmissionsDoNotCreateBurstDebtAgainstALaterTap() =
        runBlocking {
            withTimeout(TEST_TIMEOUT) {
                val gate = AttachmentDownloadGate(parallelism = 1)
                repeat(AttachmentDownloadGate.MAX_INTERACTIVE_BURST) { index ->
                    gate.withPermit("warm-tap-$index", ACCOUNT, AttachmentDownloadPriority.Interactive) {}
                }

                val releaseHolder = CompletableDeferred<Unit>()
                val started = mutableListOf<String>()
                val holder =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("holder", ACCOUNT, AttachmentDownloadPriority.Interactive) {
                            started += "holder"
                            releaseHolder.await()
                        }
                    }
                val automatic =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("automatic", ACCOUNT, AttachmentDownloadPriority.Automatic) {
                            started += "automatic"
                        }
                    }
                val tapped =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        gate.withPermit("tap", ACCOUNT, AttachmentDownloadPriority.Interactive) {
                            started += "tap"
                        }
                    }

                releaseHolder.complete(Unit)
                holder.await()
                tapped.await()
                automatic.await()

                assertEquals(listOf("holder", "tap", "automatic"), started)
            }
        }

    private companion object {
        const val ACCOUNT = "account-a"
        const val TEST_TIMEOUT = 5_000L
    }
}
