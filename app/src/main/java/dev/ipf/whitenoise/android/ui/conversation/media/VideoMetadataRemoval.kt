package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.VideoContent
import dev.ipf.whitenoise.android.media.VideoMetadataScrubber
import dev.ipf.whitenoise.android.media.VideoScrubResult
import dev.ipf.whitenoise.android.state.PendingAttachment
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

// A staged snapshot is owner-read-only once staged, so the scrub lends the owner write bit for the rewrite only.
private const val SNAPSHOT_READ_ONLY = "r--------"
private const val SNAPSHOT_SCRUBBING = "rw-------"

/** Whether [mediaType] names a video, which is how the visual picker and its drafts decide (#3376). */
internal fun isVideoMediaType(mediaType: String): Boolean = mediaType.startsWith("video/", ignoreCase = true)

/** What a Files pick needs before it may be sent. */
internal enum class DocumentVideoAction {
    /** Not a video, or one the policy leaves alone: send the bytes unchanged. */
    Keep,

    /** Remove private metadata first, and refuse the pick if that fails. */
    Scrub,

    /** A video the scrubber cannot clean: never send it. */
    Refuse,
}

/**
 * Decides a Files pick by its [content] first and its [reportedMime] second (#3376). A container with a video track
 * is scrubbed whatever its reported type, so an MP4 reported as `application/octet-stream` is cleaned too. A container
 * without one (for example an `.m4a`) is scrubbed only when reported as video, and an uncleanable video container
 * (for example AVI) is refused only when reported as video, so `audio/ogg` or `.wma` still send unchanged. Anything
 * else, such as a TypeScript file that Android's MIME table reports as `video/mp2t`, is an ordinary document.
 */
internal fun documentVideoAction(
    content: VideoContent,
    reportedMime: String,
): DocumentVideoAction =
    when (content) {
        VideoContent.VideoContainer -> DocumentVideoAction.Scrub
        VideoContent.ContainerWithoutVideo ->
            if (isVideoMediaType(reportedMime)) DocumentVideoAction.Scrub else DocumentVideoAction.Keep
        VideoContent.UnsupportedContainer ->
            if (isVideoMediaType(reportedMime)) DocumentVideoAction.Refuse else DocumentVideoAction.Keep
        VideoContent.Other -> DocumentVideoAction.Keep
    }

/**
 * Applies [documentVideoAction] to freshly read Files [bytes] the caller owns, scrubbing them in place when needed.
 * Returns whether they may be sent.
 */
internal fun removeDocumentVideoMetadataInPlace(
    mediaType: String,
    bytes: ByteArray,
): Boolean =
    when (documentVideoAction(VideoMetadataScrubber.classify(bytes), mediaType)) {
        DocumentVideoAction.Keep -> true
        DocumentVideoAction.Scrub -> VideoMetadataScrubber.scrub(bytes) is VideoScrubResult.Scrubbed
        DocumentVideoAction.Refuse -> false
    }

/**
 * Applies [documentVideoAction] to this staged Files snapshot, scrubbing it in place when needed. Returns whether it
 * may be sent.
 */
internal fun StagedUploadSource.removeDocumentVideoMetadata(mediaType: String): Boolean =
    when (documentVideoAction(VideoMetadataScrubber.classify(file), mediaType)) {
        DocumentVideoAction.Keep -> true
        DocumentVideoAction.Scrub -> scrubVideoMetadata() is VideoScrubResult.Scrubbed
        DocumentVideoAction.Refuse -> false
    }

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

/** Prepared attachments after their videos were checked again: what can be sent, and the keys that were refused. */
internal data class PreparedVideoCheck<K>(
    val ready: Map<K, PendingAttachment>,
    val refused: Set<K>,
)

/**
 * Re-checks prepared visual drafts before a send reuses them, including native drafts that an older app version saved
 * before videos were scrubbed. A draft reported as video is scrubbed or refused, as the visual picker decides. Call
 * off Main.
 */
internal fun <K> removePreparedVideoMetadata(prepared: Map<K, PendingAttachment>): PreparedVideoCheck<K> =
    recheckPrepared(prepared) { attachment ->
        if (isVideoMediaType(attachment.mediaType)) DocumentVideoAction.Scrub else DocumentVideoAction.Keep
    }

/**
 * Re-checks prepared Files drafts before a send reuses them, deciding each by content and reported type exactly as
 * [documentVideoAction] decides a fresh Files pick. Call off Main.
 */
internal fun <K> removePreparedDocumentVideoMetadata(prepared: Map<K, PendingAttachment>): PreparedVideoCheck<K> =
    recheckPrepared(prepared) { attachment ->
        documentVideoAction(VideoMetadataScrubber.classify(attachment.plaintextBytes), attachment.mediaType)
    }

/**
 * Splits [prepared] into attachments that may be sent and keys that were refused, applying [actionFor] to each. A
 * prepared draft's bytes are shared with its native draft, so they are never rewritten in place: a scrub works on a
 * copy, and clean bytes are reused without one. Prepared drafts hold their bytes in memory. A draft reported as video
 * that carries a staged file instead is refused, never rewritten in place. A refused attachment is never replaced by
 * its original bytes.
 */
private fun <K> recheckPrepared(
    prepared: Map<K, PendingAttachment>,
    actionFor: (PendingAttachment) -> DocumentVideoAction,
): PreparedVideoCheck<K> {
    val ready = LinkedHashMap<K, PendingAttachment>(prepared.size)
    val refused = LinkedHashSet<K>()
    prepared.forEach { (key, attachment) ->
        val stagedVideo = attachment.sourceFile != null && isVideoMediaType(attachment.mediaType)
        val checked =
            when (if (stagedVideo) DocumentVideoAction.Refuse else actionFor(attachment)) {
                DocumentVideoAction.Keep -> attachment
                DocumentVideoAction.Refuse -> null
                DocumentVideoAction.Scrub ->
                    VideoMetadataScrubber.scrubbedOrNull(attachment.plaintextBytes)?.let { clean ->
                        if (clean === attachment.plaintextBytes) attachment else attachment.copy(plaintextBytes = clean)
                    }
            }
        if (checked == null) refused += key else ready[key] = checked
    }
    return PreparedVideoCheck(ready, refused)
}
