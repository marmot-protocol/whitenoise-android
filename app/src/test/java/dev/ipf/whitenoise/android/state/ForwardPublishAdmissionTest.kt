package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForwardPublishAdmissionTest {
    /**
     * Stands in for MDK's token ledger. Admitting a token it already holds returns the same identity without a second
     * message, a new admission is Queued until the engine takes it, and the test moves tokens between states the way
     * the account worker does, including an admission that lands only after its caller stopped waiting.
     */
    private class FakeTokenLedger {
        val statuses = mutableMapOf<String, LocalSendStatusFfi>()
        val messages = mutableListOf<String>()
        var admissions = 0

        /** Reads a token's local status. */
        fun probe(token: String): LocalSendStatusFfi? = statuses[token]

        /** Admits [token] once into Queued; a repeated admission returns the existing identity and adds nothing. */
        fun admit(token: String): LocalSendAcceptanceFfi {
            admissions += 1
            if (statuses[token] == null) {
                messages += token
                statuses[token] = LocalSendStatusFfi.Queued
            }
            return LocalSendAcceptanceFfi(clientToken = token, messageIdHex = "message-for-$token")
        }
    }

    /** Publishes message 0 under [TOKEN] against [ledger], optionally stalling the admission call itself. */
    private suspend fun publish(
        ledger: FakeTokenLedger,
        stallAdmission: Boolean = false,
        admitFailure: Exception? = null,
    ) = admitForwardPublish(
        messageIndex = 0,
        token = TOKEN,
        probe = { ledger.probe(TOKEN) },
        admit = {
            if (stallAdmission) awaitCancellation()
            admitFailure?.let { throw it }
            ledger.admit(TOKEN)
        },
        deadlineMillis = DEADLINE_MILLIS,
        pollMillis = POLL_MILLIS,
    )

    /** A queued admission is not sent yet: the call returns only once the engine owns it. */
    @Test
    fun aQueuedAdmissionCompletesOnlyWhenTheEngineOwnsIt() =
        runTest {
            val ledger = FakeTokenLedger()
            launch {
                delay(HANDOFF_MILLIS)
                ledger.statuses[TOKEN] = LocalSendStatusFfi.EngineOwned
            }

            publish(ledger)

            assertTrue("returned before the engine owned it", currentTime >= HANDOFF_MILLIS)
            assertEquals(listOf(TOKEN), ledger.messages)
        }

    /** A message still queued at the deadline fails as a publish timeout instead of counting as sent. */
    @Test
    fun aMessageStillQueuedAtTheDeadlineTimesOut() =
        runTest {
            val ledger = FakeTokenLedger()

            val failure = runCatching { publish(ledger) }.exceptionOrNull()

            assertTrue("expected a publish timeout, got $failure", failure is ForwardPublishTimeoutException)
            assertEquals(listOf(TOKEN), ledger.messages)
        }

    /** A message the account worker rejects after queuing it surfaces as a rejection, never as sent. */
    @Test
    fun aMessageRejectedWhileQueuedIsReported() =
        runTest {
            val ledger = FakeTokenLedger()
            launch {
                delay(HANDOFF_MILLIS)
                ledger.statuses[TOKEN] = LocalSendStatusFfi.Rejected
            }

            val failure = runCatching { publish(ledger) }.exceptionOrNull()

            assertTrue("expected rejected, got $failure", failure is ForwardPublishRejectedException)
        }

    /**
     * An admission call stalls past the deadline and lands afterwards, as MDK finishes it in the background. The retry
     * finds the token owned and never admits it again, so the message exists once.
     */
    @Test
    fun aLateAdmissionIsCountedByTheRetryWithoutAdmittingAgain() =
        runTest {
            val ledger = FakeTokenLedger()
            val stalled = runCatching { publish(ledger, stallAdmission = true) }.exceptionOrNull()
            assertTrue("expected a publish timeout, got $stalled", stalled is ForwardPublishTimeoutException)

            ledger.statuses[TOKEN] = LocalSendStatusFfi.EngineOwned
            ledger.messages += TOKEN
            publish(ledger)

            assertEquals(0, ledger.admissions)
            assertEquals(listOf(TOKEN), ledger.messages)
        }

    /** An owned token, queued or handed off, is waited on or counted and never admitted again. */
    @Test
    fun anOwnedTokenIsNeverAdmittedAgain() =
        runTest {
            val completed =
                LocalSendStatusFfi.Completed(
                    SendSummaryFfi(
                        1u,
                        listOf("message"),
                        SendAcceptDispositionFfi.ACCEPTED_PENDING,
                        SendMaintenanceDispositionFfi.READY,
                    ),
                )
            listOf(LocalSendStatusFfi.EngineOwned, completed).forEach { status ->
                val ledger = FakeTokenLedger().apply { statuses[TOKEN] = status }
                publish(ledger)
                assertEquals("status $status must not be admitted again", 0, ledger.admissions)
            }
            val queued = FakeTokenLedger().apply { statuses[TOKEN] = LocalSendStatusFfi.Queued }
            launch {
                delay(HANDOFF_MILLIS)
                queued.statuses[TOKEN] = LocalSendStatusFfi.EngineOwned
            }
            publish(queued)
            assertEquals(0, queued.admissions)
        }

    /** A token MDK already rejected is reported for replacement and never admitted again. */
    @Test
    fun aRejectedTokenIsReportedAndNotAdmitted() =
        runTest {
            val ledger = FakeTokenLedger().apply { statuses[TOKEN] = LocalSendStatusFfi.Rejected }

            val failure = runCatching { publish(ledger) }.exceptionOrNull()

            assertTrue("expected rejected, got $failure", failure is ForwardPublishRejectedException)
            assertEquals(0, ledger.admissions)
        }

    /** A failed admission that stored nothing is not committed, so the same token may be admitted again later. */
    @Test
    fun aFailedAdmissionWithoutOwnershipIsNotCommitted() =
        runTest {
            val ledger = FakeTokenLedger()

            val failure =
                runCatching { publish(ledger, admitFailure = IllegalStateException("engine busy")) }
                    .exceptionOrNull()

            assertTrue("expected not committed, got $failure", failure is ForwardPublishNotCommittedException)
        }

    /** An admission that failed after it was stored continues to the handoff wait instead of failing. */
    @Test
    fun aFailedAdmissionThatWasStoredStillWaitsForTheEngine() =
        runTest {
            val ledger = FakeTokenLedger()
            launch {
                delay(HANDOFF_MILLIS)
                ledger.statuses[TOKEN] = LocalSendStatusFfi.EngineOwned
            }

            admitForwardPublish(
                messageIndex = 0,
                token = TOKEN,
                probe = { ledger.probe(TOKEN) },
                admit = {
                    ledger.admit(TOKEN)
                    throw IllegalStateException("response lost")
                },
                deadlineMillis = DEADLINE_MILLIS,
                pollMillis = POLL_MILLIS,
            )

            assertEquals(listOf(TOKEN), ledger.messages)
        }

    /** A status read that never returns is still bounded by the deadline. */
    @Test
    fun aStuckStatusReadIsBoundedByTheDeadline() =
        runTest {
            val failure =
                runCatching {
                    admitForwardPublish(
                        messageIndex = 0,
                        token = TOKEN,
                        probe = { CompletableDeferred<LocalSendStatusFfi?>().await() },
                        admit = { error("must not admit while the status read is stuck") },
                        deadlineMillis = DEADLINE_MILLIS,
                        pollMillis = POLL_MILLIS,
                    )
                }.exceptionOrNull()

            assertTrue("expected a publish timeout, got $failure", failure is ForwardPublishTimeoutException)
            assertEquals(DEADLINE_MILLIS, currentTime)
        }

    /** An acceptance for a different token fails loudly instead of being counted as sent. */
    @Test
    fun anAcceptanceForAnotherTokenFailsLoudly() =
        runTest {
            val failure =
                runCatching {
                    admitForwardPublish(
                        messageIndex = 0,
                        token = TOKEN,
                        probe = { null },
                        admit = { LocalSendAcceptanceFfi(clientToken = "other-token", messageIdHex = "message") },
                        deadlineMillis = DEADLINE_MILLIS,
                        pollMillis = POLL_MILLIS,
                    )
                }.exceptionOrNull()

            assertTrue("expected a token mismatch, got $failure", failure is IllegalStateException)
            assertTrue(failure !is ForwardPublishNotCommittedException && failure !is ForwardPublishTimeoutException)
        }

    /** A synchronous stale-epoch refusal is reported as rejected, so the session uploads that message again. */
    @Test
    fun aStaleEpochRefusalIsReportedAsRejected() =
        runTest {
            val ledger = FakeTokenLedger()
            val stale = MarmotKitException.MediaReferenceStaleEpoch(sourceEpoch = 3uL, currentEpoch = 4uL)

            val failure = runCatching { publish(ledger, admitFailure = stale) }.exceptionOrNull()

            assertTrue("expected rejected, got $failure", failure is ForwardPublishRejectedException)
            assertTrue(ledger.messages.isEmpty())
        }

    private companion object {
        const val TOKEN = "opaque-token"
        const val DEADLINE_MILLIS = 5_000L
        const val POLL_MILLIS = 100L
        const val HANDOFF_MILLIS = 1_000L
    }
}
