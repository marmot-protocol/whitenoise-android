package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.AppThemeMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.ttsEngineChoice

/** Observe only the in-memory prerequisite owners. This does no native/platform discovery or I/O. */
@Composable
internal fun settingsSearchEligibility(appState: WhiteNoiseAppState): SettingsSearchEligibility {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumedRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumedRevision++
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val pushAvailable =
        remember(appState, appState.activeAccountRef, appState.runtimeGeneration, resumedRevision) {
            appState.nativePushCapability().isAvailable
        }
    val mediaMix by appState.ttsMediaMixPreferences.state.collectAsState()
    val dictation by appState.conversationDictationPreferences.state.collectAsState()
    return settingsSearchEligibilitySnapshot(
        appState,
        pushAvailable,
        mediaMix.enabled,
        dictation.finishAfterSilenceMillis != null,
    )
}

/** Fresh activation snapshot reads only existing in-memory state; it does not discover capabilities. */
@Suppress("CyclomaticComplexMethod") // One audited snapshot of independent UI prerequisites.
internal fun settingsSearchEligibilitySnapshot(
    appState: WhiteNoiseAppState,
    pushAvailable: Boolean,
    mediaMixEnabled: Boolean = appState.ttsMediaMixPreferences.state.value.enabled,
    dictationAutoFinish: Boolean =
        appState.conversationDictationPreferences.state.value.finishAfterSilenceMillis !=
            null,
): SettingsSearchEligibility {
    val engineChoice = appState.ttsEngineChoice()
    val hidden =
        buildSet {
            if (!(engineChoice.showEngineChooser || engineChoice.engines.size > 1) ||
                engineChoice.engines.isEmpty()
            ) {
                add("TextToSpeech.engine")
            }
            if (!showTtsVoicePicker(appState.ttsVoiceResolution)) add("TextToSpeech.voice")
            if (!mediaMixEnabled) add("TextToSpeech.mix_volume")
            if (appState.appLockCredentialAvailable) add("Privacy.security_settings")
            if (!(appState.appLockCredentialAvailable && appState.requireAppUnlock)) add("Privacy.auto_lock")
            if (!dictationAutoFinish) add("Dictation.result")
            if (appState.activeAccount?.localSigning != true) {
                add("Account.private_key")
                add("AccountKeys.copy_private_key")
                add("AccountKeys.export_encrypted")
                add("AccountKeys.export_raw")
            }
            if (appState.accounts.size != 1) add("Home.add_profile")
            if (appState.accounts.size <= 1) add("Home.switch_profile")
            if (!BuildConfig.SELF_UPDATE_ENABLED ||
                !appState.appUpdateInfo.isUpdateAvailable
            ) {
                add("Home.update_available")
            }
            if (!BuildConfig.SELF_UPDATE_ENABLED ||
                appState.appUpdateInfo.isUpdateAvailable
            ) {
                add("Home.update_controls")
            }
        }
    return SettingsSearchEligibility(
        hasAccount = appState.activeAccount != null,
        developer = appState.developerMode,
        amoled = appState.themeMode == AppThemeMode.Amoled,
        pushAvailable = pushAvailable,
        hiddenIds = hidden,
    )
}
