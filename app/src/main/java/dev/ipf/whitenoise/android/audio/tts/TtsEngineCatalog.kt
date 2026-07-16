package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech

/**
 * Thin platform seam for [TextToSpeech] engine enumeration so unit tests can
 * supply deterministic engine lists without constructing real instances.
 */
interface TtsEngineCatalog {
    fun installedEngines(tts: TextToSpeech): List<TextToSpeech.EngineInfo>

    fun defaultEnginePackage(tts: TextToSpeech): String?

    /**
     * Package Android actually connected for this [TextToSpeech] instance, when
     * the public API can report it. Null means the active package cannot be
     * verified (for example after a silent fallback bind).
     */
    fun connectedEnginePackage(
        tts: TextToSpeech,
        requestedPackage: String?,
    ): String?
}

object AndroidTtsEngineCatalog : TtsEngineCatalog {
    override fun installedEngines(tts: TextToSpeech): List<TextToSpeech.EngineInfo> = tts.engines

    override fun defaultEnginePackage(tts: TextToSpeech): String? = tts.defaultEngine

    override fun connectedEnginePackage(
        tts: TextToSpeech,
        requestedPackage: String?,
    ): String? = null
}
