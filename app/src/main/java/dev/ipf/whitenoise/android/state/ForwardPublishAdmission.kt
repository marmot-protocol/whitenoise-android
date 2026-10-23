package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * How long one forwarded message may take to reach MDK's durable local admission (#2556). Admission returns before
 * relay publication, so on a healthy device it takes well under a second; a call still waiting after this bound is
 * stalled behind the account worker or commit queue. The target then fails with a publish-timeout and Retry
 * re-enters with the same client token, which MDK resolves to the same message instead of a duplicate.
 */
internal const val FORWARD_PUBLISH_ADMISSION_MILLIS = 30_000L

/** A forwarded message did not reach durable local admission within [FORWARD_PUBLISH_ADMISSION_MILLIS]. */
internal class ForwardPublishTimeoutException : IllegalStateException("forward publish admission timed out")

/**
 * MDK rejected the forwarded message's admission before engine acceptance. Its client token is spent, so the
 * session replaces the token for [messageIndex] before the user retries.
 */
internal class ForwardPublishRejectedException(
    val messageIndex: Int,
) : IllegalStateException("forward publish admission was rejected")

/** Whether MDK already owns the submission behind a token, so it must never be admitted again. */
private val LocalSendStatusFfi.ownsSubmission: Boolean
    get() =
        this is LocalSendStatusFfi.Completed ||
            this == LocalSendStatusFfi.Queued ||
            this == LocalSendStatusFfi.EngineOwned

/**
 * Admits forwarded message [messageIndex] under its stable client [token] without ever publishing it twice.
 *
 * MDK already owning the token means an earlier attempt was admitted, so nothing is resubmitted. Otherwise the
 * message is admitted within [admissionTimeoutMillis]; reusing the same token and request returns the same identity,
 * so a retry after a timeout or an unclear failure is safe. A failure followed by a status that still shows no
 * ownership is reported as not committed, and an unreadable status as uncertain with the token as its evidence.
 * [beforeAdmit] runs between the probe and the admission, so an owner that left during the probe stops the send.
 */
@Suppress("LongParameterList", "ThrowsCount", "TooGenericExceptionCaught")
internal suspend fun admitForwardPublish(
    messageIndex: Int,
    token: String,
    probe: suspend () -> LocalSendStatusFfi?,
    admit: suspend () -> LocalSendAcceptanceFfi,
    admissionTimeoutMillis: Long = FORWARD_PUBLISH_ADMISSION_MILLIS,
    beforeAdmit: () -> Unit = {},
) {
    // An unreadable status is not a reason to stop, because admitting the same token again is idempotent.
    val existing =
        try {
            probe()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    when {
        existing?.ownsSubmission == true -> return
        existing == LocalSendStatusFfi.Rejected -> throw ForwardPublishRejectedException(messageIndex)
    }
    beforeAdmit()
    val acceptance =
        try {
            withTimeoutOrNull(admissionTimeoutMillis) { admit() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val after =
                try {
                    probe()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (probeFailure: Exception) {
                    failure.addSuppressed(probeFailure)
                    throw ForwardPublishUncertainException(forwardTokenEvidence(messageIndex, token), failure)
                }
            when {
                after?.ownsSubmission == true -> return
                after == LocalSendStatusFfi.Rejected -> throw ForwardPublishRejectedException(messageIndex)
                else -> throw ForwardPublishNotCommittedException(failure)
            }
        } ?: throw ForwardPublishTimeoutException()
    check(acceptance.clientToken == token) { "forward admission changed the caller token" }
}

/**
 * Resolves an uncertain forwarded publish from its client token alone: owned means published, absent means nothing
 * was admitted and the same token may be admitted again, and an unreadable status stays unavailable.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun recoverForwardPublishByToken(
    evidence: ForwardPublishRecoveryEvidence,
    probe: suspend (token: String) -> LocalSendStatusFfi?,
): ForwardPublishRecoveryResult {
    val probed =
        evidence.clientToken?.let { token ->
            try {
                Result.success(probe(token))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        } ?: Result.failure(IllegalStateException("uncertain publish carries no client token"))
    val status = probed.getOrElse { return ForwardPublishRecoveryResult.Unavailable }
    return when {
        status?.ownsSubmission == true -> ForwardPublishRecoveryResult.Published
        status == LocalSendStatusFfi.Rejected -> throw ForwardPublishRejectedException(evidence.messageIndex)
        else -> ForwardPublishRecoveryResult.NotCommitted
    }
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

    /** Drops a token MDK rejected, so the next deliberate retry admits that message under a fresh one. */
    fun forget(
        targetGroupIdHex: String,
        messageIndex: Int,
    ) {
        tokens.remove(targetGroupIdHex to messageIndex)
    }

    /** Forgets every token once the session can no longer retry. */
    fun clear() = tokens.clear()
}

/** Recovery evidence for an uncertain token-bound publish; the token is the only evidence MDK needs. */
private fun forwardTokenEvidence(
    messageIndex: Int,
    token: String,
) = ForwardPublishRecoveryEvidence(
    messageIndex = messageIndex,
    knownMessageIdsBefore = emptySet(),
    pendingMessageIdHex = null,
    clientToken = token,
)
