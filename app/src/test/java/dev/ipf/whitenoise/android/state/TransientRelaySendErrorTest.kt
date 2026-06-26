package dev.ipf.whitenoise.android.state

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
 * The fix retries the send across a bounded budget ([SEND_RETRY_ATTEMPTS]) but
 * ONLY for failures [isTransientRelaySendError] classifies as *connect-phase*
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
    // ---- Retryable: connect-phase, event provably never sent -------------

    @Test
    fun connectRelayTimedOutIsTransient() {
        assertTrue(isTransientRelaySendError(RuntimeException("connect relay timed out")))
    }

    @Test
    fun connectionRefusedAndResetAreTransient() {
        assertTrue(isTransientRelaySendError(RuntimeException("Connection refused")))
        assertTrue(isTransientRelaySendError(RuntimeException("connection reset by peer")))
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
    fun retryBudgetIsBoundedAndPositive() {
        // A misconfigured budget would either never retry (defeats the fix) or
        // retry unboundedly (hangs the send coroutine). Pin the invariant.
        assertTrue(SEND_RETRY_ATTEMPTS in 2..6)
        assertTrue(SEND_RETRY_BACKOFF_MS in 100L..3_000L)
    }
}
