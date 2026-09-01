package dev.ipf.whitenoise.android.audio

import android.content.Intent
import android.speech.RecognizerIntent

internal const val VOICE_RECOGNITION_SERVICE_SETTING = "voice_recognition_service"

/** Builds the privacy-preferring intent shared by app-owned and provider-Activity recognition. */
internal fun conversationDictationRecognitionIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

/** Builds the provider-Activity intent used by the compatibility fallback surface. */
internal fun conversationDictationRecognitionActivityIntent(): Intent = conversationDictationRecognitionIntent()
