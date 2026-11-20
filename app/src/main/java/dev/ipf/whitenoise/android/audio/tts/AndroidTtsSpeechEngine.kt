package dev.ipf.whitenoise.android.audio.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Thin adapter that keeps Android framework calls out of the queue state machine. */
internal class AndroidTtsSpeechEngine(
    private val textToSpeech: TextToSpeech,
    private val enginePackage: String = "",
    private val selectedVoice: () -> TtsVoiceKey? = { null },
    private val onVoiceResolved: (TtsVoiceResolution) -> Unit = {},
    private val requireOfflineVoice: Boolean = false,
    private val onConnectionUncertain: () -> Unit = {},
) : TtsSpeechEngine {
    private val retired = AtomicBoolean()
    private var enforceOfflineVoice = requireOfflineVoice

    override var effectiveLocale: Locale? = null
        private set

    /** Applies voice policy without reusing a framework object which may have silently reconnected. */
    @Suppress("ReturnCount")
    override fun setLanguage(locale: Locale): Int {
        if (retired.get()) return TextToSpeech.LANG_NOT_SUPPORTED
        val status = textToSpeech.setLanguage(locale)
        if (status < TextToSpeech.LANG_AVAILABLE) return retire(status)
        effectiveLocale = locale
        if (enginePackage.isEmpty()) return status
        val voices = textToSpeech.voices ?: return retire(TextToSpeech.LANG_NOT_SUPPORTED)
        val requested = selectedVoice()
        enforceOfflineVoice = requireOfflineVoice || requested != null || voices.isNotEmpty()
        val resolution =
            if (voices.isEmpty()) {
                TtsEngineResolver.resolveVoiceSelection(enginePackage, locale, voices, requested)
            } else {
                TtsEngineResolver.applyResolvedVoice(
                    enginePackage = enginePackage,
                    locale = locale,
                    voices = voices,
                    requestedKey = requested,
                    setVoice = ::selectFrameworkVoice,
                    currentVoice = ::currentFrameworkVoice,
                )
            }
        effectiveLocale = resolution.effectiveKey?.localeTag?.let(Locale::forLanguageTag) ?: locale
        onVoiceResolved(resolution)
        return if (retired.get() || (enforceOfflineVoice && resolution.effectiveKey == null)) {
            TextToSpeech.LANG_NOT_SUPPORTED
        } else {
            status
        }
    }

    /** Treats an ambiguous framework failure as the end of the consented connection. */
    private fun retire(status: Int): Int {
        if (retired.compareAndSet(false, true)) onConnectionUncertain()
        return status
    }

    /** The framework can return its normal error value after reconnecting behind the same object. */
    private fun selectFrameworkVoice(voice: Voice): Int {
        if (retired.get()) return TextToSpeech.ERROR
        val status = textToSpeech.setVoice(voice)
        return if (status == TextToSpeech.SUCCESS) status else retire(status)
    }

    /** Null cannot distinguish an unavailable voice from framework IPC failure. */
    private fun currentFrameworkVoice(): Voice? {
        if (retired.get()) return null
        return textToSpeech.voice ?: run {
            retire(TextToSpeech.ERROR)
            null
        }
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
            androidTtsProgressListener(
                onStart,
                onDone,
                { id, error ->
                    if (error == TextToSpeech.ERROR_SERVICE || error == TextToSpeech.ERROR) retire(error)
                    onError(id, error)
                },
                onRangeStart,
                onStop,
            ),
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

    /** Submits an utterance only while the installed-voice restriction still holds. */
    private fun enqueue(
        text: String,
        utteranceId: String,
        params: Bundle?,
    ): Int {
        if (retired.get()) return TextToSpeech.ERROR
        if ((enforceOfflineVoice || selectedVoice() != null) && currentFrameworkVoice()?.isInstalledOffline() != true) {
            return TextToSpeech.ERROR
        }
        val status = textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        return if (status == TextToSpeech.SUCCESS) status else retire(status)
    }

    /** Stops framework synthesis for the active queue. */
    override fun stop() {
        if (!retired.get() && textToSpeech.stop() != TextToSpeech.SUCCESS) retire(TextToSpeech.ERROR)
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
