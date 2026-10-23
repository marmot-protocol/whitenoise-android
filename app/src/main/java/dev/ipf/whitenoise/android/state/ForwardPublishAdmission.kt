package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * How long one forwarded message may take from its first status check to MDK's engine owning it (#2556). Durable
 * admission returns in well under a second on a healthy device and the account worker usually takes the message
 * shortly after; a message still waiting at this bound is stalled behind the worker or the account's storage, so the
 * destination fails with a publish-timeout and Retry re-checks the same client token instead of sending again.
 */
internal const val FORWARD_PUBLISH_ADMISSION_MILLIS = 30_000L

/** How often a queued forward re-reads its token's local status while waiting for the engine to take it. */
internal const val FORWARD_PUBLISH_STATUS_POLL_MILLIS = 250L

/** A forwarded message was not owned by MDK's engine within [FORWARD_PUBLISH_ADMISSION_MILLIS]. */
internal class ForwardPublishTimeoutException : IllegalStateException("forward publish was not handed off in time")

/**
 * MDK rejected the forwarded message at [messageIndex], either at admission or while it was still queued. Its client
 * token is spent, so the session replaces it, and the destination waits for a deliberate Retry rather than
 * re-admitting automatically, so repeated rejections never stack failed messages.
 */
internal class ForwardPublishRejectedException(
    val messageIndex: Int,
) : IllegalStateException("forward publish was rejected")

/**
 * The admission call failed and MDK shows no ownership of the token, so nothing was admitted. Retry may admit the
 * same token again without risking a second message.
 */
internal class ForwardPublishNotCommittedException(
    cause: Throwable,
) : IllegalStateException("forward publish failed before admission", cause)

/** Whether MDK's engine has taken the submission, the point at which a forwarded message counts as sent. */
private val LocalSendStatusFfi?.handedOff: Boolean
    get() = this is LocalSendStatusFfi.Completed || this == LocalSendStatusFfi.EngineOwned

/**
 * Publishes forwarded message [messageIndex] under its stable client [token] and returns only once MDK's engine owns
 * it, so a message that is merely queued and later rejected can never be reported as sent.
 *
 * An owned token is never admitted again: an earlier admission is waited on, and only a token MDK does not know is
 * admitted, after [beforeAdmit] re-checks the owner. [onAdmitted] runs once after a new admission, for the same
 * best-effort convergence nudge the composer gives an accepted-pending send. The whole sequence, including every
 * status read, is bounded by [deadlineMillis] and ends in [ForwardPublishTimeoutException]; the callers' probe and
 * admission must therefore be cancellable while their native call is still running.
 */
@Suppress("LongParameterList", "ThrowsCount")
internal suspend fun admitForwardPublish(
    messageIndex: Int,
    token: String,
    probe: suspend () -> LocalSendStatusFfi?,
    admit: suspend () -> LocalSendAcceptanceFfi,
    beforeAdmit: () -> Unit = {},
    onAdmitted: suspend () -> Unit = {},
    deadlineMillis: Long = FORWARD_PUBLISH_ADMISSION_MILLIS,
    pollMillis: Long = FORWARD_PUBLISH_STATUS_POLL_MILLIS,
) {
    withTimeoutOrNull(deadlineMillis) {
        val existing = probeOrNull(probe)
        when {
            existing.handedOff -> return@withTimeoutOrNull Unit
            existing == LocalSendStatusFfi.Rejected -> throw ForwardPublishRejectedException(messageIndex)
            existing == null -> {
                beforeAdmit()
                admitOrClassify(messageIndex, token, probe, admit)
                onAdmitted()
            }
        }
        awaitEngineHandoff(messageIndex, probe, pollMillis)
    } ?: throw ForwardPublishTimeoutException()
}

/**
 * Admits a token MDK does not yet know. A failed admission is classified from the token's status afterwards: owned
 * continues to the handoff wait, rejected is reported, and absent or unreadable means nothing was admitted.
 */
@Suppress("ThrowsCount", "TooGenericExceptionCaught")
private suspend fun admitOrClassify(
    messageIndex: Int,
    token: String,
    probe: suspend () -> LocalSendStatusFfi?,
    admit: suspend () -> LocalSendAcceptanceFfi,
) {
    try {
        val acceptance = admit()
        check(acceptance.clientToken == token) { "forward admission changed the caller token" }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        when (probeOrNull(probe)) {
            LocalSendStatusFfi.Rejected -> throw ForwardPublishRejectedException(messageIndex)
            null -> throw ForwardPublishNotCommittedException(failure)
            else -> Unit
        }
    }
}

/** Polls the token until MDK's engine owns it; a rejection while queued is reported, an unreadable read retried. */
private suspend fun awaitEngineHandoff(
    messageIndex: Int,
    probe: suspend () -> LocalSendStatusFfi?,
    pollMillis: Long,
) {
    while (true) {
        val status = probeOrNull(probe)
        if (status.handedOff) return
        if (status == LocalSendStatusFfi.Rejected) throw ForwardPublishRejectedException(messageIndex)
        delay(pollMillis)
    }
}

/** Reads a token's status, treating an unreadable status like an unknown one; cancellation still propagates. */
@Suppress("TooGenericExceptionCaught")
private suspend fun probeOrNull(probe: suspend () -> LocalSendStatusFfi?): LocalSendStatusFfi? =
    try {
        probe()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

/**
 * One forward session's client tokens: one opaque token per destination and message, minted once and kept across
 * retries so MDK resolves a repeated admission to the message it already owns. Only a rejected admission replaces
 * its token. Tokens never contain identifiers or content.
 */
internal class ForwardPublishTokens {
    private val tokens = mutableMapOf<Pair<String, Int>, String>()

    /** The stable token for [messageIndex] to [targetGroupIdHex], minted on first use. */
    fun tokenFor(
        targetGroupIdHex: String,
        messageIndex: Int,
    ): String = tokens.getOrPut(targetGroupIdHex to messageIndex) { UUID.randomUUID().toString() }

    /** Drops a token MDK rejected, so the user's next Retry admits that message under a fresh one. */
    fun forget(
        targetGroupIdHex: String,
        messageIndex: Int,
    ) {
        tokens.remove(targetGroupIdHex to messageIndex)
    }

    /** Forgets every token once the session can no longer retry. */
    fun clear() = tokens.clear()
}
