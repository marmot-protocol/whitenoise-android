@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.editor.DraftBackedPhoto
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.PhotoDraftStageResult
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceFailure
import dev.ipf.whitenoise.android.media.editor.hasInlineBody
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoPreview
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoQuality
import dev.ipf.whitenoise.android.ui.conversation.media.clearMediaTempFiles
import dev.ipf.whitenoise.android.ui.conversation.media.photoApprovalOutputQuality
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** The composer presentation file of one native draft attachment, named stably by its id, or null without one. */
internal fun draftPresentationFile(
    context: Context,
    attachment: MessageDraftAttachmentFfi,
): File? =
    runCatching {
        val directory = File(context.cacheDir, "composer_paste/native_drafts").apply { mkdirs() }
        val safeExtension =
            attachment.fileName
                .substringAfterLast('.', "")
                .lowercase()
                .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
                ?.let { ".$it" }
                .orEmpty()
        val stableName =
            UUID.nameUUIDFromBytes(attachment.id.toByteArray(StandardCharsets.UTF_8)).toString()
        File(directory, "$stableName$safeExtension")
    }.getOrNull()

/** The shelf URI of a presentation [file], shown under [attachment]'s file name. */
internal fun draftPresentationUri(
    context: Context,
    file: File,
    attachment: MessageDraftAttachmentFfi,
): Uri? =
    runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
            MediaPipeline.safeDisplayName(attachment.fileName),
        )
    }.getOrNull()

/**
 * What a send carries for each prepared key: inline bytes, or a reference to the file the draft stores. A stored
 * entry whose size is not known yet is left out, so its send reads the shelf file instead.
 */
internal fun <K> Map<K, DraftPreparedPhoto>.sendableBodies(): Map<K, PendingAttachment> =
    filterValues { photo ->
        photo.attachment.hasInlineBody() || photo.storedBytes > 0L
    }.mapValues { (_, photo) -> photo.pendingAttachment() }

/** Previews from prepared bytes; a file-backed attachment has none and previews from its shelf file instead. */
internal fun preparedPhotoPreviews(
    backedPhotos: Map<String, DraftBackedPhoto>,
    preparedPhotos: Map<String, DraftPreparedPhoto>,
): Map<String, PreparedPhotoPreview> =
    backedPhotos.mapValues { (_, photo) ->
        PreparedPhotoPreview(
            revision = photo.attachmentDigest,
            bytes = photo.attachment.plaintext,
        )
    } +
        preparedPhotos.filterValues { it.attachment.hasInlineBody() }.mapValues { (_, photo) ->
            PreparedPhotoPreview(
                revision = photo.attachmentDigest,
                bytes = photo.attachment.plaintext,
            )
        }

internal fun preparedPhotoQualities(
    backedPhotos: Map<String, DraftBackedPhoto>,
    renderer: PhotoEditorRenderer,
): Map<String, PreparedPhotoQuality> =
    backedPhotos.mapValues { (_, photo) ->
        fun dimensions(quality: MediaQuality): String? =
            renderer.outputPlan(photo.sourceInfo, photo.recipe, quality)?.geometry?.outputSize?.let {
                "${it.width} × ${it.height}"
            }
        PreparedPhotoQuality(
            selectedQuality = photo.quality,
            standardDimensions =
                dimensions(photoApprovalOutputQuality(photo.quality, MediaQuality.Standard)),
            hdDimensions = dimensions(photoApprovalOutputQuality(photo.quality, MediaQuality.High)),
        )
    }

/** Deletes composer presentation copies, clearing every media temp file only when the draft is [empty]. */
internal fun releaseDraftPresentationFiles(
    context: Context,
    empty: Boolean,
) {
    if (empty) {
        clearMediaTempFiles(context)
    } else {
        runCatching { File(context.cacheDir, "composer_paste/native_drafts").deleteRecursively() }
    }
}

/** Picks the shelf description that explains why a staged photo cannot be edited. */
internal fun nonEditablePhotoDescription(
    result: PhotoDraftStageResult.NotEditable,
    messages: PhotoEditorMessages,
): String =
    if (result.reason == PhotoEditorSourceFailure.Animated) {
        messages.animationNotEditable
    } else {
        messages.sourceNotEditable
    }
