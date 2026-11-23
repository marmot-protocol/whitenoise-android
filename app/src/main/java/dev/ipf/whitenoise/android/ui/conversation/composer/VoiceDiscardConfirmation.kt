package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

/** Takes at least this long ask before review Discard deletes them; shorter takes are cheap to record again. */
internal const val VOICE_DISCARD_CONFIRM_MS = 10_000L

/** Asks before review Discard deletes a long unsent take. */
@Composable
@Suppress("FunctionNaming")
internal fun VoiceDiscardConfirmation(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.voice_discard_title),
        message = stringResource(R.string.voice_discard_message),
        confirmLabel = stringResource(R.string.discard),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        dismissLabel = stringResource(R.string.cancel),
    )
}
