package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.wipeSessionAttachmentPlaintext
import dev.ipf.whitenoise.android.media.wipeSharedPlaintext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Wipes the session plaintext caches. Only a destructive wipe also ends FileProvider access to
 * previously shared files, so ordinary sign-out and leaving a chat preserve active external readers.
 */
internal suspend fun WhiteNoiseAppState.wipeDecryptedMediaFromDisk(wipedAccountRef: String? = null): Boolean =
    withContext(NonCancellable + Dispatchers.IO) {
        var failed = false
        val onFailure: (String, Throwable) -> Unit = { area, failure ->
            failed = true
            appStateDebug { "$area cleanup failed: ${failure.javaClass.simpleName}" }
        }
        AttachmentCachePublication.onWipeStarted()
        try {
            runCatching { clearMediaDiskCaches() }.onFailure { onFailure("disk media cache", it) }
            wipeSessionAttachmentPlaintext(appContext.cacheDir, onFailure)
            if (wipedAccountRef != null) wipeSharedPlaintext(appContext, wipedAccountRef, onFailure)
            runCatching { forwardRequestPersistence.clear() }.onFailure { onFailure("pending forward request", it) }
        } finally {
            AttachmentCachePublication.onWipeFinished()
        }
        failed
    }

/** Maps host cleanup failure to the local-data stage without exposing paths or platform exception text. */
internal fun WhiteNoiseAppState.localMediaFailure(failed: Boolean): WipeFailureItem? =
    if (failed) WipeFailureItem(null, appContext.getString(R.string.wipe_media_cleanup_failed)) else null
