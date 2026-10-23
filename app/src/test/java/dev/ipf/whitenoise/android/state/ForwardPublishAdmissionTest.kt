package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForwardPublishAdmissionTest {
    /**
     * Stands in for MDK's token ledger: admitting a token it already owns returns the same identity without a second
     * message, which is the property a forward retry relies on.
     */
    private class FakeTokenLedger {
        val statuses = mutableMapOf<String, LocalSendStatusFfi>()
        val messages = mutableListOf<String>()

        /** Reads a token's local status. */
        fun probe(token: String): LocalSendStatusFfi? = statuses[token]

        /** Admits [token] once; a repeated admission returns the existing identity and adds nothing. */
        fun admit(token: String): LocalSendAcceptanceFfi {
            if (statuses[token] == null) {
                messages += token
                statuses[token] = LocalSendStatusFfi.Queued
            }
            return LocalSendAcceptanceFfi(clientToken = token, messageIdHex = "message-for-$token")
        }
    }

    /** Runs one admission of message 0 under [token] against [ledger], with an optional gate before admitting. */
    private suspend fun admitOnce(
        ledger: FakeTokenLedger,
        token: String = TOKEN,
        gate: CompletableDeferred<Unit>? = null,
        admitFailure: Exception? = null,
    ) = admitForwardPublish(
        messageIndex = 0,
        token = token,
        probe = { ledger.probe(token) },
        admit = {
            gate?.await()
            admitFailure?.let { throw it }
            ledger.admit(token)
        },
        admissionTimeoutMillis = TIMEOUT_MILLIS,
    )

    /** An admission still waiting at the deadline fails as a publish timeout instead of spinning for minutes. */
    @Test
    fun aStalledAdmissionTimesOut() =
        runTest {
            val ledger = FakeTokenLedger()

            val failure = runCatching { admitOnce(ledger, gate = CompletableDeferred()) }.exceptionOrNull()

            assertTrue("expected a publish timeout, got $failure", failure is ForwardPublishTimeoutException)
            assertTrue(ledger.messages.isEmpty())
        }

    /** A retry finds the earlier admission already owned in any state and never admits it again. */
    @Test
    fun aRetryOfAnOwnedTokenNeverAdmitsAgain() =
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
            listOf(LocalSendStatusFfi.Queued, LocalSendStatusFfi.EngineOwned, completed).forEach { status ->
                val ledger = FakeTokenLedger().apply { statuses[TOKEN] = status }
                var admissions = 0

                admitForwardPublish(
                    messageIndex = 0,
                    token = TOKEN,
                    probe = { ledger.probe(TOKEN) },
                    admit = {
                        admissions += 1
                        ledger.admit(TOKEN)
                    },
                )

                assertEquals("status $status must not be admitted again", 0, admissions)
            }
        }

    /** After a timeout with nothing owned, the retry admits under the same token and the message exists once. */
    @Test
    fun aRetryWithNoOwnershipAdmitsTheSameTokenOnce() =
        runTest {
            val ledger = FakeTokenLedger()
            runCatching { admitOnce(ledger, gate = CompletableDeferred()) }

            admitOnce(ledger)
            admitOnce(ledger)

            assertEquals(listOf(TOKEN), ledger.messages)
        }

    /** An admission that failed but still landed is counted as published from its status. */
    @Test
    fun aFailedAdmissionThatLandedCountsAsPublished() =
        runTest {
            val ledger = FakeTokenLedger()

            admitForwardPublish(
                messageIndex = 0,
                token = TOKEN,
                probe = { ledger.probe(TOKEN) },
                admit = {
                    ledger.admit(TOKEN)
                    throw IllegalStateException("response lost")
                },
            )

            assertEquals(listOf(TOKEN), ledger.messages)
        }

    /** A failed admission with no ownership is not committed, so the same token may be admitted again. */
    @Test
    fun aFailedAdmissionWithoutOwnershipIsNotCommitted() =
        runTest {
            val ledger = FakeTokenLedger()

            val failure =
                runCatching { admitOnce(ledger, admitFailure = IllegalStateException("engine busy")) }
                    .exceptionOrNull()

            assertTrue("expected not committed, got $failure", failure is ForwardPublishNotCommittedException)
        }

    /** A failed admission whose status cannot be read stays uncertain and carries the token as its evidence. */
    @Test
    fun aFailedAdmissionWithAnUnreadableStatusIsUncertainWithItsToken() =
        runTest {
            val ledger = FakeTokenLedger()
            var probes = 0

            val failure =
                runCatching {
                    admitForwardPublish(
                        messageIndex = 3,
                        token = TOKEN,
                        probe = {
                            probes += 1
                            if (probes > 1) throw IllegalStateException("status unavailable")
                            null
                        },
                        admit = { throw IllegalStateException("engine busy") },
                    )
                }.exceptionOrNull()

            if (failure !is ForwardPublishUncertainException) fail("expected uncertain, got $failure")
            val evidence = (failure as ForwardPublishUncertainException).evidence
            assertEquals(3, evidence.messageIndex)
            assertEquals(TOKEN, evidence.clientToken)
        }

    /** A token MDK rejected is reported for replacement and never admitted again. */
    @Test
    fun aRejectedTokenIsReportedAndNotAdmitted() =
        runTest {
            val ledger = FakeTokenLedger().apply { statuses[TOKEN] = LocalSendStatusFfi.Rejected }

            val failure = runCatching { admitOnce(ledger) }.exceptionOrNull()

            assertTrue("expected rejected, got $failure", failure is ForwardPublishRejectedException)
            assertTrue(ledger.messages.isEmpty())
        }

    /** Token recovery maps ownership to published, absence to not committed and an unreadable status to unavailable. */
    @Test
    fun tokenRecoveryResolvesFromStatusAlone() =
        runTest {
            val evidence = ForwardPublishRecoveryEvidence(0, emptySet(), null, clientToken = TOKEN)

            assertEquals(
                ForwardPublishRecoveryResult.Published,
                recoverForwardPublishByToken(evidence) { LocalSendStatusFfi.EngineOwned },
            )
            assertEquals(ForwardPublishRecoveryResult.NotCommitted, recoverForwardPublishByToken(evidence) { null })
            assertEquals(
                ForwardPublishRecoveryResult.Unavailable,
                recoverForwardPublishByToken(evidence) { throw IllegalStateException("status unavailable") },
            )
            assertEquals(
                ForwardPublishRecoveryResult.Unavailable,
                recoverForwardPublishByToken(evidence.copy(clientToken = null)) { LocalSendStatusFfi.Queued },
            )
        }

    private companion object {
        const val TOKEN = "opaque-token"
        const val TIMEOUT_MILLIS = 1_000L
    }
}
