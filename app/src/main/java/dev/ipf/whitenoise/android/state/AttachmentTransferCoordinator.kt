package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    Cancelled,
}

/** True while a transfer is queued or running and can still be cancelled. */
internal fun AttachmentTransferState.isTransferInProgress(): Boolean =
    when (this) {
        AttachmentTransferState.Resolving,
        AttachmentTransferState.Downloading,
        -> true
        AttachmentTransferState.Remote,
        AttachmentTransferState.Available,
        AttachmentTransferState.NotRetained,
        AttachmentTransferState.Failed,
        AttachmentTransferState.Cancelled,
        -> false
    }

/**
 * Marks a cancellation the user asked for, so the transfer owner can publish
 * [AttachmentTransferState.Cancelled] while scope teardown keeps restoring the
 * pre-download state.
 */
internal class AttachmentTransferCancelledByUserException : CancellationException(CANCELLED_BY_USER)

private const val CANCELLED_BY_USER = "attachment transfer cancelled by user"

private fun cancelledByUser(cause: Throwable?): Boolean =
    generateSequence(cause) { it.cause }
        .any { it is AttachmentTransferCancelledByUserException }

/**
 * Owns the UI-facing lifecycle of attachment downloads for one conversation.
 *
 * Callers may request the same attachment from auto-download and tap-to-open
 * paths, but only one coordinator-owned [Deferred] performs the work and owns
 * state transitions. Awaiting callers can disappear without cancelling that
 * owner; the underlying app-level download can still finish and publish to the
 * encrypted cache.
 */
