package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Holds one synchronous MarmotKit binding call on the thread that made it — the way a slow native read
 * blocks its IO worker — until the test releases it with a reply. Synchronous bindings never receive a
 * continuation, so a fake cannot suspend them.
 */
internal class HeldNativeCall<T> {
    private val entered = CountDownLatch(1)
    private val reply = CompletableFuture<T>()

    /** Blocks the native caller until [release], failing the call if the test never releases it. */
    fun hold(): T {
        entered.countDown()
        return reply.get(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Suspends until production code has entered the held call, yielding so the caller can reach it. */
    suspend fun awaitEntered() {
        withTimeout(ENTER_TIMEOUT_MILLIS) { while (entered.count > 0) yield() }
    }

    /** Returns [value] from the held call; later releases are ignored. */
    fun release(value: T) {
        reply.complete(value)
    }

    private companion object {
        const val ENTER_TIMEOUT_MILLIS = 5_000L
        const val HOLD_TIMEOUT_SECONDS = 10L
    }
}
