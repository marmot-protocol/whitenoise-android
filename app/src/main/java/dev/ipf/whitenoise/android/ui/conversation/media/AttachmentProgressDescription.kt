package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress

/** Uses native phase names and real body bytes, without presenting ciphertext completion as a ready file. */
@Composable
internal fun nativeProgressDescription(
    progress: NativeAttachmentProgress?,
    host: AttachmentTransferState,
): String? =
    when {
        host == AttachmentTransferState.Available -> null
        host == AttachmentTransferState.Cancelled -> stringResource(R.string.media_download_cancelled)
        progress?.phase == AttachmentTransferStateFfi.POLICY_BLOCKED ->
            stringResource(R.string.media_download_policy_blocked)
        progress?.phase in
            setOf(
                AttachmentTransferStateFfi.UNAVAILABLE,
                AttachmentTransferStateFfi.REMOVED,
                AttachmentTransferStateFfi.PREVIOUSLY_ACQUIRED_UNAVAILABLE,
                AttachmentTransferStateFfi.COMPLETED_UNRETAINED,
            ) -> stringResource(R.string.media_attachment_unavailable)
        host == AttachmentTransferState.Failed || host == AttachmentTransferState.NotRetained -> null
        progress != null -> nativeActiveProgressDescription(progress)
        host == AttachmentTransferState.Resolving -> stringResource(R.string.media_preparing_attachment)
        host == AttachmentTransferState.Downloading -> stringResource(R.string.media_preparing_download)
        else -> null
    }

/** Active native phases describe acquisition independently of the host's plaintext materialization. */
@Composable
private fun nativeActiveProgressDescription(progress: NativeAttachmentProgress): String? =
    when (progress.phase) {
        AttachmentTransferStateFfi.READY -> stringResource(R.string.media_preparing_attachment)
        AttachmentTransferStateFfi.QUEUED -> stringResource(R.string.media_download_queued)
        AttachmentTransferStateFfi.DOWNLOADING ->
            if (progress.fraction != null) {
                val locale = LocalConfiguration.current.locales[0]
                stringResource(
                    R.string.media_download_body_known,
                    formatAttachmentProgressSize(progress.received, locale),
                    formatAttachmentProgressSize(requireNotNull(progress.total), locale),
                )
            } else {
                stringResource(
                    R.string.media_download_body_unknown,
                    formatAttachmentProgressSize(progress.received, LocalConfiguration.current.locales[0]),
                )
            }
        AttachmentTransferStateFfi.VERIFYING_CIPHERTEXT,
        AttachmentTransferStateFfi.VERIFYING_PLAINTEXT,
        -> stringResource(R.string.media_verifying_download)
        AttachmentTransferStateFfi.DECRYPTING -> stringResource(R.string.media_decrypting_download)
        AttachmentTransferStateFfi.RETRY_SCHEDULED -> stringResource(R.string.media_download_retry_scheduled)
        AttachmentTransferStateFfi.PAUSED -> stringResource(R.string.media_download_paused)
        else -> null
    }
