package dev.ipf.darkmatter.state

import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class CancellationHandlingTest {
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
