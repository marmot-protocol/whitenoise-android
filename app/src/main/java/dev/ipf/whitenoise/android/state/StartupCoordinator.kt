package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps one process-owned bootstrap attempt alive across UI timeout/retry cycles. */
internal class BootstrapAttemptCoordinator {
    private val lock = Mutex()
    private var attempt: Deferred<Unit>? = null

    suspend fun currentOrStart(start: () -> Deferred<Unit>): Deferred<Unit> =
        lock.withLock {
            attempt?.takeIf { it.isActive } ?: start().also { attempt = it }
        }
}

/** Constructs and starts the Marmot runtime at most once after a successful start. */
internal class BootstrapRuntimeCoordinator<T : Any> {
    private val lock = Mutex()
    private var runtime: T? = null
    private var started = false

    @Suppress("TooGenericExceptionCaught") // Every configure/start throwable invalidates the cached runtime.
    suspend fun open(
        construct: suspend () -> T,
        configure: suspend (T) -> Unit,
        start: suspend (T) -> Unit,
    ): T =
        lock.withLock {
            val opened = runtime ?: construct().also { runtime = it }
            if (!started) {
                try {
                    configure(opened)
                    start(opened)
                    started = true
                } catch (failure: Throwable) {
                    runtime = null
                    throw failure
                }
            }
            opened
        }
}

/** Rejects deferred unread work once a newer account snapshot supersedes it. */
internal class StartupUnreadRevisionGuard(
    private val expectedRevision: Long,
    private val currentRevision: () -> Long,
) {
    fun isCurrent(): Boolean = startupUnreadRefreshIsCurrent(expectedRevision, currentRevision())
}
