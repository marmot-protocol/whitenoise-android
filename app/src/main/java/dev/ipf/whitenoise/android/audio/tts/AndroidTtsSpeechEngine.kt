package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Thin adapter that keeps Android framework calls out of the queue state machine. */
internal class AndroidTtsSpeechEngine(
    private val textToSpeech: TextToSpeech,
) : TtsSpeechEngine {
    override fun setLanguage(locale: Locale): Int = textToSpeech.setLanguage(locale)

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
