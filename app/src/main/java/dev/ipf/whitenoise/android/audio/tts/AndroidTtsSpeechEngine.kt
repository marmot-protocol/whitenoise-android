package dev.ipf.whitenoise.android.audio.tts

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    /** One binder voice check per submission batch: a main-thread task or one framework callback. */
    private val offlineVoice = TaskScopedConfirmation()

    override var effectiveLocale: Locale? = null
        private set

    /** Applies voice policy without reusing a framework object which may have silently reconnected. */
    @Suppress("ReturnCount")
    override fun setLanguage(locale: Locale): Int {
        if (retired.get()) return TextToSpeech.LANG_NOT_SUPPORTED
        offlineVoice.clear()
        val status = textToSpeech.setLanguage(locale)
        if (status == TextToSpeech.LANG_NOT_SUPPORTED) return retire(status)
        if (status < TextToSpeech.LANG_AVAILABLE) return status
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
        offlineVoice.clear()
        if (retired.compareAndSet(false, true)) onConnectionUncertain()
        return status
    }

    /** The framework can return its normal error value after reconnecting behind the same object. */
    private fun selectFrameworkVoice(voice: Voice): Int {
        if (retired.get()) return TextToSpeech.ERROR
        val status = textToSpeech.setVoice(voice)
        return if (status == TextToSpeech.LANG_NOT_SUPPORTED) retire(status) else status
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
        // Each callback is one submission batch, so a queue resubmitted from it checks the voice once.
        textToSpeech.setOnUtteranceProgressListener(
            androidTtsProgressListener(
                { id -> offlineVoice.duringCallback { onStart(id) } },
                { id -> offlineVoice.duringCallback { onDone(id) } },
                { id, error ->
                    offlineVoice.duringCallback {
                        if (error == TextToSpeech.ERROR_SERVICE || error == TextToSpeech.ERROR) retire(error)
                        onError(id, error)
                    }
                },
                { id, start, end, frame -> offlineVoice.duringCallback { onRangeStart(id, start, end, frame) } },
                { id, interrupted -> offlineVoice.duringCallback { onStop(id, interrupted) } },
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
    ): Int =
        when {
            retired.get() -> TextToSpeech.ERROR
            (enforceOfflineVoice || selectedVoice() != null) &&
                !offlineVoice.confirm { currentFrameworkVoice()?.isInstalledOffline() == true } ->
                TextToSpeech.ERROR
            else -> {
                val status = textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
                if (status == TextToSpeech.SUCCESS) status else retire(status)
            }
        }

    /** Stops framework synthesis for the active queue. */
    override fun stop() {
        offlineVoice.clear()
        if (!retired.get() && textToSpeech.stop() != TextToSpeech.SUCCESS) retire(TextToSpeech.ERROR)
    }
}

/**
 * Reuses a positive check for the rest of one submission batch, so a batch of sentences reads the framework voice
 * once. On a looper the batch is the current task: a message at the front of that looper's queue clears it, ahead of
 * any later message such as a framework reconnection callback. On a callback thread without a looper, such as a Binder
 * thread requeueing after a rate change, the batch is the framework callback [duringCallback] wraps. Anywhere else
 * every call checks again. [clear] cancels a confirmation on every thread.
 */
private class TaskScopedConfirmation {
    @Volatile
    private var confirmedOn: Looper? = null
    private val changes = AtomicLong()

    /** The [changes] count a callback on this thread confirmed at, [UNCONFIRMED] before it does, null outside one. */
    private val callbackConfirmedAt = ThreadLocal<Long?>()

    /** Forgets every confirmation at once, for example when the voice or language may have changed. */
    fun clear() {
        confirmedOn = null
        changes.incrementAndGet()
    }

    /** Runs a framework callback as one batch: speech it submits on a thread without a looper shares one check. */
    fun duringCallback(block: () -> Unit) {
        val outer = callbackConfirmedAt.get()
        callbackConfirmedAt.set(UNCONFIRMED)
        try {
            block()
        } finally {
            if (outer == null) callbackConfirmedAt.remove() else callbackConfirmedAt.set(outer)
        }
    }

    /** Returns true when this batch already confirmed, or when [check] confirms now. */
    fun confirm(check: () -> Boolean): Boolean {
        val looper = Looper.myLooper()
        val at = changes.get()
        if ((looper != null && confirmedOn === looper) || callbackConfirmedAt.get() == at) return true
        val confirmed = check()
        if (confirmed && looper != null) {
            // Keep the confirmation only when its clear is queued; a quitting looper refuses the post.
            val clearQueued = Handler(looper).postAtFrontOfQueue { if (confirmedOn === looper) confirmedOn = null }
            if (clearQueued && changes.get() == at) confirmedOn = looper
        } else if (confirmed && callbackConfirmedAt.get() != null) {
            callbackConfirmedAt.set(at)
        }
        return confirmed
    }
}

/** Marks a callback batch that has not confirmed the voice yet; [AtomicLong] change counts are never negative. */
private const val UNCONFIRMED = -1L

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
