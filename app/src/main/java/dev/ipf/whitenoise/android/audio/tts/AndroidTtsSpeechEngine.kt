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
) : TtsSpeechEngine {
    /** Applies the utterance locale and then enforces the saved offline voice policy. */
    override fun setLanguage(locale: Locale): Int {
        val status = textToSpeech.setLanguage(locale)
        if (status < TextToSpeech.LANG_AVAILABLE || enginePackage.isEmpty()) return status
        val voices = textToSpeech.voices.orEmpty().toList()
        // Some otherwise usable engines synthesize through their default language
        // without exposing a Voice catalog. Preserve the framework's accepted
        // language status instead of treating the missing optional catalog as a
        // hard language failure.
        if (voices.isEmpty()) return status
        val resolution =
            TtsEngineResolver.resolveVoiceSelection(
                enginePackage = enginePackage,
                locale = locale,
                voices = voices,
                requestedKey = selectedVoice(),
            )
        val preferred = resolution.preferredVoice
        val effective =
            if (preferred != null && textToSpeech.setVoice(preferred) == TextToSpeech.SUCCESS) {
                preferred
            } else {
                val candidates = TtsEngineResolver.offlineVoiceCandidates(locale, voices)
                candidates
                    .asSequence()
                    .filterNot { it == preferred }
                    .firstOrNull { candidate ->
                        textToSpeech.setVoice(candidate) == TextToSpeech.SUCCESS
                    }
            }
        onVoiceResolved(
            resolution.copy(
                effectiveKey =
                    effective?.let { voice ->
                        TtsVoiceKey(enginePackage, voice.name, voice.locale.toLanguageTag())
                    },
            ),
        )
        return if (effective == null) TextToSpeech.LANG_NOT_SUPPORTED else status
    }

    override fun setSpeechRate(rate: Float) {
        textToSpeech.setSpeechRate(rate)
    }

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

    override fun clearCallbacks() {
        textToSpeech.setOnUtteranceProgressListener(null)
    }

    /** Sends Android's per-utterance volume only for explicit media mixing. */
    override fun speak(
        text: String,
        utteranceId: String,
    ): Int =
        textToSpeech.speak(
            text,
            TextToSpeech.QUEUE_ADD,
            null,
            utteranceId,
        )

    override fun speak(
        text: String,
        utteranceId: String,
        volume: Float,
    ): Int =
        textToSpeech.speak(
            text,
            TextToSpeech.QUEUE_ADD,
            Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
            },
            utteranceId,
        )

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
