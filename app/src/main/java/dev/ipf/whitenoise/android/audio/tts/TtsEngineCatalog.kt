package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech

/**
 * Thin platform seam for [TextToSpeech] engine enumeration so unit tests can
 * supply deterministic engine lists without constructing real instances.
 */
interface TtsEngineCatalog {
    fun installedEngines(tts: TextToSpeech): List<TextToSpeech.EngineInfo>

    fun defaultEnginePackage(tts: TextToSpeech): String?
}

object AndroidTtsEngineCatalog : TtsEngineCatalog {
    override fun installedEngines(tts: TextToSpeech): List<TextToSpeech.EngineInfo> = tts.engines

    override fun defaultEnginePackage(tts: TextToSpeech): String? = tts.defaultEngine
}
