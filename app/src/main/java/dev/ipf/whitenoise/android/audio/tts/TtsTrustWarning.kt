package dev.ipf.whitenoise.android.audio.tts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.TtsWarningPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog

fun requiresTtsTrustWarning(
    enginePackage: String,
    trust: EngineTrust,
    preferences: TtsWarningPreferences,
): Boolean =
    trust == EngineTrust.Unknown &&
        enginePackage.isNotBlank() &&
        !preferences.hasAcknowledged(enginePackage)

/** Explicit speech requests can obtain consent; background automatic starts only fail closed. */
@Composable
internal fun rememberTtsTrustAction(appState: WhiteNoiseAppState): (() -> Unit) -> Unit {
    val account = appState.activeAccountRef
    var pending by remember(appState, account) { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    pending?.let { (engine, action) ->
        TtsTrustWarningDialog(
            onProceed = {
                pending = null
                // Never transfer a warning acknowledgement to a replacement engine or account.
                if (appState.activeAccountRef == account && appState.ttsPlaybackEnginePackage == engine) {
                    appState.ttsWarningPreferences.acknowledge(engine)
                    action()
                }
            },
            onDismiss = { pending = null },
        )
    }
    return { action ->
        val engine = appState.ttsEngineRequiringConsent()
        if (engine == null) action() else pending = engine to action
    }
}

/**
 * Reusable first-use warning for Unknown engines, shared by selection and explicit playback requests.
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
