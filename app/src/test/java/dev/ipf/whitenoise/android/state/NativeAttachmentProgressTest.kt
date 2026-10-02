package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeAttachmentProgressTest {
    /** A body cannot appear to move backwards; a new generation or reference explicitly resets its bytes. */
    @Test
    fun bodyProgressResetsOnlyAcrossBodyIdentity() {
        val reducer = NativeAttachmentProgressReducer()
        assertEquals(80uL, reducer.update(status(received = 80u)).received)
        assertEquals(80uL, reducer.update(status(received = 20u)).received)
        assertEquals(5uL, reducer.update(status(attempt = 2u, received = 5u)).received)
        assertEquals(0uL, reducer.update(status(attempt = 2u, reference = "replacement")).received)
    }

    /** Unknown, zero or inconsistent lengths never fabricate a percentage. */
    @Test
    fun invalidOrUnknownTotalRemainsIndeterminate() {
        val reducer = NativeAttachmentProgressReducer()
        assertNull(reducer.update(status(total = null, received = 80u)).fraction)
        assertNull(reducer.update(status(total = 0u)).fraction)
        assertNull(reducer.update(status(total = 10u, received = 80u)).fraction)
        assertEquals(0.8f, reducer.update(status(received = 80u)).fraction!!, 0.001f)
        val decrypting = status(phase = AttachmentTransferStateFfi.DECRYPTING)
        assertNull(reducer.update(decrypting).fraction)
    }

    /** EOF cannot leave a dead observer's Downloading phase visible after its subscription closes. */
    @Test
    fun flowClearsInitialAndCompletedObservation() =
        runTest {
            val feed = Feed(listOf(status(received = 50u)))
            val updates = nativeAttachmentProgressFlow { feed }.toList()
            assertEquals(listOf(null, AttachmentTransferStateFfi.DOWNLOADING, null), updates.map { it?.phase })
            assertEquals(1, feed.closes)
            assertEquals(listOf(null), nativeAttachmentProgressFlow { null }.toList())
        }

    /** Observation failure clears progress without inventing a transfer failure or cancelling its acquisition. */
    @Test
    fun failedObservationClearsProgressAndClosesHandle() =
        runTest {
            var reads = 0
            var closed = 0
            val feed =
                object : NativeTransferFeed {
                    /** Delivers one live phase, then a real observer failure. */
                    override suspend fun next(): AttachmentTransferSnapshotFfi {
                        if (reads++ > 0) throw java.io.IOException("observer failed")
                        return AttachmentTransferSnapshotFfi(listOf(status(received = 50u)))
                    }

                    /** Disposal owns this observer only. */
                    override fun close() {
                        closed++
                    }
                }
            val updates = nativeAttachmentProgressFlow { feed }.toList()
            assertEquals(listOf(null, AttachmentTransferStateFfi.DOWNLOADING, null), updates.map { it?.phase })
            assertEquals(1, closed)
        }

    /** Every native phase, including terminal states and deadlines, is emitted even within one redraw window. */
    @Test
    fun phaseChangesAndRetryDeadlineAreNotThrottled() =
        runTest {
            val statuses =
                AttachmentTransferStateFfi.entries.map { status(phase = it) } +
                    listOf(
                        status(phase = AttachmentTransferStateFfi.RETRY_SCHEDULED, retryAt = 1u),
                        status(phase = AttachmentTransferStateFfi.RETRY_SCHEDULED, retryAt = 2u),
                    )
            val displayed = mutableListOf<NativeAttachmentProgress>()
            observeNativeAttachmentProgress(Feed(statuses), nowNanos = { 0 }) { displayed += it }
            assertEquals(statuses.map { it.state }, displayed.map { it.phase })
            assertEquals(2uL, displayed.last().retryAt)
        }

    /** Byte redraws are bounded but the reducer still remembers a suppressed high-water mark. */
    @Test
    fun byteThrottleRetainsSuppressedProgressForNextPhase() =
        runTest {
            val displayed = mutableListOf<NativeAttachmentProgress>()
            val statuses =
                listOf(
                    status(received = 1u),
                    status(received = 80u),
                    status(received = 20u),
                    status(phase = AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT, received = 40u),
                )
            observeNativeAttachmentProgress(Feed(statuses), nowNanos = { 0 }) { displayed += it }
            assertEquals(listOf(1uL, 80uL), displayed.map { it.received })
            assertEquals(AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT, displayed.last().phase)
        }

    /** Cancellation during subscription creation releases its observer without issuing demand or control. */
    @Test
    fun cancellationDuringSubscriptionCreationReleasesObserver() =
        runTest {
            val opened = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val feed = Feed(emptyList())
            val observer =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    observeNativeAttachmentProgress(open = {
                        opened.complete(Unit)
                        release.await()
                        feed
                    }) {}
                }
            opened.await()
            observer.cancel()
            release.complete(Unit)
            observer.cancelAndJoin()
            assertEquals(1, feed.closes)
        }

    /** A renderer failure propagates and still disposes the native subscription. */
    @Test
    fun observerFailureClosesSubscription() =
        runTest {
            val feed = Feed(listOf(status()))
            runCatching { observeNativeAttachmentProgress(open = { feed }) { error("renderer stopped") } }
            assertEquals(1, feed.closes)
        }

    /** Native Ready alone cannot prove the host has verified usable plaintext. */
    @Test
    fun presentationKeepsLocalAvailabilitySeparateFromNativeReady() {
        val ready = NativeAttachmentProgressReducer().update(status(phase = AttachmentTransferStateFfi.READY))
        assertEquals(
            AttachmentTransferState.Resolving,
            attachmentFilePresentationState(AttachmentTransferState.Resolving, ready, AttachmentCancellationState.None),
        )
        val failed = NativeAttachmentProgressReducer().update(status(phase = AttachmentTransferStateFfi.FAILED))
        assertEquals(
            AttachmentTransferState.Available,
            attachmentFilePresentationState(
                AttachmentTransferState.Available,
                failed,
                AttachmentCancellationState.None,
            ),
        )
        assertEquals(
            AttachmentTransferState.Failed,
            attachmentFilePresentationState(AttachmentTransferState.Remote, failed, AttachmentCancellationState.None),
        )
    }

    /** Every native phase preserves verified availability and cannot bypass an unacknowledged cancellation. */
    @Test
    fun everyNativePhaseHonorsHostAvailabilityAndCancelOwnership() {
        AttachmentTransferStateFfi.entries.forEach { phase ->
            val progress = NativeAttachmentProgressReducer().update(status(phase = phase, received = 50u))
            assertEquals(
                AttachmentTransferState.Available,
                attachmentFilePresentationState(
                    AttachmentTransferState.Available,
                    progress,
                    AttachmentCancellationState.None,
                ),
            )
            assertEquals(
                AttachmentTransferState.Downloading,
                attachmentFilePresentationState(
                    AttachmentTransferState.Downloading,
                    progress,
                    AttachmentCancellationState.Pending,
                ),
            )
            if (phase == AttachmentTransferStateFfi.DOWNLOADING) {
                assertEquals(0.5f, progress.fraction)
            } else {
                assertNull(progress.fraction)
            }
        }
    }

    /** Builds complete native replacements without inventing storage retry counters. */
    private fun status(
        phase: AttachmentTransferStateFfi = AttachmentTransferStateFfi.DOWNLOADING,
        attempt: ULong = 1u,
        received: ULong = 0u,
        total: ULong? = 100u,
        retryAt: ULong? = null,
        reference: String? = "reference",
    ) = AttachmentTransferStatusFfi(reference, phase, attempt, received, total, retryAt)

    private class Feed(
        statuses: List<AttachmentTransferStatusFfi>,
    ) : NativeTransferFeed {
        private val statuses = ArrayDeque(statuses)
        var closes = 0

        /** Returns null at EOF so no polling or synthetic terminal update is required. */
        override suspend fun next() = statuses.removeFirstOrNull()?.let { AttachmentTransferSnapshotFfi(listOf(it)) }

        /** Observer disposal releases only this observer. */
        override fun close() {
            closes++
        }
    }
}
