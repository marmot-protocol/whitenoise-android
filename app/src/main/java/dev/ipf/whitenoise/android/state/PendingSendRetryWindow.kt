package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Wait for the timer, usable connectivity, or cancellation of an optimistic send. */
internal suspend fun awaitPendingSendRetryWindow(
    connectivityRecoveryGeneration: StateFlow<Long>?,
    observedGeneration: Long?,
    backoffMs: Long,
    cancellationGeneration: StateFlow<Long>? = null,
    observedCancellationGeneration: Long? = null,
): Boolean =
    when {
        cancellationGeneration != null &&
            observedCancellationGeneration != null &&
            cancellationGeneration.value != observedCancellationGeneration -> false
        connectivityRecoveryGeneration == null && cancellationGeneration == null -> {
            delay(backoffMs)
            false
        }
        connectivityRecoveryGeneration != null && connectivityRecoveryGeneration.value != observedGeneration -> true
        else ->
            withTimeoutOrNull(backoffMs) {
                when {
                    connectivityRecoveryGeneration == null -> {
                        cancellationGeneration!!.first { it != observedCancellationGeneration }
                        false
                    }
                    cancellationGeneration == null -> {
                        connectivityRecoveryGeneration.first { it != observedGeneration }
                        true
                    }
                    else -> {
                        combine(connectivityRecoveryGeneration, cancellationGeneration) { recovery, cancelled ->
                            recovery to cancelled
                        }.first { (recovery, cancelled) ->
                            recovery != observedGeneration || cancelled != observedCancellationGeneration
                        }.first != observedGeneration
                    }
                }
            } ?: false
    }
