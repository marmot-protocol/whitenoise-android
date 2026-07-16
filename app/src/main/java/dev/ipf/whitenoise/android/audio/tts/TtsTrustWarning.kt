package dev.ipf.whitenoise.android.audio.tts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsWarningPreferences
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

fun requiresTtsTrustWarning(
    enginePackage: String,
    trust: EngineTrust,
    preferences: TtsWarningPreferences,
): Boolean =
    trust == EngineTrust.Unknown &&
        enginePackage.isNotBlank() &&
        !preferences.hasAcknowledged(enginePackage)

/**
 * Reusable first-use trust warning for Unknown TTS engines (#1479). #1481 will
 * gate Speak on this before handing off to #1480.
 */
@Composable
fun TtsTrustWarningDialog(
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.tts_warning_title),
        message = stringResource(R.string.tts_warning_message),
        confirmLabel = stringResource(R.string.tts_warning_proceed),
        onConfirm = onProceed,
        onDismiss = onDismiss,
    )
}
