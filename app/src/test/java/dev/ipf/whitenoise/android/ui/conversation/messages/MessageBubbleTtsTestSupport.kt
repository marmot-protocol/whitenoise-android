package dev.ipf.whitenoise.android.ui.conversation.messages

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.MutableState
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.TtsEngineInfo
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Marks a local engine usable so bubbles mount their speak-aloud and text double-tap affordances. */
internal fun WhiteNoiseAppState.forceUsableTtsResolutionForTests() {
    val delegateField = WhiteNoiseAppState::class.java.getDeclaredField("ttsResolution\$delegate")
    delegateField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val delegate = delegateField.get(this) as MutableState<TtsResolutionResult?>
    delegate.value =
        TtsResolutionResult(
            status = TextToSpeech.SUCCESS,
            engines = listOf(TtsEngineInfo("com.test.tts", "Test TTS", EngineTrust.Local)),
            defaultEnginePackage = "com.test.tts",
            handle = null,
        )
}
