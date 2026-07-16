package dev.ipf.whitenoise.android.state

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [block] like [runCatching], but never converts coroutine cancellation
 * into a failed [Result].
 *
 * Use this at coroutine boundaries where a failure is logged, displayed, or
 * replaced with a fallback value. Cancellation remains structural instead of
 * relying on every result handler to remember a manual rethrow.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

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
