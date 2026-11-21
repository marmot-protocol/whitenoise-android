package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.media.AndroidKeystoreDiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.PersistentDisplayThumbnails
import java.io.File

/**
 * Persistent encrypted originals survive process restart behind the in-memory plaintext tier.
 * Their private cache directory is excluded from backups and can be trimmed by Android.
 * Account and media cleanup invalidate this store together with its display derivatives.
 */
internal fun createOriginalMediaCache(
    context: Context,
    onMutation: () -> Unit,
): DiskByteCache =
    DiskByteCache(
        cacheDir = File(context.cacheDir, MediaCacheDirs.ORIGINALS),
        maxBytes = DISK_MEDIA_CACHE_MAX_BYTES,
        maxEntryBytes = DISK_MEDIA_CACHE_MAX_ENTRY_BYTES,
        maxInMemoryEntryBytes = MEDIA_PLAINTEXT_CACHE_MAX_ENTRY_BYTES,
        keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(),
        onMutation = onMutation,
    )

/** Derivatives have their own encrypted namespace and never satisfy original-availability probes. */
internal fun createDisplayThumbnailCache(context: Context): PersistentDisplayThumbnails =
    PersistentDisplayThumbnails(
        DiskByteCache(
            cacheDir = File(context.cacheDir, MediaCacheDirs.DISPLAY_THUMBNAILS),
            maxBytes = PersistentDisplayThumbnails.MAX_BYTES,
            maxEntryBytes = PersistentDisplayThumbnails.MAX_ENTRY_BYTES.toLong(),
            keyProvider = AndroidKeystoreDiskByteCacheKeyProvider("whitenoise.display_thumbnails.aes_gcm.v1"),
        ),
    )
