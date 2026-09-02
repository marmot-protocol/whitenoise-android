package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.ui.chats.relaysConnectedOnNetworkChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

/** Serializes process-wide native catch-up while coalescing queued successors. */
internal class AccountCatchUpCoordinator(
    private val scope: CoroutineScope,
) {
    private data class Request(
        val key: AccountCatchUpKey,
        val result: CompletableDeferred<Boolean>,
        val block: suspend () -> Boolean,
    )

    private var running: Request? = null
    private var pending: Request? = null
    private val lock = Any()

    /**
     * Shares an exact in-flight request, otherwise retains only the newest
     * successor and starts it after the current native call has settled.
     */
    fun launch(
        key: AccountCatchUpKey,
        block: suspend () -> Boolean,
    ): Deferred<Boolean> {
        var requestToStart: Request? = null
        var superseded: Request? = null
        val result =
            synchronized(lock) {
                running?.takeIf { it.key == key }?.result
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
        superseded?.result?.complete(false)
        requestToStart?.let(::start)
        return result
    }

    /** Runs one request and hands ownership directly to the newest successor. */
    private fun start(request: Request) {
        val job =
            scope.launch {
                runCatchingCancellable { request.block() }
                    .onSuccess { request.result.complete(it) }
                    .onFailure { request.result.completeExceptionally(it) }
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
