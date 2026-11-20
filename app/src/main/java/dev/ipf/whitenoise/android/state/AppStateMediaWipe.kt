package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.discardSharedPlaintextWipe
import dev.ipf.whitenoise.android.media.recordSharedPlaintextWipe
import dev.ipf.whitenoise.android.media.wipeSessionAttachmentPlaintext
import dev.ipf.whitenoise.android.media.wipeSharedPlaintext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Fails closed before native deletion if Android cannot persist the share cleanup obligation. */
internal suspend fun WhiteNoiseAppState.prepareSharedPlaintextWipe(accountRef: String): Boolean =
    runCatchingCancellable { recordSharedPlaintextWipe(appContext, accountRef) }.getOrDefault(false)

/** Keeps the staged files when a definitive native outcome leaves the owning account intact. */
internal suspend fun WhiteNoiseAppState.discardRejectedSharedPlaintextWipe(accountRef: String) {
    val discarded = runCatchingCancellable { discardSharedPlaintextWipe(appContext, accountRef) }.getOrDefault(false)
    if (!discarded) appStateDebug { "could not disarm rejected shared plaintext wipe" }
}

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
            val reportDiskFailure: (Throwable) -> Unit = { onFailure("disk media cache", it) }
            runCatching { clearMediaDiskCaches(reportDiskFailure) }.onFailure(reportDiskFailure)
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
