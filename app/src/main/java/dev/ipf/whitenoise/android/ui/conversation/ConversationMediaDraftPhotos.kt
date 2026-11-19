@file:Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.

package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.editor.DraftBackedPhoto
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.PhotoEditorRenderer
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoPreview
import dev.ipf.whitenoise.android.ui.conversation.media.PreparedPhotoQuality
import dev.ipf.whitenoise.android.ui.conversation.media.photoApprovalOutputQuality
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Materializes native draft bytes only while their composer is visible. */
internal fun materializeDraftAttachment(
    context: Context,
    attachment: MessageDraftAttachmentFfi,
): Uri? =
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
        val file = File(directory, "$stableName$safeExtension")
        file.writeBytes(attachment.plaintext)
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
            MediaPipeline.safeDisplayName(attachment.fileName),
        )
    }.getOrNull()

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
        preparedPhotos.mapValues { (_, photo) ->
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
