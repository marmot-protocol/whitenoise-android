package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decrypted voice and video copies the conversation materializes for playback. Removed attachments must not keep
 * playing from here, and both directories honour in-use holds. Shared-reader copies are left to their age-based
 * janitor, since another app may still be reading them.
 */
private val TemporaryAttachmentDirectories = listOf(MediaCacheDirs.VOICE, MediaCacheDirs.VIDEO)

/** Bytes held in Android's temporary decrypted copies: the encrypted disk cache and the playback directories. */
internal suspend fun WhiteNoiseAppState.temporaryAttachmentCopyBytes(): Long =
    withContext(Dispatchers.IO) {
        val disk = runCatchingCancellable { diskMediaCache.residentBytes() }.getOrDefault(0L)
        disk + TemporaryAttachmentDirectories.sumOf { directoryBytes(File(appContext.cacheDir, it)) }
    }

/** Deletes playback copies that no player, save or share currently holds. */
internal suspend fun WhiteNoiseAppState.clearTemporaryAttachmentFiles() {
    withContext(Dispatchers.IO) {
        TemporaryAttachmentDirectories.forEach { name ->
            runCatchingCancellable { AttachmentPlaintextCache.clearUnheldFiles(File(appContext.cacheDir, name)) }
        }
    }
}

/**
 * Drops every decrypted attachment copy: both in-memory tiers, the encrypted disk cache and the playback files.
 * Attachments that are still retained decrypt again on their next view, while removed ones cannot keep rendering.
 */
internal suspend fun WhiteNoiseAppState.clearDecryptedAttachmentCopies() {
    withContext(Dispatchers.Main.immediate) { mediaMemoryCacheKeysSnapshot().forEach(::removeMediaMemoryCacheEntry) }
    withContext(Dispatchers.IO) {
        AttachmentCachePublication.onWipeStarted()
        try {
            runCatchingCancellable { diskMediaCache.clear() }
        } finally {
            AttachmentCachePublication.onWipeFinished()
        }
    }
    clearTemporaryAttachmentFiles()
}

/** Drops the decrypted copies of exactly [items], in memory and on disk. */
internal suspend fun WhiteNoiseAppState.evictAttachmentCopies(
    account: String,
    items: List<StoredAttachment>,
) {
    val keys = items.map { mediaCacheKey(account, it.groupIdHex, it.messageIdHex, it.attachmentIndex) }
    withContext(Dispatchers.Main.immediate) { keys.forEach(::removeMediaMemoryCacheEntry) }
    withContext(Dispatchers.IO) { keys.forEach { runCatchingCancellable { diskMediaCache.remove(it) } } }
}

/** Sums the sizes of the files directly inside [directory]. */
private fun directoryBytes(directory: File): Long {
    val files = directory.listFiles() ?: return 0L
    return files.filter(File::isFile).sumOf { it.length() }
}
