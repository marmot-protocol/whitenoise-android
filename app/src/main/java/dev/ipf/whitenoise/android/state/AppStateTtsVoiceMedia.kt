package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsEngineChoice
import dev.ipf.whitenoise.android.audio.tts.TtsStartFailure
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import kotlinx.coroutines.launch

/** Returns the resolved platform engine list and default used by the settings surface. */
@Suppress("MaxLineLength")
internal fun WhiteNoiseAppState.ttsEngineChoice(): TtsEngineChoice = ttsResolution?.engineChoice() ?: TtsEngineChoice(null, emptyList())

/** Resolves the package currently selected by the user/default-engine policy. */
internal fun WhiteNoiseAppState.resolvedTtsEnginePackage(): String? =
    ttsEngineResolver.preferredEnginePackage(
        engines = ttsEngineChoice().engines,
        defaultPackage = ttsEngineChoice().defaultPackage,
        selectedOverride = ttsEnginePreferences.selectedEngine(),
    )

/** Enables the explicitly opted-in active-media mixing mode. */
internal fun WhiteNoiseAppState.setTtsMediaMixEnabled(enabled: Boolean) {
    ttsMediaMixPreferences.setEnabled(enabled)
}

/** Applies a bounded mix level at the next queued sentence boundary. */
internal fun WhiteNoiseAppState.setTtsMediaMixVolume(volume: TtsMediaMixVolume) {
    ttsMediaMixPreferences.setVolume(volume)
    ttsController.onMediaMixVolumeChanged()
}

/** Provides accessible copy for the latest read-aloud start refusal. */
@StringRes
internal fun WhiteNoiseAppState.ttsStartFailureMessage(): Int =
    when (ttsController.lastStartFailure) {
        TtsStartFailure.MediaNotActive -> R.string.tts_media_mix_no_active_media
        else -> R.string.tts_bar_error
    }

/** Saves a voice for the active engine and safely swaps in a newly configured handle. */
internal fun WhiteNoiseAppState.selectTtsVoice(voice: TtsVoiceKey?) {
    val enginePackage = resolvedTtsEnginePackage() ?: return
    if (voice != null && voice.enginePackage != enginePackage) return
    mutationsScope.launch {
        ttsVoicePreferences.setSelectedVoice(enginePackage, voice)
        selectTtsEngineLocked(enginePackage)
    }
}
