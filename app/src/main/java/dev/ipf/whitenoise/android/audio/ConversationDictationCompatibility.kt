package dev.ipf.whitenoise.android.audio

import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.ParcelFileDescriptor
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

/** Reports whether this Android version defines the caller-supplied audio recognizer extras. */
internal fun conversationDictationAudioSourceSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Declares audio White Noise captured itself, so a provider that cannot open the microphone for an
 * app-owned session can still transcribe.
 *
 * The session stays unsegmented on purpose. `EXTRA_SEGMENTED_SESSION` would move results onto
 * [android.speech.RecognitionListener.onSegmentResults], while the controller reads the transcript
 * from `onResults`, so asking for it would deliver audio the app then ignored.
 */
internal fun Intent.withConversationDictationAudioSource(source: ParcelFileDescriptor): Intent =
    apply {
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, CALLER_AUDIO_CHANNEL_COUNT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CALLER_AUDIO_SAMPLE_RATE_HZ)
    }
