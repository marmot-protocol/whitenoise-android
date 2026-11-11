package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Reads one subscription's events into [onEvent] until the stream ends, and releases the subscription
 * exactly once on every exit: normal end, failure, and cancellation, including a cancellation that
 * lands after [acquire] obtained it but before control returned here (#3718).
 *
 * [acquire] hands the subscription to its `keep` callback inside its own context, so the reference
 * survives even when that context then throws on cancellation. Receiving and releasing run on IO.
 */
internal suspend fun <S : Any, E : Any> streamDiagnosticEvents(
    acquire: suspend (keep: (S) -> Unit) -> Unit,
    next: suspend (S) -> E?,
    release: (S) -> Unit,
    onEvent: (E) -> Unit,
) {
    var acquired: S? = null
    try {
        acquire { acquired = it }
        val subscription = acquired ?: return
        while (true) {
            val event = withContext(Dispatchers.IO) { next(subscription) } ?: break
            onEvent(event)
        }
    } finally {
        // A cancelled effect's own context would refuse entry, so release outside its cancellation.
        acquired?.let { subscription ->
            withContext(NonCancellable + Dispatchers.IO) { runCatching { release(subscription) } }
        }
    }
}
