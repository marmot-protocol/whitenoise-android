package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.chats.relaysConnectedOnNetworkChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val CATCH_UP_MAX_SUPERSEDED_REPLACEMENTS = 3

data class ConnectivitySignals(
    val hasValidatedInternet: Boolean = false,
    val relaysConnected: Boolean = true,
)

internal class ConnectivitySignalOwner {
    private val mutableSignals = MutableStateFlow(ConnectivitySignals())
    val signals: StateFlow<ConnectivitySignals> = mutableSignals.asStateFlow()
    private val networkLifetime = StalenessGuard()
    private val lock = Any()

    /** Captures the network identity that an asynchronous probe is validating. */
    fun captureNetworkGeneration(): Long = networkLifetime.capture()

    /** Reports whether a captured probe still belongs to the active network identity. */
    fun isNetworkGenerationCurrent(captured: Long): Boolean = networkLifetime.isCurrent(captured)

    /** Publishes connectivity changes and invalidates probes when validation identity changes. */
    fun update(
        hasValidatedInternet: Boolean? = null,
        relaysConnected: Boolean? = null,
    ) {
        synchronized(lock) {
            val current = mutableSignals.value
            val nextHasValidatedInternet = hasValidatedInternet ?: current.hasValidatedInternet
            if (nextHasValidatedInternet != current.hasValidatedInternet) networkLifetime.advance()
            mutableSignals.value =
                current.copy(
                    hasValidatedInternet = nextHasValidatedInternet,
                    relaysConnected =
                        relaysConnectedOnNetworkChange(
                            isOnline = nextHasValidatedInternet,
                            cached = relaysConnected ?: current.relaysConnected,
                        ),
                )
        }
    }

    /** Invalidates probes after a network callback reports a different network identity. */
    fun noteNetworkIdentityChange() {
        networkLifetime.advance()
    }
}

internal data class AccountCatchUpKey(
    val accountRef: String?,
    val runtimeGeneration: Int,
    val networkGeneration: Long,
)

/** Distinguishes executed catch-up work from a queued request coalesced away. */
internal enum class AccountCatchUpOutcome {
    Succeeded,
    Failed,
    Superseded,
}

/** Result of one catch-up request; superseded requests never reached native work. */
internal data class AccountCatchUpResult(
    val outcome: AccountCatchUpOutcome,
    val startSequence: Long? = null,
) {
    /** True only when the request executed successfully rather than failing or being coalesced. */
    val succeeded: Boolean
        get() = outcome == AccountCatchUpOutcome.Succeeded
}

/**
 * Follows coalesced catch-up work up to a finite replacement budget.
 * Exhaustion becomes a retryable failure so callers yield to their outer backoff policy.
 */
internal suspend fun awaitCatchUpAfterSupersession(
    initial: AccountCatchUpResult,
    maxSupersededReplacements: Int = CATCH_UP_MAX_SUPERSEDED_REPLACEMENTS,
    launchReplacement: suspend () -> AccountCatchUpResult,
): AccountCatchUpResult {
    require(maxSupersededReplacements >= 0) { "maxSupersededReplacements cannot be negative" }
    var result = initial
    repeat(maxSupersededReplacements) {
        if (result.outcome != AccountCatchUpOutcome.Superseded) return result
        result = launchReplacement()
    }
    return if (result.outcome == AccountCatchUpOutcome.Superseded) {
        AccountCatchUpResult(AccountCatchUpOutcome.Failed)
    } else {
        result
    }
}

/**
 * Awaits executed work that began after an external trigger and acknowledges
 * that trigger only after the fresh work succeeds. Coalescing is followed
 * within a finite budget before returning a retryable failure.
 */
