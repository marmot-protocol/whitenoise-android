package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val active = mutableMapOf<String, Deferred<ByteArray>>()

    fun state(
        key: String,
        initiallyAvailable: Boolean,
    ): StateFlow<AttachmentTransferState> =
        synchronized(lock) {
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
                }.asStateFlow()
        }

    /** Refreshes availability without disturbing a download already in flight. */
    suspend fun refresh(
        key: String,
        probe: suspend () -> Boolean,
    ) {
        val available =
            try {
                probe()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return
            }
        synchronized(lock) {
            if (active[key]?.isCompleted == false) return
            val state = stateFlow(key)
            state.value =
                when {
                    available -> AttachmentTransferState.Available
                    state.value == AttachmentTransferState.NotRetained -> AttachmentTransferState.NotRetained
                    state.value == AttachmentTransferState.Failed -> AttachmentTransferState.Failed
                    else -> AttachmentTransferState.Remote
                }
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
                            check(bytes.isNotEmpty()) { "attachment download returned empty plaintext" }
                            val retained = probeAvailability(availableAfterLoad)
                            state.value =
                                if (retained) {
                                    AttachmentTransferState.Available
                                } else {
                                    AttachmentTransferState.NotRetained
                                }
                            bytes
                        } catch (cancellation: CancellationException) {
                            state.value =
                                if (stateBeforeDownload == AttachmentTransferState.Available) {
                                    AttachmentTransferState.Available
                                } else {
                                    AttachmentTransferState.Remote
                                }
                            throw cancellation
                        } catch (exception: Exception) {
                            state.value = AttachmentTransferState.Failed
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
                    if (active[key] === deferred) active.remove(key)
                }
            }
            deferred.start()
        }
        return deferred
    }

    private suspend fun probeAvailability(probe: suspend () -> Boolean): Boolean =
        try {
            probe()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    @Suppress("MaxLineLength")
    private fun stateFlow(key: String): MutableStateFlow<AttachmentTransferState> = states.getOrPut(key) { MutableStateFlow(AttachmentTransferState.Resolving) }
}
