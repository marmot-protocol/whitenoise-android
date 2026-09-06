package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.notifications.NotificationReactionSendOutcome
import dev.ipf.whitenoise.android.notifications.NotificationReplySendOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for issue #294: sending a message occasionally failed
 * with a "relays not connected"-style error even when relays were reachable a
 * moment later.
 *
 * Root cause: a publish that began during a *transient* relay-pool gap (socket
 * teardown mid-reconnect on a doze wake / network change) saw an empty or
 * still-handshaking pool at the single instant it fanned out, and the Nostr
 * transport returned a connect timeout. `ConversationController.send()`
 * surfaced that first failure as a hard, user-visible "send failed" instead of
 * giving the pool a brief window to (re)connect and retrying.
 *
 * Foreground conversation sends stay pending and retry with capped backoff;
 * bounded background operations use [SEND_RETRY_ATTEMPTS]. Both policies apply
 * ONLY to failures [isTransientRelaySendError] classifies as *connect-phase*
 * connectivity — the cases that prove the event never reached a relay.
 *
 * IDEMPOTENCY CONTRACT (adversarial review of PR #299): the retry re-enters the
 * high-level FFI send (`sendText`/`replyToMessage`), and the Marmot runtime
 * builds a brand-new inner app event per call. So the classifier must reject any
 * ambiguous *post-send* failure where the first event may already have reached a
 * relay — otherwise a lost/late ack would make us republish a second distinct
 * event and peers would see a duplicate message. These tests pin both halves:
 * connect-phase failures are retried; post-send/ambiguous and terminal failures
 * are not.
 */
class TransientRelaySendErrorTest {
    @Test
    fun sharedSendPolicyRetriesAConnectGapThenReturnsSuccess() =
        runTest {
            var attempts = 0

            val result =
                retryTransientRelaySend { attempt ->
                    attempts = attempt
                    if (attempt == 1) throw MarmotKitException.Publish("connect relay failed")
                    "sent"
                }

            assertEquals("sent", result)
            assertEquals(2, attempts)
        }

    @Test
    fun sharedSendPolicyExhaustsTheBoundedConnectRetryBudget() =
        runTest {
            var attempts = 0

            val failure =
                runCatching {
                    retryTransientRelaySend { attempt ->
                        attempts = attempt
                        throw MarmotKitException.Publish("connection refused")
                    }
                }.exceptionOrNull()

            assertTrue(failure is MarmotKitException.Publish)
            assertEquals(SEND_RETRY_ATTEMPTS, attempts)
        }

    @Test
    fun sharedSendPolicyNeverRetriesAmbiguousOrCancelledSends() =
        runTest {
            var ambiguousAttempts = 0
            val ambiguous =
                runCatching {
                    retryTransientRelaySend {
                        ambiguousAttempts += 1
                        throw MarmotKitException.Publish("send event timed out")
                    }
                }.exceptionOrNull()

            assertTrue(ambiguous is MarmotKitException.Publish)
            assertEquals(1, ambiguousAttempts)

            var cancelledAttempts = 0
            val cancellation =
                runCatching {
                    retryTransientRelaySend {
                        cancelledAttempts += 1
                        throw CancellationException("conversation closed")
                    }
                }.exceptionOrNull()

            assertTrue(cancellation is CancellationException)
            assertEquals(1, cancelledAttempts)
        }

    // ---- Retryable: connect-phase, event provably never sent -------------

    @Test
    fun connectRelayTimedOutIsTransient() {
        assertTrue(isTransientRelaySendError(RuntimeException("connect relay timed out")))
    }

    @Test
    fun connectRelayFailureFromMdkIsTransient() {
        assertTrue(
            isTransientRelaySendError(
                MarmotKitException.Publish("connect relay failed"),
            ),
        )
    }

    @Test
    fun mixedConnectAndPostSendFailuresAreNotTransient() {
        listOf(
            "connect relay failed; send event failed",
            "connect relay timed out; send event timed out",
            "relay did not acknowledge event; connect relay failed",
            "relay rejected event (blocked); connect relay failed",
            "connect relay failed; publish timed out after 30s: accepted 0 of required 2",
            "connect relay failed; insufficient publish acknowledgements: accepted 0 of required 2",
            "connect relay failed; connection reset by peer",
        ).forEach { reason ->
            assertFalse(reason, isTransientRelaySendError(MarmotKitException.Publish(reason)))
        }
    }

    @Test
    fun connectionRefusedIsTransientButUnqualifiedResetIsAmbiguous() {
        assertTrue(isTransientRelaySendError(RuntimeException("Connection refused")))
        val reset = RuntimeException("connection reset by peer")
        assertFalse(isTransientRelaySendError(reset))
        assertTrue(isAmbiguousRelayDeliveryError(reset))
    }

    @Test
    fun noRelayEndpointsIsTransient() {
        assertTrue(isTransientRelaySendError(RuntimeException("directory fetch: no relay endpoints")))
    }

    @Test
    fun classifierWalksTheCauseChain() {
        val nested =
            RuntimeException(
                "publish failed",
                IllegalStateException("connect relay timed out"),
            )
        assertTrue(isTransientRelaySendError(nested))
    }

    // ---- NOT retryable: post-send / ambiguous, event may have landed -----
    // Re-sending these would re-enter the high-level FFI and build a NEW event,
    // duplicating a message that the first attempt may have already delivered.

    @Test
    fun sendEventTimedOutIsNotTransient() {
        // `send_event_to` was called; the frame may have landed and only the OK
        // ack timed out. Retrying could duplicate the message.
        assertFalse(isTransientRelaySendError(RuntimeException("send event timed out")))
    }

    @Test
    fun publishTimedOutIsNotTransient() {
        // "publish timed out after Ns: accepted X of required Y" — the same
        // string is emitted whether `accepted` is 0 or > 0, so we cannot prove
        // nothing landed.
        assertFalse(
            isTransientRelaySendError(
                RuntimeException("publish timed out after 30s: accepted 0 of required 1"),
            ),
        )
        assertFalse(
            isTransientRelaySendError(
                RuntimeException("publish timed out after 30s: accepted 1 of required 2"),
            ),
        )
    }

    @Test
    fun insufficientAcknowledgementsIsNotTransient() {
        // `accepted` can be > 0 — at least one relay took the event.
        assertFalse(
            isTransientRelaySendError(
                RuntimeException("insufficient publish acknowledgements: accepted 1 of required 2"),
            ),
        )
    }

    @Test
    fun relayDidNotAcknowledgeIsNotTransient() {
        // The relay returned the event in `output.failed`; it WAS transmitted,
        // only the acknowledgement is missing.
        assertFalse(
            isTransientRelaySendError(RuntimeException("relay did not acknowledge event")),
        )
    }

    @Test
    fun transportClosedIsNotTransient() {
        // `MarmotKitException.TransportClosed` flattens to an empty message and
        // surfaces from BOTH the pre-publish worker command channel and the
        // post-publish response channel (the worker may have already published).
        // The two are indistinguishable, so it must NOT be auto-retried.
        class TransportClosed : Exception()
        assertFalse(isTransientRelaySendError(TransportClosed()))
    }

    @Test
    fun postPublishFailuresAreAmbiguousButExplicitRejectionIsTerminal() {
        listOf(
            MarmotKitException.Publish("send event failed"),
            MarmotKitException.Publish("send event timed out"),
            MarmotKitException.Publish("relay did not acknowledge event"),
            MarmotKitException.Publish("publish timed out after 30s: accepted 1 of required 2"),
            MarmotKitException.Publish("insufficient publish acknowledgements: accepted 0 of required 1"),
            MarmotKitException.Publish("connection reset by peer"),
            MarmotKitException.TransportClosed(),
        ).forEach { throwable -> assertTrue(isAmbiguousRelayDeliveryError(throwable)) }

        assertFalse(isAmbiguousRelayDeliveryError(MarmotKitException.Publish("relay rejected event")))
        assertFalse(isAmbiguousRelayDeliveryError(MarmotKitException.Publish("connect relay failed")))
    }

    // ---- NOT retryable: terminal / shutdown ------------------------------

    @Test
    fun runtimeStoppingIsNotTransient() {
        // Runtime is shutting down (sign-out/teardown): retrying only delays a
        // send that can never land, so it must fail fast.
        class RuntimeStopping : Exception()
        assertFalse(isTransientRelaySendError(RuntimeStopping()))
    }

    @Test
    fun terminalLogicErrorsAreNotTransient() {
        // Unknown group / missing key package / invalid hex etc. are not
        // connectivity problems — retrying them is pointless and would delay a
        // legitimate failure toast.
        assertFalse(isTransientRelaySendError(RuntimeException("groupIdHex=abc123")))
        assertFalse(isTransientRelaySendError(IllegalArgumentException("details=bad input")))
        assertFalse(isTransientRelaySendError(RuntimeException("unexpected boom")))
    }

    @Test
    fun notificationReplyOutcomeTreatsConnectGapAsRetryable() {
        assertEquals(
            NotificationReplySendOutcome.RetryableFailure,
            notificationReplySendFailureOutcome(MarmotKitException.Publish("connect relay failed")),
        )
    }

    @Test
    fun notificationReplyOutcomeTreatsUnknownGroupAsNonRetryable() {
        assertEquals(
            NotificationReplySendOutcome.NonRetryableFailure,
            notificationReplySendFailureOutcome(MarmotKitException.UnknownGroup("abc123")),
        )
    }

    @Test
    fun notificationReactionOutcomeRetriesOnlyProvenConnectGaps() {
        assertEquals(
            NotificationReactionSendOutcome.RetryableFailure,
            notificationReactionSendFailureOutcome(RuntimeException("connection refused")),
        )
        assertEquals(
            NotificationReactionSendOutcome.NonRetryableFailure,
            notificationReactionSendFailureOutcome(RuntimeException("send event timed out")),
        )
    }

    @Test
    fun retryBudgetIsBoundedAndPositive() {
        // A misconfigured budget would either never retry (defeats the fix) or
        // retry unboundedly (hangs the send coroutine). Pin the invariant.
        assertTrue(SEND_RETRY_ATTEMPTS in 2..6)
        assertTrue(SEND_RETRY_BACKOFF_MS in 100L..3_000L)
    }

    @Test
    fun pendingSendBackoffGrowsAndCaps() {
        assertEquals(SEND_RETRY_BACKOFF_MS, pendingSendRetryBackoffMs(1))
        assertEquals(SEND_RETRY_BACKOFF_MS * 2, pendingSendRetryBackoffMs(2))
        assertEquals(PENDING_SEND_RETRY_MAX_BACKOFF_MS, pendingSendRetryBackoffMs(100))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pendingSendRetryWakesImmediatelyOnValidatedConnectivityRecovery() =
        runTest {
            val recoveryGeneration = MutableStateFlow(0L)
            var attempts = 0
            val send =
                async {
                    retryPendingConversationSend(
                        connectivityRecoveryGeneration = recoveryGeneration,
                    ) {
                        attempts += 1
                        if (attempts == 1) throw MarmotKitException.Publish("connect relay failed")
                        "sent"
                    }
                }

            runCurrent()
            assertEquals(1, attempts)

            recoveryGeneration.value += 1
            runCurrent()

            assertEquals("sent", send.await())
            assertEquals(2, attempts)
            assertEquals("the retry timer must not advance virtual time", 0L, currentTime)
        }
}
