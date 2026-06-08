package dev.ipf.darkmatter.state

import kotlin.coroutines.cancellation.CancellationException

/**
 * Rethrow [throwable] when it is a coroutine cancellation, so structured
 * concurrency is preserved and a cancelled operation (screen rotation,
 * navigating away, an account switch) is never reported to the user as a
 * spurious error toast or a stuck "Failed" bubble. No-op for any other
 * throwable, so callers fall through to their normal error handling.
 */
internal fun rethrowIfCancellation(throwable: Throwable) {
    if (throwable is CancellationException) throw throwable
}
