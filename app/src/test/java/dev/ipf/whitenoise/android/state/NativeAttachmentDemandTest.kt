package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAttachmentDemandTest {
    /** Ordinary taps join scheduled work or override automatic policy without requesting a restart. */
    @Test
    fun activeTransfersNeverChooseRestart() =
        runTest {
            for (state in listOf(
                AttachmentTransferStateFfi.NOT_REQUESTED,
                AttachmentTransferStateFfi.QUEUED,
                AttachmentTransferStateFfi.DOWNLOADING,
                AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT,
                AttachmentTransferStateFfi.DECRYPTING,
                AttachmentTransferStateFfi.VERIFYING_PLAINTEXT,
                AttachmentTransferStateFfi.RETRY_SCHEDULED,
                AttachmentTransferStateFfi.PAUSED,
                AttachmentTransferStateFfi.READY,
                AttachmentTransferStateFfi.POLICY_BLOCKED,
                AttachmentTransferStateFfi.UNAVAILABLE,
            )) {
                var requests = 0
                repeat(3) {
                    assertEquals(
                        state,
                        requestInteractiveAttachmentDemand(
                            state,
                            demandIntent = AttachmentDemandIntent.Join,
                            requestExplicit = {
                                requests += 1
                                state
                            },
                            retryTerminal = { error("An ordinary tap restarted $state") },
                        ),
                    )
                }
                assertEquals(3, requests)
            }
        }

    /** A new deliberate request retains recovery for failed, cancelled and exhausted work. */
    @Test
    fun terminalTransfersKeepDeliberateRetry() =
        runTest {
            for (state in listOf(
                AttachmentTransferStateFfi.FAILED,
                AttachmentTransferStateFfi.CANCELLED,
                AttachmentTransferStateFfi.REMOVED,
                AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
                AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
                AttachmentTransferStateFfi.RETRY_EXHAUSTED,
            )) {
                var retries = 0
                assertEquals(
                    null,
                    requestInteractiveAttachmentDemand(
                        state,
                        demandIntent = AttachmentDemandIntent.Retry,
                        requestExplicit = { error("A terminal retry only joined $state") },
                        retryTerminal = {
                            retries += 1
                            null
                        },
                    ),
                )
                assertEquals(1, retries)
            }
        }

    /** Ordinary Open or Save never grants terminal recovery, even after durable job replacement. */
    @Test
    fun ordinaryJoinsDoNotRestartTerminalTransfers() =
        runTest {
            for (state in listOf(
                AttachmentTransferStateFfi.FAILED,
                AttachmentTransferStateFfi.CANCELLED,
                AttachmentTransferStateFfi.REMOVED,
                AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
                AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
                AttachmentTransferStateFfi.RETRY_EXHAUSTED,
            )) {
                repeat(10) {
                    assertEquals(
                        state,
                        requestInteractiveAttachmentDemand(
                            state,
                            AttachmentDemandIntent.Join,
                            requestExplicit = { error("Terminal join issued new demand for $state") },
                            retryTerminal = { error("Terminal join reset retry budgets for $state") },
                        ),
                    )
                }
            }
        }

    /** Replayed durable work observes an existing native acquisition without issuing fresh demand. */
    @Test
    fun durableRetriesDoNotRearmExistingWork() =
        runTest {
            val existingStates =
                AttachmentTransferStateFfi.entries.filter { it != AttachmentTransferStateFfi.NOT_REQUESTED }
            for (state in existingStates) {
                assertEquals(
                    state,
                    requestInteractiveAttachmentDemand(
                        state,
                        demandIntent = AttachmentDemandIntent.Observe,
                        requestExplicit = { error("Durable retry issued explicit demand for $state") },
                        retryTerminal = { error("Durable retry restarted $state") },
                    ),
                )
            }
            assertEquals(
                AttachmentTransferStateFfi.QUEUED,
                requestInteractiveAttachmentDemand(
                    AttachmentTransferStateFfi.NOT_REQUESTED,
                    demandIntent = AttachmentDemandIntent.Observe,
                    requestExplicit = { AttachmentTransferStateFfi.QUEUED },
                    retryTerminal = { error("First demand used restart") },
                ),
            )
        }

    /** An idempotent request already ready after admission cannot wait for an unnecessary update. */
    @Test
    fun readyRequestClosesObservationImmediately() =
        runTest {
            val feed = Feed(listOf(AttachmentTransferStateFfi.NOT_REQUESTED))
            awaitNativeAttachment(feed) {
                requestInteractiveAttachmentDemand(
                    AttachmentTransferStateFfi.NOT_REQUESTED,
                    demandIntent = AttachmentDemandIntent.Join,
                    requestExplicit = { AttachmentTransferStateFfi.READY },
                    retryTerminal = { error("Ready request was restarted") },
                )
            }
            assertEquals(1, feed.closes)
        }

    /** A deliberate terminal retry consumes fresh progress without treating the initial failure as final. */
    @Test
    fun terminalRetryObservesFreshAcquisition() =
        runTest {
            val feed =
                Feed(
                    listOf(
                        AttachmentTransferStateFfi.FAILED,
                        AttachmentTransferStateFfi.QUEUED,
                        AttachmentTransferStateFfi.READY,
                    ),
                )
            awaitNativeAttachment(feed) {
                requestInteractiveAttachmentDemand(
                    AttachmentTransferStateFfi.FAILED,
                    demandIntent = AttachmentDemandIntent.Retry,
                    requestExplicit = { error("Terminal request did not retry") },
                    retryTerminal = { null },
                )
            }
            assertEquals(1, feed.closes)
        }

    /** Extra reads fail so tests detect waits after a ready admission or completed transfer. */
    private class Feed(
        states: List<AttachmentTransferStateFfi>,
    ) : NativeTransferFeed {
        private val states = ArrayDeque(states)
        var closes = 0

        /** Emits one complete status using the same vocabulary as the native subscription. */
        override suspend fun next() =
            AttachmentTransferSnapshotFfi(
                listOf(AttachmentTransferStatusFfi(null, states.removeFirst(), 0u, 0u, null, null)),
            )

        /** Counts lexical disposal without cancelling durable native work. */
        override fun close() {
            closes += 1
        }
    }
}
