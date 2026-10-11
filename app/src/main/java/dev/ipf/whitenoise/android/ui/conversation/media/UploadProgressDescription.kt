package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.FileUploadPhase
import dev.ipf.whitenoise.android.state.FileUploadProgress

/** Names the upload's current step and, while bytes move, how many of the file's bytes that step has handled. */
@Composable
internal fun uploadProgressDescription(progress: FileUploadProgress): String {
    val locale = LocalConfiguration.current.locales[0]
    val handled = formatAttachmentProgressSize(progress.phaseBytes.toULong(), locale)
    val total = formatAttachmentProgressSize(progress.totalBytes.toULong(), locale)
    return when (progress.phase) {
        FileUploadPhase.PREPARING -> stringResource(R.string.media_upload_preparing_body, handled, total)
        FileUploadPhase.ENCRYPTING -> stringResource(R.string.media_upload_encrypting_body, handled, total)
        FileUploadPhase.UPLOADING -> stringResource(R.string.media_upload_body_known, handled, total)
        FileUploadPhase.SENDING -> stringResource(R.string.sending)
    }
}

/**
 * The short line a file card shows for the upload's step, sized for its single metadata row: the step's
 * name while MDK copies and encrypts, the bytes sent while it uploads, as a download card shows them.
 * [uploadProgressDescription] keeps the full step and bytes for TalkBack.
 */
@Composable
internal fun uploadProgressLabel(progress: FileUploadProgress): String =
    when (progress.phase) {
        FileUploadPhase.PREPARING -> stringResource(R.string.media_upload_preparing)
        FileUploadPhase.ENCRYPTING -> stringResource(R.string.media_upload_encrypting)
        FileUploadPhase.UPLOADING -> {
            val locale = LocalConfiguration.current.locales[0]
            stringResource(
                R.string.media_upload_bytes,
                formatAttachmentProgressSize(progress.phaseBytes.toULong(), locale),
                formatAttachmentProgressSize(progress.totalBytes.toULong(), locale),
            )
        }
        FileUploadPhase.SENDING -> stringResource(R.string.sending)
    }

/**
 * The ring's share of work done, or null while it should keep spinning: before the first byte is counted,
 * and once only the message is left to send and no byte count applies.
 */
internal val FileUploadProgress.ringFraction: Float?
    get() = fraction.takeIf { phase != FileUploadPhase.SENDING && it > 0f }

/**
 * Follows the byte progress of [messageIdHex]'s pending single-file send while [active]. The returned
 * reader is read by the ring and label that draw it, so only they recompose as bytes move. It reads null
 * for albums, for sends held in memory, and once the message is no longer pending.
 */
@Composable
internal fun rememberPendingUploadProgress(
    controller: ConversationController,
    messageIdHex: String,
    active: Boolean,
): () -> FileUploadProgress? {
    val progress =
        remember(controller, messageIdHex, active) {
            if (active) controller.pendingUploadProgress(messageIdHex) else null
        }
    val state: State<FileUploadProgress?>? = progress?.collectAsStateWithLifecycle()
    return remember(state) { { state?.value } }
}
