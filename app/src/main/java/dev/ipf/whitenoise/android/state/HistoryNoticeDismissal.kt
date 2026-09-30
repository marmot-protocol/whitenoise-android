package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException

/**
 * Tries every notice id captured when the user tapped Dismiss. A false result
 * means the id was already gone. Returns the first native failure after trying
 * the other ids; coroutine cancellation stops immediately.
 */
@Suppress("TooGenericExceptionCaught") // Independent notice ids can fail independently.
internal suspend fun dismissHistoryNoticeIds(
    ids: List<String>,
    dismiss: suspend (String) -> Boolean,
): Throwable? {
    var firstFailure: Throwable? = null
    ids.forEach { id ->
        try {
            dismiss(id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure
        }
    }
    return firstFailure
}
