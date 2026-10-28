package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.VideoMetadataScrubber
import dev.ipf.whitenoise.android.media.VideoScrubResult
import dev.ipf.whitenoise.android.state.PendingAttachment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// A staged snapshot is owner-read-only once staged; the scrub lends the owner write bit for the rewrite only.
private const val SNAPSHOT_READ_ONLY = "r--------"
private const val SNAPSHOT_SCRUBBING = "rw-------"

/** Whether [mediaType] names a video, the only attachments the metadata scrubber prepares. */
internal fun isVideoMediaType(mediaType: String): Boolean = mediaType.startsWith("video/", ignoreCase = true)

/**
 * Removes private metadata in place from freshly read [bytes] the caller owns, and reports whether they may be sent:
 * true for every non-video [mediaType], false for a video whose metadata cannot be removed.
 */
internal fun removeVideoMetadataInPlace(
    mediaType: String,
    bytes: ByteArray,
): Boolean = !isVideoMediaType(mediaType) || VideoMetadataScrubber.scrub(bytes) is VideoScrubResult.Scrubbed

/**
 * Removes private metadata from this staged snapshot in place (#3376). The size is unchanged, so the budget that
 * admitted the snapshot stays exact. A snapshot whose permissions cannot be changed reads as malformed, and the
 * snapshot is always returned to owner-read-only.
 */
internal fun StagedUploadSource.scrubVideoMetadata(): VideoScrubResult {
    val path = file.toPath()
    return try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(SNAPSHOT_SCRUBBING))
        VideoMetadataScrubber.scrub(file)
    } catch (_: IOException) {
        VideoScrubResult.Malformed
    } finally {
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(SNAPSHOT_READ_ONLY)) }
    }
}

/**
 * This attachment with private video metadata removed, or null when it is a video whose metadata cannot be
 * removed. Non-video attachments are returned unchanged. Bytes are never mutated in place, because a prepared
 * draft shares its array with the native draft; clean bytes are reused without a copy. Call off the main thread.
 */
internal fun PendingAttachment.withVideoMetadataRemoved(): PendingAttachment? {
    val staged = sourceFile
    return when {
        !isVideoMediaType(mediaType) -> this
        staged != null -> this.takeIf { staged.scrubVideoMetadata() is VideoScrubResult.Scrubbed }
        else -> {
            val clean = VideoMetadataScrubber.scrubbedOrNull(plaintextBytes)
            when {
                clean == null -> null
                clean === plaintextBytes -> this
                else -> copy(plaintextBytes = clean)
            }
        }
    }
}

/** Prepared attachments after their videos were checked again: what can be sent, and the keys that were refused. */
internal data class PreparedVideoCheck<K>(
    val ready: Map<K, PendingAttachment>,
    val refused: Set<K>,
)

/**
 * Re-checks every prepared attachment before a send reuses it, including native drafts that an older app version
 * saved before videos were scrubbed. A refused video is never replaced by its original bytes. Call off Main.
 */
internal fun <K> removePreparedVideoMetadata(prepared: Map<K, PendingAttachment>): PreparedVideoCheck<K> {
    val ready = LinkedHashMap<K, PendingAttachment>(prepared.size)
    val refused = LinkedHashSet<K>()
    prepared.forEach { (key, attachment) ->
        val checked = attachment.withVideoMetadataRemoved()
        if (checked == null) refused += key else ready[key] = checked
    }
    return PreparedVideoCheck(ready, refused)
}
