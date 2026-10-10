package dev.ipf.whitenoise.android.media

/**
 * Names of the `cacheDir` subdirectories that hold decrypted media plaintext.
 *
 * These are privacy-cleanup targets: the conversation UI creates them and the
 * sign-out wipe deletes them. Centralizing the names here keeps the create and
 * wipe sites from drifting apart — a silent rename on one side would otherwise
 * leave decrypted plaintext behind.
 */
object MediaCacheDirs {
    const val VOICE = "voice_attachments"
    const val VIDEO = "video_attachments"
    const val SHARED = "shared_media"
    const val NATIVE_ATTACHMENT_LEASES = "native_attachment_leases"
    const val COMPOSER_PASTE = "composer_paste"

    /** Private snapshots of large picks that the native runtime uploads from a path. */
    const val UPLOAD_SOURCES = "upload_sources"
}

/** Deletes session-owned plaintext directories while keeping shared-reader leases alive. */
internal fun wipeSessionAttachmentPlaintext(
    cacheRoot: java.io.File,
    onFailure: (String, Throwable) -> Unit,
) {
    listOf(
        MediaCacheDirs.VOICE,
        MediaCacheDirs.VIDEO,
        MediaCacheDirs.COMPOSER_PASTE,
        MediaCacheDirs.NATIVE_ATTACHMENT_LEASES,
        MediaCacheDirs.UPLOAD_SOURCES,
    ).forEach { name ->
        runCatching {
            check(java.io.File(cacheRoot, name).deleteRecursively()) {
                "failed to delete session plaintext directory"
            }
        }.onFailure { failure -> onFailure(name, failure) }
    }
}
