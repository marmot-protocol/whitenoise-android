package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.flow.StateFlow

/** Short retry budget for background operations and base foreground backoff. */
internal const val SEND_RETRY_ATTEMPTS: Int = 3
internal const val SEND_RETRY_BACKOFF_MS: Long = 700L
internal const val PENDING_SEND_RETRY_MAX_BACKOFF_MS: Long = 60_000L
private const val PENDING_SEND_MAX_BACKOFF_DOUBLINGS: Int = 16

/**
 * True only when a transport failure proves the event was never sent to a
 * relay. Re-entering `sendText` creates a new inner event, so any failure that
 * can occur after transmission must stay excluded to prevent duplicates.
 */
internal fun isTransientRelaySendError(throwable: Throwable): Boolean {
    val text = throwable.causeChainText()
    if (
        listOf(
            "connection reset",
            "send event failed",
            "send event timed out",
            "relay did not acknowledge event",
            "relay rejected event",
            "publish timed out after",
            "insufficient publish acknowledgements",
        ).any(text::contains)
    ) {
        return false
    }
    return ("connect relay failed" in text) ||
        ("connect relay" in text && ("timed out" in text || "timeout" in text)) ||
        ("connection refused" in text) ||
        ("no relay endpoints" in text)
}

/**
 * True when publication was attempted but Android cannot prove whether a
 * relay accepted it. These outcomes stay pending; a high-level resend could
 * create a duplicate event.
 */
internal fun isAmbiguousRelayDeliveryError(throwable: Throwable): Boolean {
    val causes = throwable.causeChain()
    val transportClosed = causes.any { it is MarmotKitException.TransportClosed }
    val text = causes.joinToString("\n") { it.errorIdentity() }.lowercase()
    val explicitRejection = "relay rejected event" in text
    val ambiguousPublish =
        listOf(
            "connection reset",
            "send event failed",
            "send event timed out",
            "relay did not acknowledge event",
            "publish timed out after",
            "insufficient publish acknowledgements",
        ).any(text::contains)
    return transportClosed || (!explicitRejection && ambiguousPublish)
}

/** Bounded connect-phase retry used by background send operations. */
@Suppress("TooGenericExceptionCaught") // Every non-cancellation gateway failure must be classified before retry.
internal suspend fun <T> retryTransientRelaySend(
    onTransientFailure: suspend (attempt: Int, throwable: Throwable) -> Unit = { _, _ -> },
    sendAttempt: suspend (attempt: Int) -> T,
): T {
    var lastTransient: Throwable? = null
    for (attempt in 1..SEND_RETRY_ATTEMPTS) {
        try {
            return sendAttempt(attempt)
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
            if (!isTransientRelaySendError(throwable)) throw throwable
            lastTransient = throwable
            onTransientFailure(attempt, throwable)
            if (attempt < SEND_RETRY_ATTEMPTS) {
                kotlinx.coroutines.delay(SEND_RETRY_BACKOFF_MS)
            }
        }
    }
    throw lastTransient ?: IllegalStateException("send retry budget exhausted")
}

internal fun pendingSendRetryBackoffMs(failedAttempt: Int): Long {
    var backoffMs = SEND_RETRY_BACKOFF_MS
    repeat((failedAttempt - 1).coerceIn(0, PENDING_SEND_MAX_BACKOFF_DOUBLINGS)) {
        backoffMs = (backoffMs * 2).coerceAtMost(PENDING_SEND_RETRY_MAX_BACKOFF_MS)
    }
    return backoffMs
}

/**
 * Keep a foreground send pending across proven pre-publish connectivity
 * failures. Coroutine cancellation is the lifecycle boundary. [sendAttempt]
 * must acquire and release any shared commit lock within one invocation; the
 * retry loop deliberately owns no lock while it waits between attempts. A
 * newer [connectivityRecoveryGeneration] interrupts that wait and resets the
 * backoff so Android does not sleep through restored validated internet.
 */
@Suppress("TooGenericExceptionCaught") // Every non-cancellation gateway failure must be classified before retry.
internal suspend fun <T> retryPendingConversationSend(
    connectivityRecoveryGeneration: StateFlow<Long>? = null,
    onTransientFailure: suspend (attempt: Int, throwable: Throwable) -> Unit = { _, _ -> },
    sendAttempt: suspend (attempt: Int) -> T,
): T {
    var attempt = 1
    var backoffAttempt = 1
    while (true) {
        val recoveryGenerationBeforeAttempt = connectivityRecoveryGeneration?.value
        try {
            return sendAttempt(attempt)
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
            if (!isTransientRelaySendError(throwable)) throw throwable
            onTransientFailure(attempt, throwable)
            val wokeForConnectivity =
                awaitPendingSendRetryWindow(
                    connectivityRecoveryGeneration = connectivityRecoveryGeneration,
                    observedGeneration = recoveryGenerationBeforeAttempt,
                    backoffMs = pendingSendRetryBackoffMs(backoffAttempt),
                )
            backoffAttempt =
                when {
                    wokeForConnectivity -> 1
                    backoffAttempt < Int.MAX_VALUE -> backoffAttempt + 1
                    else -> Int.MAX_VALUE
                }
            if (attempt < Int.MAX_VALUE) attempt += 1
        }
    }
}

internal fun isTransientRuntimeWorkerError(throwable: Throwable): Boolean =
    throwable
        .causeChain()
        .any { it is MarmotKitException.TransportClosed }

/**
 * Limits automatic mutation retries to failures that are typed as transient
 * and to connection gaps that prove the operation never reached a relay.
 * Ambiguous worker timeouts and queue pressure remain terminal here.
 */
internal fun isRetryableIdempotentMutationError(throwable: Throwable): Boolean =
    isTransientRuntimeWorkerError(throwable) ||
        isTypedMutationContention(throwable) ||
        isTransientRelaySendError(throwable)

/** Native ownership/storage contention is distinct from transport failures and ambiguous queue pressure. */
internal fun isTypedMutationContention(throwable: Throwable): Boolean =
    throwable.causeChain().any { cause ->
        cause is MarmotKitException.AccountWorkerBusy ||
            cause is MarmotKitException.RuntimeBusy ||
            cause is MarmotKitException.AccountSessionBusy ||
            cause is MarmotKitException.StorageBusy
    }

private fun Throwable.causeChain(): List<Throwable> =
    generateSequence(this) { current ->
        current.cause?.takeUnless { it === current }
    }.toList()

private fun Throwable.causeChainText(): String = causeChain().joinToString("\n") { it.errorIdentity() }.lowercase()

private fun Throwable.errorIdentity(): String = listOfNotNull(message, javaClass.simpleName).joinToString(" ")
