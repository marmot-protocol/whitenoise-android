package dev.ipf.whitenoise.android.media

import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import java.io.File
import java.nio.file.Files

/**
 * Names of the `cacheDir` subdirectories that hold decrypted media plaintext.
 *
 * These are privacy-cleanup targets: the conversation UI creates them and the
 * sign-out wipe deletes them. Centralizing the names here keeps the create and
 * wipe sites from drifting apart — a silent rename on one side would otherwise
 * leave decrypted plaintext behind.
 */
object MediaCacheDirs {
    const val ORIGINALS = "decrypted-media"
    const val DISPLAY_THUMBNAILS = "display-thumbnails-v1"
    const val VOICE = "voice_attachments"
    const val VIDEO = "video_attachments"
    const val SHARED = "shared_media"
    const val NATIVE_ATTACHMENT_LEASES = "native_attachment_leases"
    const val COMPOSER_PASTE = "composer_paste"

    /** Private temporary copies of PDFs open in the in-app reader; no FileProvider path exposes them. */
    const val DOCUMENT_READER = "document_reader"

    /** Private downloads of referenced Nostr videos open in the isolated player; no FileProvider path exposes them. */
    const val NOSTR_VIDEO = "nostr_video"

    /** Private snapshots of large picks that the native runtime uploads from a path. */
    const val UPLOAD_SOURCES = "upload_sources"
}

/** Deletes session-owned plaintext directories while keeping shared-reader leases alive. */
internal fun wipeSessionAttachmentPlaintext(
    cacheRoot: File,
    onFailure: (String, Throwable) -> Unit,
) {
    listOf(
        MediaCacheDirs.VOICE,
        MediaCacheDirs.VIDEO,
        MediaCacheDirs.COMPOSER_PASTE,
        MediaCacheDirs.NATIVE_ATTACHMENT_LEASES,
        MediaCacheDirs.UPLOAD_SOURCES,
        MediaCacheDirs.DOCUMENT_READER,
        MediaCacheDirs.NOSTR_VIDEO,
    ).forEach { name ->
        runCatching {
            check(File(cacheRoot, name).deleteRecursively()) {
                "failed to delete session plaintext directory"
            }
        }.onFailure { failure -> onFailure(name, failure) }
    }
}

/**
 * Evicts disposable playback copies while respecting reader and publication holds. Persistent encrypted envelopes and
 * session-owned working files carry no account identifier, so a remaining file keeps an inactive wipe partial.
 */
internal fun clearInactivePlaybackAndCheckRemaining(cacheRoot: File): Boolean {
    listOf(MediaCacheDirs.VOICE, MediaCacheDirs.VIDEO).forEach { name ->
        AttachmentPlaintextCache.clearUnheldFiles(File(cacheRoot, name))
    }
    val exported = setOf(MediaCacheDirs.SHARED, ConversationTranscriptExport.CacheDirName)
    val privateCaches =
        listOf(
            MediaCacheDirs.ORIGINALS,
            MediaCacheDirs.DISPLAY_THUMBNAILS,
            MediaCacheDirs.VOICE,
            MediaCacheDirs.VIDEO,
            MediaCacheDirs.DOCUMENT_READER,
            MediaCacheDirs.NOSTR_VIDEO,
            MediaCacheDirs.NATIVE_ATTACHMENT_LEASES,
            MediaCacheDirs.COMPOSER_PASTE,
            MediaCacheDirs.UPLOAD_SOURCES,
        )
    return (privateCaches + exported).any { name ->
        val root = File(cacheRoot, name).toPath()
        !Files.notExists(root) &&
            Files.walk(root).use { entries ->
                entries.anyMatch { file ->
                    Files.isRegularFile(file) && (name !in exported || isUnownedPrivateShare(file.fileName.toString()))
                }
            }
    }
}