internal suspend fun runCatchUpAfterTrigger(
    observedStartSequence: Long,
    launchAfter: (Long) -> Deferred<AccountCatchUpResult>,
    onSucceeded: () -> Unit,
    maxSupersededReplacements: Int = CATCH_UP_MAX_SUPERSEDED_REPLACEMENTS,
): AccountCatchUpResult {
    val result =
        awaitCatchUpAfterSupersession(
            initial = launchAfter(observedStartSequence).await(),
            maxSupersededReplacements = maxSupersededReplacements,
            launchReplacement = { launchAfter(observedStartSequence).await() },
        )
    if (result.succeeded && result.startSequence?.let { it > observedStartSequence } == true) {
        onSucceeded()
    }
    return result
}

/** Serializes process-wide native catch-up while coalescing queued successors. */
internal class AccountCatchUpCoordinator(
    private val scope: CoroutineScope,
) {
    private data class Request(
        val key: AccountCatchUpKey,
        val result: CompletableDeferred<AccountCatchUpResult>,
        val block: suspend () -> Boolean,
        var startSequence: Long? = null,
    ) {
        /** Reports whether this request is guaranteed to start after an observed trigger. */
        fun startsAfter(sequence: Long): Boolean = startSequence?.let { it > sequence } ?: true
    }

    private var running: Request? = null
    private var pending: Request? = null
    private var newestStartSequence = 0L
    private val lock = Any()

    /** Captures the latest native-work start for ordering a later external trigger. */
    fun captureStartSequence(): Long = synchronized(lock) { newestStartSequence }

    /**
     * Shares an exact in-flight request, otherwise retains only the newest
     * successor and starts it after the current native call has settled.
     */
    fun launch(
        key: AccountCatchUpKey,
        block: suspend () -> Boolean,
    ): Deferred<AccountCatchUpResult> = launch(key, mustStartAfter = null, block)

    /** Shares only work that will start after [sequence], otherwise queues a successor. */
    fun launchAfter(
        sequence: Long,
        key: AccountCatchUpKey,
        block: suspend () -> Boolean,
    ): Deferred<AccountCatchUpResult> = launch(key, mustStartAfter = sequence, block)

    /** Shares eligible work or atomically replaces the single queued successor. */
    private fun launch(
        key: AccountCatchUpKey,
        mustStartAfter: Long?,
        block: suspend () -> Boolean,
    ): Deferred<AccountCatchUpResult> {
        var requestToStart: Request? = null
        var superseded: Request? = null
        val result =
            synchronized(lock) {
                running
                    ?.takeIf { request ->
                        request.key == key &&
                            (mustStartAfter == null || request.startsAfter(mustStartAfter))
                    }?.result
                    ?: pending?.takeIf { it.key == key }?.result
                    ?: Request(key, CompletableDeferred(), block)
                        .also { request ->
                            if (running == null) {
                                running = request
                                requestToStart = request
                            } else {
                                superseded = pending
                                pending = request
                            }
                        }.result
            }
        superseded?.result?.complete(AccountCatchUpResult(AccountCatchUpOutcome.Superseded))
        requestToStart?.let(::start)
        return result
    }

    /** Runs one request and hands ownership directly to the newest successor. */
    private fun start(request: Request) {
        val startSequence =
            synchronized(lock) {
                newestStartSequence += 1L
                newestStartSequence.also { request.startSequence = it }
            }
        val job =
            scope.launch {
                runCatchingCancellable { request.block() }
                    .onSuccess { succeeded ->
                        request.result.complete(
                            AccountCatchUpResult(
                                if (succeeded) AccountCatchUpOutcome.Succeeded else AccountCatchUpOutcome.Failed,
                                startSequence = startSequence,
                            ),
                        )
                    }.onFailure { request.result.completeExceptionally(it) }
            }
        job.invokeOnCompletion { cause -> finish(request, cause) }
    }

    /** Completes cancellation and starts the successor outside the coordinator lock. */
    private fun finish(
        request: Request,
        cause: Throwable?,
    ) {
        if (cause != null && !request.result.isCompleted) {
            request.result.completeExceptionally(cause)
        }
        val successor =
            synchronized(lock) {
                if (running !== request) return@synchronized null
                pending.also {
                    running = it
                    pending = null
                }
            }
        successor?.let(::start)
    }
}
