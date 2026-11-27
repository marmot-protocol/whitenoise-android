package dev.ipf.whitenoise.android.audio.tts

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Thin adapter that keeps Android framework calls out of the queue state machine.
 *
 * [speak] returns at once and hands the sentence to the service on [handOff]'s thread, in call order. Each sentence's
 * installed-offline-voice check runs there, just before the sentence leaves the app, so the binder catalogue read never
 * blocks the caller. A refused sentence is reported through the queue's error callback.
 */
internal class AndroidTtsSpeechEngine(
    private val textToSpeech: TextToSpeech,
    private val enginePackage: String = "",
    private val selectedVoice: () -> TtsVoiceKey? = { null },
    private val onVoiceResolved: (TtsVoiceResolution) -> Unit = {},
    private val requireOfflineVoice: Boolean = false,
    private val onConnectionUncertain: () -> Unit = {},
    handOff: Executor = ttsHandOffThread,
) : TtsSpeechEngine {
    private val retired = AtomicBoolean()

    @Volatile
    private var enforceOfflineVoice = requireOfflineVoice

    /** The rate the controller set for the next sentence, applied when that sentence is handed off. */
    @Volatile
    private var speechRate: Float? = null

    private val handOffs = SerialHandOff(handOff)

    override var effectiveLocale: Locale? = null
        private set

    /** Applies voice policy without reusing a framework object which may have silently reconnected. */
    @Suppress("ReturnCount")
    override fun setLanguage(locale: Locale): Int =
        handOffs.exclusive {
            // Exclusive with hand-offs, so a sentence is never checked under one voice and spoken under the next.
            if (retired.get()) return@exclusive TextToSpeech.LANG_NOT_SUPPORTED
            val status = textToSpeech.setLanguage(locale)
            if (status == TextToSpeech.LANG_NOT_SUPPORTED) return@exclusive retire(status)
            if (status < TextToSpeech.LANG_AVAILABLE) return@exclusive status
            effectiveLocale = locale
            if (enginePackage.isEmpty()) return@exclusive status
            val voices = textToSpeech.voices ?: return@exclusive retire(TextToSpeech.LANG_NOT_SUPPORTED)
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
            if (retired.get() || (enforceOfflineVoice && resolution.effectiveKey == null)) {
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

    /** Records the controller's bounded speech rate for the sentences submitted after it. */
    override fun setSpeechRate(rate: Float) {
        speechRate = rate
    }

    /** Installs one listener that forwards every queue lifecycle callback. */
    override fun setCallbacks(
        onStart: (String?) -> Unit,
        onDone: (String?) -> Unit,
        onError: (String?, Int) -> Unit,
        onRangeStart: (String?, Int, Int, Int) -> Unit,
        onStop: (String?, Boolean) -> Unit,
    ) {
        handOffs.onRefused = onError
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
        handOffs.onRefused = null
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

    /**
     * Queues an utterance for hand-off. It reaches the service only if the installed-voice restriction still holds
     * when its turn comes; otherwise it and the rest of its queue are refused.
     */
    private fun enqueue(
        text: String,
        utteranceId: String,
        params: Bundle?,
    ): Int {
        if (retired.get()) return TextToSpeech.ERROR
        val rate = speechRate
        handOffs.submit(utteranceId) { current ->
            val allowed =
                !(enforceOfflineVoice || selectedVoice() != null) ||
                    currentFrameworkVoice()?.isInstalledOffline() == true
            when {
                // The voice lookup can outlast a stop, and a stopped queue's sentence never leaves the app.
                !current() -> null
                retired.get() || !allowed -> TextToSpeech.ERROR
                else -> {
                    rate?.let(textToSpeech::setSpeechRate)
                    val status = textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
                    if (status == TextToSpeech.SUCCESS) status else retire(status)
                }
            }
        }
        return TextToSpeech.SUCCESS
    }

    /**
     * Drops sentences not yet handed off at once, then stops framework synthesis on the hand-off thread after any
     * hand-off in flight, so a pause never waits on a voice lookup.
     */
    override fun stop() {
        handOffs.stop {
            if (!retired.get() && textToSpeech.stop() != TextToSpeech.SUCCESS) retire(TextToSpeech.ERROR)
        }
    }
}

/** One process-wide thread hands read-aloud sentences to the speech service, off the main thread. */
private val ttsHandOffThread: Executor =
    Executors.newSingleThreadExecutor { task -> Thread(task, "tts-hand-off").apply { isDaemon = true } }

/**
 * Runs hand-offs in submission order on [executor]. [stop] drops every hand-off not yet handed over, and a refused
 * hand-off drops the rest of its queue, reporting only the refused sentence to [onRefused]. A hand-off holds [lock]
 * from its voice check to its speak call, and [exclusive] waits for it, so a voice change never lands in between.
 */
private class SerialHandOff(
    private val executor: Executor,
) {
    private val lock = Any()
    private val queue = AtomicLong()

    /** Read and written only under [lock]. */
    private var refusedQueue = -1L

    @Volatile
    var onRefused: ((String?, Int) -> Unit)? = null

    /**
     * Queues [handOff] behind earlier submissions; it is skipped if its queue was stopped or refused first. [handOff]
     * gets a check that its queue is still current, to repeat just before it speaks, and returns null to drop quietly.
     */
    fun submit(
        utteranceId: String,
        handOff: (current: () -> Boolean) -> Int?,
    ) {
        val submittedIn = queue.get()
        val current = { submittedIn == queue.get() }
        executor.execute {
            val status =
                synchronized(lock) {
                    if (!current() || submittedIn == refusedQueue) return@synchronized null
                    handOff(current)?.also { if (it != TextToSpeech.SUCCESS) refusedQueue = submittedIn }
                } ?: return@execute
            // Reported outside the lock: the callback takes the controller's lock.
            if (status != TextToSpeech.SUCCESS) onRefused?.invoke(utteranceId, status)
        }
    }

    /** Drops every hand-off not yet handed over, then runs [halt] on [executor] after any hand-off in flight. */
    fun stop(halt: () -> Unit) {
        queue.incrementAndGet()
        executor.execute { synchronized(lock, halt) }
    }

    /** Runs [block] while no hand-off is in flight. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock, block)
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
