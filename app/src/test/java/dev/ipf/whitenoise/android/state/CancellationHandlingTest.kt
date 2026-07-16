package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
    fun watchAgentTextStreamKeepsTheStreamLoopInsideCancellationSafeWrapper() {
        val body = controllersSource().readText().functionBody("watchAgentTextStream")
        val wrapperStart = body.indexOf("runCatchingCancellable {")
        assertTrue("agent stream must use cancellation-safe result handling", wrapperStart >= 0)

        val wrapperBrace = body.indexOf('{', wrapperStart)
        val wrappedBlock = body.kotlinBlockFrom(wrapperBrace, "watchAgentTextStream cancellation-safe wrapper")
        val failureHandler = body.indexOf(".onFailure", wrapperBrace + wrappedBlock.length)

        assertTrue("agent stream loop must stay inside the cancellation-safe wrapper", "while (true)" in wrappedBlock)
        assertTrue("ordinary failure handling must remain outside the wrapped loop", failureHandler >= 0)
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

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull(File::exists)
            ?: error("Missing Controllers.kt source file")
}
