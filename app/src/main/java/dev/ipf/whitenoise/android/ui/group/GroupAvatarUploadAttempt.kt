package dev.ipf.whitenoise.android.ui.group

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.core.DiagnosticErrorMetadata
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import kotlinx.coroutines.CancellationException

/** The editor-owned boundary an avatar attempt failed at, reported as a fixed operation code. */
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
    /**
     * Whether MDK refused the upload destination before sending the photo (#2899).
     *
     * At the pinned MarmotKit, `upload_profile_image` raises `InvalidMediaReference` only from
     * `UnsafeMediaFetch` — an unsafe Blossom URL or a non-public resolved address (UniFFI
     * `errors.rs`). Its other failures (bytes/type checks, DNS, HTTP status, descriptor and hash
     * validation) are `BlobStore` and cross the FFI as `Runtime`, and signer failures keep their
     * own variants. The type alone is therefore the refusal signal, and the free-text detail is
     * never parsed. Do not infer anything about the image bytes from it, or weaken MDK's
     * destination policy to retry it.
     */
    val destinationRefused =
        stage == GroupAvatarUploadStage.Upload && cause is MarmotKitException.InvalidMediaReference
    override val diagnosticErrorCode =
        if (destinationRefused) "MEDIA_DESTINATION_REFUSED" else DiagnosticFormatter.errorCode(cause)
    override val diagnosticTechnicalDetail = "phase=${stage.name} elapsed_ms=${elapsedMillis.coerceAtLeast(0)}"
}

/** Android editor ownership around MDK calls; all transport, validation and commits stay in MDK. */
internal class GroupAvatarUploadAttempt(
    private val isCurrent: () -> Boolean,
    private val clockMillis: () -> Long,
) {
    /**
     * Prepares, uploads, validates and publishes one avatar, stopping at the first failed or stale stage.
     *
     * [finished] runs once whenever the attempt ends — success, `false`, a stale owner or a failure —
     * but not on cancellation, so saved crop-recovery evidence is cleared exactly when the attempt
     * reached an outcome and survives only a genuine interruption.
     */
    suspend fun run(
        prepare: suspend () -> ImageUploadDraft,
        upload: suspend (ImageUploadDraft) -> String,
        publish: suspend (String) -> Boolean,
        finished: () -> Unit = {},
    ): Boolean {
        var cancelled = false
        try {
            return runStages(prepare, upload, publish)
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } finally {
            if (!cancelled) finished()
        }
    }

    /** Runs the stages in order, wrapping any failure with the stage and its elapsed time. */
    private suspend fun runStages(
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

/** The failure title: preparation failures blame the image, every later stage blames the upload. */
internal fun groupAvatarFailureTitle(stage: GroupAvatarUploadStage?): Int =
    if (stage == GroupAvatarUploadStage.Prepare) {
        dev.ipf.whitenoise.android.R.string.toast_couldnt_prepare_image
    } else {
        dev.ipf.whitenoise.android.R.string.toast_couldnt_upload_group_image
    }
