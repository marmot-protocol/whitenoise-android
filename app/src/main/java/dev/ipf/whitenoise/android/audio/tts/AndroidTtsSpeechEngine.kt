package dev.ipf.whitenoise.android.audio.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Thin adapter that keeps Android framework calls out of the queue state machine. */
internal class AndroidTtsSpeechEngine(
    private val textToSpeech: TextToSpeech,
    private val enginePackage: String = "",
    private val selectedVoice: () -> TtsVoiceKey? = { null },
    private val onVoiceResolved: (TtsVoiceResolution) -> Unit = {},
    private val requireOfflineVoice: Boolean = false,
) : TtsSpeechEngine {
    override val effectiveLocale: Locale? get() = textToSpeech.voice?.locale

    /** Applies the utterance locale and then enforces the saved offline voice policy. */
    override fun setLanguage(locale: Locale): Int {
        val status = textToSpeech.setLanguage(locale)
        if (status < TextToSpeech.LANG_AVAILABLE || enginePackage.isEmpty()) return status
        val voices = textToSpeech.voices.orEmpty().toList()
        val resolution =
            if (voices.isEmpty()) {
                // Some otherwise usable engines synthesize through their default
                // language without exposing a Voice catalog. Clear any prior
                // catalog-derived UI state while preserving the accepted status.
                TtsEngineResolver.resolveVoiceSelection(
                    enginePackage = enginePackage,
                    locale = locale,
                    voices = emptyList(),
                    requestedKey = selectedVoice(),
                )
            } else {
                TtsEngineResolver.applyResolvedVoice(
                    tts = textToSpeech,
                    enginePackage = enginePackage,
                    locale = locale,
                    voices = voices,
                    requestedKey = selectedVoice(),
                )
            }
        onVoiceResolved(resolution)
        // A handle that resolved an offline voice cannot fall back to a network voice, even after consent.
        val offlineVoiceRequired = requireOfflineVoice || voices.isNotEmpty()
        return if (offlineVoiceRequired && resolution.effectiveKey == null) TextToSpeech.LANG_NOT_SUPPORTED else status
    }

    /** Applies the controller's bounded speech-rate preference to the framework engine. */
    override fun setSpeechRate(rate: Float) {
        textToSpeech.setSpeechRate(rate)
    }

    /** Installs one listener that forwards every queue lifecycle callback. */
    override fun setCallbacks(
        onStart: (String?) -> Unit,
        onDone: (String?) -> Unit,
        onError: (String?, Int) -> Unit,
        onRangeStart: (String?, Int, Int, Int) -> Unit,
        onStop: (String?, Boolean) -> Unit,
    ) {
        textToSpeech.setOnUtteranceProgressListener(
            androidTtsProgressListener(onStart, onDone, onError, onRangeStart, onStop),
        )
    }

    /** Detaches the queue listener before an engine is replaced or released. */
    override fun clearCallbacks() {
        textToSpeech.setOnUtteranceProgressListener(null)
    }

    /** Enqueues ordinary speech without an explicit per-utterance volume. */
    override fun speak(
        text: String,
        utteranceId: String,
    ): Int = enqueue(text, utteranceId, null)

    /** Sends Android's per-utterance volume only for explicit media mixing. */
    override fun speak(
        text: String,
        utteranceId: String,
        volume: Float,
    ): Int =
        enqueue(
            text,
            utteranceId,
            Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            },
        )

    private fun enqueue(
        text: String,
        utteranceId: String,
        params: Bundle?,
    ): Int {
        if (requireOfflineVoice && textToSpeech.voice?.isNetworkConnectionRequired != false) {
            return TextToSpeech.ERROR
        }
        return textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    /** Stops framework synthesis for the active queue. */
    override fun stop() {
        textToSpeech.stop()
    }
}

internal fun androidTtsProgressListener(
    onStart: (String?) -> Unit,
    onDone: (String?) -> Unit,
    onError: (String?, Int) -> Unit,
    onRangeStart: (String?, Int, Int, Int) -> Unit,
    onStop: (String?, Boolean) -> Unit,
): UtteranceProgressListener =
    object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            onStart(utteranceId)
        }

        override fun onDone(utteranceId: String?) {
            onDone(utteranceId)
        }

        override fun onStop(
            utteranceId: String?,
            interrupted: Boolean,
        ) {
            onStop(utteranceId, interrupted)
        }

        override fun onRangeStart(
            utteranceId: String?,
            start: Int,
            end: Int,
            frame: Int,
        ) {
            onRangeStart(utteranceId, start, end, frame)
        }

        @Deprecated("Framework fallback without a detailed error code")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(
            utteranceId: String?,
            errorCode: Int,
        ) {
            onError(utteranceId, errorCode)
        }
    }
