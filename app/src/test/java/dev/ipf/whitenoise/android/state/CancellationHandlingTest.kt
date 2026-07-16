package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class CancellationHandlingTest {
    @Test
    fun runCatchingCancellableRethrowsCancellationException() {
        val cancellation = CancellationException("cancelled")

        val thrown =
            assertThrows(CancellationException::class.java) {
                runCatchingCancellable { throw cancellation }
            }

        assertSame(cancellation, thrown)
    }

    @Test
    fun runCatchingCancellableCapturesNonCancellationFailure() {
        val failure = IllegalStateException("boom")

        val result = runCatchingCancellable<Unit> { throw failure }

        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun runCatchingCancellableReturnsSuccessfulValue() {
        val result = runCatchingCancellable { "ok" }

        assertEquals("ok", result.getOrThrow())
    }

    @Test
    fun rethrowsCancellationException() {
        assertThrows(CancellationException::class.java) {
            rethrowIfCancellation(CancellationException("cancelled"))
        }
    }

    @Test
    fun ignoresNonCancellationThrowables() {
        // Must return normally so callers fall through to their error handling.
        rethrowIfCancellation(RuntimeException("boom"))
        rethrowIfCancellation(IllegalStateException("nope"))
    }
}