@Suppress("TooManyFunctions") // Cohesive single-flight owner for one attachment transfer lifecycle.
internal class AttachmentTransferCoordinator(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val states = mutableMapOf<String, MutableStateFlow<AttachmentTransferState>>()
    private val availabilitySignals = AttachmentAvailabilitySignals()
    private val active = mutableMapOf<String, Deferred<ByteArray>>()
    private val terminalLifetimes = mutableMapOf<String, StalenessGuard>()
    private val refreshLifetimes = mutableMapOf<String, StalenessGuard>()
    private val observerCounts = mutableMapOf<String, Int>()

    private data class RefreshClaim(
        val terminalLifetime: StalenessGuard,
        val terminalToken: Long,
        val refreshLifetime: StalenessGuard,
        val refreshToken: Long,
    )

    fun acquireState(
        key: String,
        initiallyAvailable: Boolean,
    ): StateFlow<AttachmentTransferState> =
        synchronized(lock) {
            observerCounts[key] = (observerCounts[key] ?: 0) + 1
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
        val waiter = synchronized(lock) { availabilitySignals.register(key) }
        try {
            waiter.await()
        } finally {
            synchronized(lock) {
                availabilitySignals.unregister(key, waiter)
                retireStateIfUnused(key)
            }
        }
    }

    /** Refreshes availability without disturbing a download already in flight. */
    suspend fun refresh(
        key: String,
        probe: suspend () -> Boolean,
    ) {
        val claim =
            synchronized(lock) {
                val terminalLifetime = terminalLifetime(key)
                val refreshLifetime = refreshLifetime(key)
                RefreshClaim(
                    terminalLifetime = terminalLifetime,
                    terminalToken = terminalLifetime.capture(),
                    refreshLifetime = refreshLifetime,
                    refreshToken = refreshLifetime.advance(),
                )
            }
        val available = probeForRefresh(probe)
        synchronized(lock) {
            // A newer cache probe supersedes this result. In particular, a
            // slow cold miss must not demote a later authenticated L2 hit and
            // transiently reopen the automatic-download path. Guard identity
            // also rejects a probe from a retired lifecycle after this key is
            // reopened and its numeric tokens begin again.
            if (
                refreshLifetimes[key] !== claim.refreshLifetime ||
                !claim.refreshLifetime.isCurrent(claim.refreshToken)
            ) {
                return
            }
            // The cache probe runs outside the lock. A transfer may finish and
            // publish a newer terminal state while the probe is suspended, so
            // never let that stale result overwrite the completion state.
            val state = currentStateForRefresh(key, claim.terminalLifetime, claim.terminalToken) ?: return
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
                                cancellationTerminalState(cancellation, stateBeforeDownload),
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
            deferred.invokeOnCompletion { cause ->
                synchronized(lock) {
                    // A user cancel that lands before the lazy owner body runs
                    // never reaches the catch above, so publish here too. The
                    // in-progress guard keeps this idempotent and stops it from
                    // overwriting a terminal state the owner already published.
                    if (cancelledByUser(cause)) {
                        states[key]
                            ?.takeIf { it.value.isTransferInProgress() }
                            ?.let { publishTerminalState(key, it, AttachmentTransferState.Cancelled) }
                    }
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

    /**
     * Cancels the transfer for [key] on the user's behalf.
     *
     * A live owner is cancelled with the user marker so its cancellation branch
     * publishes [AttachmentTransferState.Cancelled]; a key that is queued
     * without a live owner is published directly so the fencing generation
     * still advances and a late refresh cannot reopen it. A transfer that has
     * already published a terminal state wins the race and is left alone.
     *
     * MDK's `download_media` is all-or-nothing today (marmot-protocol/mdk#1437),
     * so this detaches the UI and the durable intent. A network fetch already in
     * flight may still complete and publish to the encrypted cache, which
     * [refresh] then surfaces as [AttachmentTransferState.Available].
     */
    fun cancel(key: String) {
        val owner =
            synchronized(lock) {
                active[key]?.takeUnless { it.isCompleted }
                    ?: run {
                        states[key]
                            ?.takeIf { it.value.isTransferInProgress() }
                            ?.let { publishTerminalState(key, it, AttachmentTransferState.Cancelled) }
                        return
                    }
            }
        owner.cancel(AttachmentTransferCancelledByUserException())
    }

    /** Returns mutable state only when no newer terminal publication superseded the probe. */
    private fun currentStateForRefresh(
        key: String,
        terminalLifetime: StalenessGuard,
        terminalToken: Long,
    ): MutableStateFlow<AttachmentTransferState>? =
        states[key]?.takeUnless {
            active[key]?.isCompleted == false ||
                terminalLifetimes[key] !== terminalLifetime ||
                !terminalLifetime.isCurrent(terminalToken)
        }

    /** Publishes a terminal transfer result and invalidates every suspended cache probe. */
    private fun publishTerminalState(
        key: String,
        state: MutableStateFlow<AttachmentTransferState>,
        value: AttachmentTransferState,
    ) {
        synchronized(lock) {
            state.value = value
            terminalLifetime(key).advance()
            availabilitySignals.onTerminal(key, value)
        }
    }

    /** Converts a non-cancellation cache probe failure into an unavailable result. */
    private suspend fun probeAvailability(probe: suspend () -> Boolean): Boolean =
        try {
            probe()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    /** Removes per-key state and its guards after both owners and observers have departed. */
    private fun retireStateIfUnused(key: String) {
        if (
            observerCounts[key] == 0 &&
            active[key]?.isCompleted != false &&
            !availabilitySignals.hasWaiters(key)
        ) {
            observerCounts.remove(key)
            terminalLifetimes.remove(key)
            refreshLifetimes.remove(key)
            states.remove(key)
            availabilitySignals.retire(key)
        }
    }

    /** Returns the retained per-key state, seeding cache availability only on first access. */
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

    /** Returns the per-attachment fence for transfer terminal publications. */
    private fun terminalLifetime(key: String): StalenessGuard = terminalLifetimes.getOrPut(key, ::StalenessGuard)

    /** Returns the per-attachment fence for cache availability probes. */
    private fun refreshLifetime(key: String): StalenessGuard = refreshLifetimes.getOrPut(key, ::StalenessGuard)
}

internal class AttachmentAvailabilitySignals {
    private val waiters = mutableMapOf<String, MutableSet<CompletableDeferred<Unit>>>()
    private val lastCacheAvailability = mutableMapOf<String, Boolean>()

    fun register(key: String): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { waiter ->
            waiters.getOrPut(key, ::mutableSetOf).add(waiter)
        }

    fun unregister(
        key: String,
        waiter: CompletableDeferred<Unit>,
    ) {
        waiters[key]?.let { registered ->
            registered.remove(waiter)
            if (registered.isEmpty()) {
                waiters.remove(key)
            }
        }
    }

    fun hasWaiters(key: String): Boolean = waiters[key].isNullOrEmpty().not()

    fun onRefresh(
        key: String,
        available: Boolean?,
    ) {
        val previous = lastCacheAvailability[key]
        if (available != null) {
            lastCacheAvailability[key] = available
        }
        if (available == true && previous != true) {
            completeWaiters(key)
        }
    }

    fun onTerminal(
        key: String,
        state: AttachmentTransferState,
    ) {
        when (state) {
            AttachmentTransferState.Available -> {
                lastCacheAvailability[key] = true
                completeWaiters(key)
            }
            AttachmentTransferState.NotRetained -> lastCacheAvailability[key] = false
            else -> Unit
        }
    }

    fun retire(key: String) {
        waiters.remove(key)?.forEach { it.cancel() }
        lastCacheAvailability.remove(key)
    }

    private fun completeWaiters(key: String) {
        waiters.remove(key)?.forEach { it.complete(Unit) }
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

private fun cancellationTerminalState(
    cancellation: CancellationException,
    stateBeforeDownload: AttachmentTransferState,
): AttachmentTransferState =
    when {
        cancelledByUser(cancellation) -> AttachmentTransferState.Cancelled
        stateBeforeDownload == AttachmentTransferState.Available -> AttachmentTransferState.Available
        else -> AttachmentTransferState.Remote
    }

private fun refreshedState(
    previous: AttachmentTransferState,
    available: Boolean?,
): AttachmentTransferState =
    when {
        // A verified cache publication that won the cancel race is still the
        // truthful answer, and marmot-protocol/whitenoise-android#2045 allows it.
        available == true -> AttachmentTransferState.Available
        available == null && previous == AttachmentTransferState.Resolving -> AttachmentTransferState.Remote
        available == null -> previous
        previous == AttachmentTransferState.NotRetained -> AttachmentTransferState.NotRetained
        previous == AttachmentTransferState.Failed -> AttachmentTransferState.Failed
        previous == AttachmentTransferState.Cancelled -> AttachmentTransferState.Cancelled
        else -> AttachmentTransferState.Remote
    }

private fun attachmentStateAfterRetention(retained: Boolean): AttachmentTransferState =
    if (retained) AttachmentTransferState.Available else AttachmentTransferState.NotRetained
