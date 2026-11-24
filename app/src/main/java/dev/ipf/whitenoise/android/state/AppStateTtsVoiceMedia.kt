package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsEngineChoice
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey

/** Returns the resolved platform engine list and default used by the settings surface. */
internal fun WhiteNoiseAppState.ttsEngineChoice(): TtsEngineChoice = readAloud.ttsEngineChoice()

/** Resolves the package currently selected by the user/default-engine policy. */
internal fun WhiteNoiseAppState.resolvedTtsEnginePackage(): String? = readAloud.resolvedTtsEnginePackage()

/** Enables the explicitly opted-in active-media mixing mode. */
internal fun WhiteNoiseAppState.setTtsMediaMixEnabled(enabled: Boolean) = readAloud.setTtsMediaMixEnabled(enabled)

/** Applies a bounded mix level at the next queued sentence boundary. */
internal fun WhiteNoiseAppState.setTtsMediaMixVolume(volume: TtsMediaMixVolume) = readAloud.setTtsMediaMixVolume(volume)

/** Provides accessible copy for the latest read-aloud start refusal. */
@StringRes
internal fun WhiteNoiseAppState.ttsStartFailureMessage(): Int = R.string.tts_bar_error

/** Saves a voice for the active engine and safely swaps in a newly configured handle. */
internal fun WhiteNoiseAppState.selectTtsVoice(voice: TtsVoiceKey?) = readAloud.selectTtsVoice(voice)
