package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Waits for the encrypted host copy before fixture teardown; an L1 hit alone cannot survive process restart. */
internal suspend fun awaitFixtureHostPublication(
    state: WhiteNoiseAppState,
    request: AttachmentTransferRequest,
    expected: ByteArray,
): Boolean {
    val key = request.cacheKey()
    state.outgoingAttachmentCachePublications.await(key)
    return withContext(Dispatchers.IO) { state.diskMediaCache.get(key)?.contentEquals(expected) == true }
}
