package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Presentation state for one received attachment transfer.
 *
 * This is short-lived controller state, not a second protocol-data store. MDK
 * remains authoritative for the attachment reference and the encrypted media
 * cache remains authoritative for local byte availability.
 */
internal enum class AttachmentTransferState {
    Resolving,
    Remote,
    Downloading,
    Available,
    NotRetained,
    Failed,
}

/**
 * Owns the UI-facing lifecycle of attachment downloads for one conversation.
 *
 * Callers may request the same attachment from auto-download and tap-to-open
 * paths, but only one coordinator-owned [Deferred] performs the work and owns
 * state transitions. Awaiting callers can disappear without cancelling that
 * owner; the underlying app-level download can still finish and publish to the
 * encrypted cache.
 */
internal class AttachmentTransferCoordinator(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val states = mutableMapOf<String, MutableStateFlow<AttachmentTransferState>>()
    private val availabilitySignals = AttachmentAvailabilitySignals()
    private val active = mutableMapOf<String, Deferred<ByteArray>>()
    private val terminalGenerations = mutableMapOf<String, Long>()
    private val observerCounts = mutableMapOf<String, Int>()

    fun acquireState(
        key: String,
        initiallyAvailable: Boolean,
    ): StateFlow<AttachmentTransferState> =
        synchronized(lock) {
            observerCounts[key] = (observerCounts[key] ?: 0) + 1
            availabilitySignals.acquire(key)
            stateFlow(key, initiallyAvailable).asStateFlow()
        }

    fun releaseState(key: String) {
        synchronized(lock) {
            observerCounts[key]?.let { observers ->
                observerCounts[key] = (observers - 1).coerceAtLeast(0)
                retireStateIfUnused(key)
            }
        }
    }

    fun state(
        key: String,
        initiallyAvailable: Boolean,
    ): StateFlow<AttachmentTransferState> =
        synchronized(lock) {
            stateFlow(key, initiallyAvailable).asStateFlow()
        }

    /** Waits for a fresh retained/cache-confirmed completion for exactly one attachment key. */
    suspend fun awaitNextAvailability(key: String) {
        synchronized(lock) { availabilitySignals.acquire(key) }.first()
    }

    /** Refreshes availability without disturbing a download already in flight. */
    suspend fun refresh(
        key: String,
        probe: suspend () -> Boolean,
    ) {
        val generation = synchronized(lock) { terminalGenerations[key] ?: 0L }
        val available = probeForRefresh(probe)
        synchronized(lock) {
            // The cache probe runs outside the lock. A transfer may finish and
            // publish a newer terminal state while the probe is suspended, so
            // never let that stale result overwrite the completion state.
            val state = currentStateForRefresh(key, generation) ?: return
            state.value = refreshedState(state.value, available)
            availabilitySignals.onRefresh(key, available)
        }
    }

    /**
     * Returns the existing transfer when present, otherwise starts one owner.
     * A cache-hot load keeps [AttachmentTransferState.Available] to avoid a
     * one-frame spinner while bytes are read for external opening.
     */
    @Suppress("TooGenericExceptionCaught") // The owner must publish Failed for every non-cancellation MDK/IO exception.
    fun request(
        key: String,
        load: suspend () -> ByteArray,
        availableAfterLoad: suspend () -> Boolean,
    ): Deferred<ByteArray> {
        var created = false
        val deferred =
            synchronized(lock) {
                active[key]?.takeUnless { it.isCompleted }?.let { return@synchronized it }
                val state = stateFlow(key)
                val stateBeforeDownload = state.value
                if (stateBeforeDownload != AttachmentTransferState.Available) {
                    state.value = AttachmentTransferState.Downloading
                }
                scope
                    .async(start = CoroutineStart.LAZY) {
                        try {
                            val bytes = load()
                            // MDK rejects empty plaintext when creating media;
                            // receiving an empty result violates that contract.
                            check(bytes.isNotEmpty()) { "attachment download returned empty plaintext" }
                            val retained = probeAvailability(availableAfterLoad)
                            publishTerminalState(
                                key,
                                state,
                                attachmentStateAfterRetention(retained),
                            )
                            bytes
                        } catch (cancellation: CancellationException) {
                            publishTerminalState(
                                key,
                                state,
                                if (stateBeforeDownload == AttachmentTransferState.Available) {
                                    AttachmentTransferState.Available
                                } else {
                                    AttachmentTransferState.Remote
                                },
                            )
                            throw cancellation
                        } catch (exception: Exception) {
                            publishTerminalState(key, state, AttachmentTransferState.Failed)
                            throw exception
                        }
                    }.also {
                        active[key] = it
                        created = true
                    }
            }
        if (created) {
            deferred.invokeOnCompletion {
                synchronized(lock) {
                    if (active[key] === deferred) {
                        active.remove(key)
                        retireStateIfUnused(key)
                    }
                }
            }
            deferred.start()
        }
        return deferred
    }

    private fun currentStateForRefresh(
        key: String,
        generation: Long,
    ): MutableStateFlow<AttachmentTransferState>? =
        states[key]?.takeUnless {
            active[key]?.isCompleted == false ||
                (terminalGenerations[key] ?: 0L) != generation
        }

    private fun publishTerminalState(
        key: String,
        state: MutableStateFlow<AttachmentTransferState>,
        value: AttachmentTransferState,
    ) {
        synchronized(lock) {
            state.value = value
            terminalGenerations[key] = (terminalGenerations[key] ?: 0L) + 1L
            availabilitySignals.onTerminal(key, value)
        }
    }

    private suspend fun probeAvailability(probe: suspend () -> Boolean): Boolean =
        try {
            probe()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    private fun retireStateIfUnused(key: String) {
        if (observerCounts[key] == 0 && active[key]?.isCompleted != false) {
            observerCounts.remove(key)
            terminalGenerations.remove(key)
            states.remove(key)
            availabilitySignals.retire(key)
        }
    }

    private fun stateFlow(
        key: String,
        initiallyAvailable: Boolean = false,
    ): MutableStateFlow<AttachmentTransferState> =
        states
            .getOrPut(key) {
                MutableStateFlow(
                    if (initiallyAvailable) {
                        AttachmentTransferState.Available
                    } else {
                        AttachmentTransferState.Resolving
                    },
                )
            }.also { state ->
                if (initiallyAvailable && state.value == AttachmentTransferState.Resolving) {
                    state.value = AttachmentTransferState.Available
                }
            }
}

private class AttachmentAvailabilitySignals {
    private val signals = mutableMapOf<String, MutableSharedFlow<Unit>>()
    private val lastCacheAvailability = mutableMapOf<String, Boolean>()

    fun acquire(key: String): MutableSharedFlow<Unit> = signals.getOrPut(key, ::newAvailabilitySignal)

    private fun newAvailabilitySignal(): MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    fun onRefresh(
        key: String,
        available: Boolean?,
    ) {
        val previous = lastCacheAvailability[key]
        if (available != null) {
            lastCacheAvailability[key] = available
        }
        if (available == true && previous != true) {
            signals[key]?.tryEmit(Unit)
        }
    }

    fun onTerminal(
        key: String,
        state: AttachmentTransferState,
    ) {
        when (state) {
            AttachmentTransferState.Available -> {
                lastCacheAvailability[key] = true
                signals[key]?.tryEmit(Unit)
            }
            AttachmentTransferState.NotRetained -> lastCacheAvailability[key] = false
            else -> Unit
        }
    }

    fun retire(key: String) {
        signals.remove(key)
        lastCacheAvailability.remove(key)
    }
}

private suspend fun probeForRefresh(probe: suspend () -> Boolean): Boolean? =
    try {
        probe()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

private fun refreshedState(
    previous: AttachmentTransferState,
    available: Boolean?,
): AttachmentTransferState =
    when {
        available == true -> AttachmentTransferState.Available
        available == null && previous == AttachmentTransferState.Resolving -> AttachmentTransferState.Remote
        available == null -> previous
        previous == AttachmentTransferState.NotRetained -> AttachmentTransferState.NotRetained
        previous == AttachmentTransferState.Failed -> AttachmentTransferState.Failed
        else -> AttachmentTransferState.Remote
    }

private fun attachmentStateAfterRetention(retained: Boolean): AttachmentTransferState =
    if (retained) AttachmentTransferState.Available else AttachmentTransferState.NotRetained
