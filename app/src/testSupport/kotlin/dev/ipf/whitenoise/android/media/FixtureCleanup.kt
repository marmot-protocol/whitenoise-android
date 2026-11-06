@file:Suppress("TooGenericExceptionCaught") // Preserve assertion errors as well as exceptions during fixture teardown.

package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs cleanup even after cancellation while retaining the fixture body's original failure. */
internal suspend fun <T> withFixtureCleanup(
    cleanup: suspend () -> Unit,
    body: suspend () -> T,
): T {
    val result = runCatching { body() }
    // Carry cleanup exceptions as values across the context boundary; stack-trace recovery must not copy them.
    val cleanupFailure =
        runCatching {
            withContext(NonCancellable) { runCatching { cleanup() }.exceptionOrNull() }
        }.getOrElse { it }
    val original = result.exceptionOrNull()
    if (cleanupFailure != null) {
        if (original == null) throw cleanupFailure
        if (original !== cleanupFailure) original.addSuppressed(cleanupFailure)
    }
    return result.getOrThrow()
}

/** Attempts every generated-data cleanup step, retaining subsequent failures on the first failure. */
internal suspend fun runFixtureCleanupSteps(vararg steps: suspend () -> Unit) {
    var firstFailure: Throwable? = null
    for (step in steps) {
        try {
            step()
        } catch (failure: Throwable) {
            val original = firstFailure
            if (original == null) {
                firstFailure = failure
            } else if (original !== failure) {
                original.addSuppressed(failure)
            }
        }
    }
    firstFailure?.let { throw it }
}
