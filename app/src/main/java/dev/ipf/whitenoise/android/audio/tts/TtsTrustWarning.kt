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

/** First-use warning shared by engine selection and private read-aloud. */
@Composable
fun TtsTrustWarningDialog(
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
    ownerKey: Any? = Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.tts_warning_title),
        message = stringResource(R.string.tts_warning_message),
        confirmLabel = stringResource(R.string.tts_warning_proceed),
        onConfirm = onProceed,
        ownerKey = ownerKey,
        onDismiss = onDismiss,
    )
}
