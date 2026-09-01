package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Wait for either the retry timer or a newer usable-connectivity edge. */
internal suspend fun awaitPendingSendRetryWindow(
    connectivityRecoveryGeneration: StateFlow<Long>?,
    observedGeneration: Long?,
    backoffMs: Long,
): Boolean =
    when {
        connectivityRecoveryGeneration == null || observedGeneration == null -> {
            delay(backoffMs)
            false
        }
        connectivityRecoveryGeneration.value != observedGeneration -> true
        else ->
            withTimeoutOrNull(backoffMs) {
                connectivityRecoveryGeneration.first { generation -> generation != observedGeneration }
                true
            } ?: false
    }
