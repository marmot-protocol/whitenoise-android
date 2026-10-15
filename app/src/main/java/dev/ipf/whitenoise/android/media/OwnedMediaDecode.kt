package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Cancelling the delivery of decoded pixels also releases any already-created native session. */
internal suspend fun <T : Any> decodeOwnedMedia(
    decode: () -> T?,
    discard: (T) -> Unit,
): T? {
    var produced: T? = null
    try {
        return runInterruptible(Dispatchers.IO) { decode().also { produced = it } }
    } catch (cancelled: CancellationException) {
        produced?.let(discard)
        throw cancelled
    }
}
