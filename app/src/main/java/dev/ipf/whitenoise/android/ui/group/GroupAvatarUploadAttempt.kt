package dev.ipf.whitenoise.android.ui.group

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.DiagnosticErrorMetadata
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.state.runCatchingCancellable

internal enum class GroupAvatarUploadStage(
    val operationCode: String,
) {
    Prepare("GROUP_IMAGE_PREPARE"),
    Upload("GROUP_IMAGE_UPLOAD"),
    ValidateUrl("GROUP_IMAGE_UPLOAD_URL"),
    Publish("GROUP_AVATAR_UPDATE"),
}

/** Only fixed categories and elapsed time are copied; native error details can contain private URLs. */
internal class GroupAvatarUploadFailure(
    val stage: GroupAvatarUploadStage,
    elapsedMillis: Long,
    cause: Exception,
) : Exception("Group avatar ${stage.name} failed", cause),
    DiagnosticErrorMetadata {
    // The public-profile uploader maps UnsafeMediaFetch to InvalidMediaReference. Do not infer
    // anything about image bytes from that error, or weaken MDK's destination policy to retry it.
    val destinationRefused = stage == GroupAvatarUploadStage.Upload && cause is MarmotKitException.InvalidMediaReference
    override val diagnosticErrorCode =
        if (destinationRefused) "MEDIA_DESTINATION_REFUSED" else DiagnosticFormatter.errorCode(cause)
    override val diagnosticTechnicalDetail = "phase=${stage.name} elapsed_ms=${elapsedMillis.coerceAtLeast(0)}"
}

/** Android editor ownership around MDK calls; all transport, validation and commits stay in MDK. */
internal class GroupAvatarUploadAttempt(
    private val isCurrent: () -> Boolean,
    private val clockMillis: () -> Long,
) {
    suspend fun run(
        prepare: suspend () -> ImageUploadDraft,
        upload: suspend (ImageUploadDraft) -> String,
        publish: suspend (String) -> Boolean,
    ): Boolean {
        if (!isCurrent()) return false
        var stage = GroupAvatarUploadStage.Prepare
        var started = clockMillis()
        return runCatchingCancellable {
            val draft = prepare()
            if (!isCurrent()) return@runCatchingCancellable false
            stage = GroupAvatarUploadStage.Upload
            started = clockMillis()
            val uploaded = upload(draft)
            if (!isCurrent()) return@runCatchingCancellable false
            stage = GroupAvatarUploadStage.ValidateUrl
            started = clockMillis()
            val safeUrl = safeAvatarUploadUrl(uploaded)
            stage = GroupAvatarUploadStage.Publish
            started = clockMillis()
            publish(safeUrl)
        }.getOrElse { error ->
            if (error !is Exception) throw error
            throw GroupAvatarUploadFailure(stage, clockMillis() - started, error)
        }
    }
}
