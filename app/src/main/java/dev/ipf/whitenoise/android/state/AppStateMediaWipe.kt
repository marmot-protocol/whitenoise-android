package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wipe the device-side decrypted-media footprint that outlives the
 * in-memory caches: the L2 disk cache (cacheDir/decrypted-media) and the
 * decrypted voice/video plaintext and unsent pasted media that the
 * conversation UI materializes under cacheDir. Used at sign-out, when we
 * treat that footprint as ending with the session. Re-opening a chat after
 * the next sign-in re-downloads from Blossom.
 *
 * Suspends so the sign-out flow can await completion rather than racing a
 * fast re-sign-in against an unfinished wipe. `shared_media` is left to its
 * age-based janitor on purpose: those files can back a live external
 * "open with"/share reader and deleting them out from under it would break
 * an in-progress share.
 */
internal suspend fun WhiteNoiseAppState.wipeDecryptedMediaFromDisk() {
    withContext(Dispatchers.IO) {
        AttachmentCachePublication.onWipeStarted()
        try {
            // Each target holds decrypted plaintext, so wipe them independently
            // and best-effort: a failure in one (IO error, locked file) must not
            // skip the others, and a swallowed failure should still be visible.
            runCatchingCancellable { clearMediaDiskCaches() }
                .onFailure {
                    appStateDebug { "disk media cache wipe failed: ${it.readableMessage()}" }
                }
            dev.ipf.whitenoise.android.media.wipeSessionAttachmentPlaintext(appContext.cacheDir) { name, failure ->
                appStateDebug { "$name plaintext wipe failed: ${failure.readableMessage()}" }
            }
            // The single pending-forward entry can hold the wiped account's
            // message plaintext; a destructive wipe drops it unconditionally.
            runCatchingCancellable { forwardRequestPersistence.clear() }
                .onFailure {
                    appStateDebug { "pending forward request wipe failed: ${it.readableMessage()}" }
                }
        } finally {
            AttachmentCachePublication.onWipeFinished()
        }
    }
}
