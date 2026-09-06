package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps one process-owned bootstrap attempt alive across UI timeout/retry cycles. */
internal class BootstrapAttemptCoordinator {
    private val lock = Mutex()
    private var attempt: Deferred<Unit>? = null

    suspend fun currentOrStart(start: () -> Deferred<Unit>): Deferred<Unit> =
        lock.withLock {
            attempt?.takeIf { it.isActive } ?: start().also { attempt = it }
        }
}

/** Owns one live Marmot runtime and terminally closes failed instances before replacement. */
internal class BootstrapRuntimeCoordinator<T : Any> {
    private val lock = Mutex()
    private var runtime: T? = null
    private var started = false
    private var initializationFailure: Throwable? = null

    @Suppress("TooGenericExceptionCaught") // Startup and cleanup failures must leave the runtime in a safe state.
    suspend fun open(
        construct: suspend () -> T,
        configure: suspend (T) -> Unit,
        start: suspend (T) -> Unit,
        closeAfterFailure: suspend (T) -> Unit,
    ): T =
        lock.withLock {
            initializationFailure?.let { throw it }
            val opened = runtime ?: construct().also { runtime = it }
            if (!started) {
                try {
                    configure(opened)
                    start(opened)
                    started = true
                } catch (failure: Throwable) {
                    try {
                        withContext(NonCancellable) { closeAfterFailure(opened) }
                        runtime = null
                    } catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                        initializationFailure = failure
                    }
                    throw failure
                }
            }
            opened
        }
}
