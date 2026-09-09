package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsMediaMixControllerTest {
    /** Guards the unchanged ordinary playback contract when mixing is disabled. */
    @Test
    fun ordinaryPlaybackKeepsFullFocusAndDoesNotSendAVolumeBundle() {
        val engine = RecordingEngine()
        val focus = RecordingFocus()
        val controller = controller(focus, mixEnabled = { false }, mediaActive = { false })
        controller.attachEngine(engine)

        assertTrue(controller.speak("Ordinary speech.", Locale.US))

        assertEquals(listOf(TtsAudioFocusMode.Full), focus.modes)
        assertEquals(listOf(null), engine.spoken.map { it.volume })
    }

    /** Regression: enabling media mix must not prevent ordinary playback when no peer media exists. */
    @Test
    fun absentMediaFallsBackToFullFocusAndSpeaksNormally() {
        val engine = RecordingEngine()
        val focus = RecordingFocus()
        val controller = controller(focus, mixEnabled = { true }, mediaActive = { false })
        controller.attachEngine(engine)

        assertTrue(controller.speak("Keep reading.", Locale.US))

        assertEquals(listOf(TtsAudioFocusMode.Full), focus.modes)
        assertEquals(1, engine.languageCalls)
        assertEquals(listOf("Keep reading."), engine.spoken.map { it.text })
        assertEquals(listOf(null), engine.spoken.map { it.volume })
        assertTrue(controller.state.value is TtsState.Speaking)
        assertEquals(TtsStartFailure.None, controller.lastStartFailure)
    }

    /** Defensively prevents speech when the injected session-focus policy refuses. */
    @Test
    fun mediaMixPolicyFailureDoesNotLeakSpeech() {
        val engine = RecordingEngine()
        val focus = RecordingFocus(granted = false)
        val controller = controller(focus, mixEnabled = { true }, mediaActive = { true })
        controller.attachEngine(engine)

        assertFalse(controller.speak("Do not speak.", Locale.US))

        assertEquals(listOf(TtsAudioFocusMode.MediaMix), focus.modes)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(TtsStartFailure.AudioFocusDenied, controller.lastStartFailure)
    }

    /** Samples media activity once when selecting the session's latched focus mode. */
    @Test
    fun activeMediaSelectsMixModeOnceAtTheStartBoundary() {
        var activeChecks = 0
        val engine = RecordingEngine()
        val focus = RecordingFocus()
        val controller =
            controller(
                focus,
                mixEnabled = { true },
                mediaActive = {
                    activeChecks += 1
                    true
                },
            )
        controller.attachEngine(engine)

        assertTrue(controller.speak("Race-safe speech.", Locale.US))

        assertEquals(1, engine.languageCalls)
        assertEquals(1, activeChecks)
        assertEquals(listOf(TtsAudioFocusMode.MediaMix), focus.modes)
        assertEquals(listOf("Race-safe speech."), engine.spoken.map { it.text })
        assertEquals(0, focus.releases)
        assertTrue(controller.state.value is TtsState.Speaking)
        assertEquals(TtsStartFailure.None, controller.lastStartFailure)
    }

    /** Verifies bounded enqueue parameters and next-boundary preference updates. */
    @Test
    fun activeMediaUsesBoundedVolumeAndChangedLevelAtTheNextBoundary() {
        var volume = 0.35f
        val engine = RecordingEngine()
        val focus = RecordingFocus()
        val controller =
            controller(
                focus,
                mixEnabled = { true },
                mediaActive = { true },
                volume = { volume },
            )
        controller.attachEngine(engine)

        assertTrue(controller.speak("First sentence. Second sentence.", Locale.US))
        val sessionId = controller.state.value.sessionId
        assertEquals(listOf(0.35f, 0.35f), engine.spoken.map { it.volume })

        volume = 4f
        controller.onMediaMixVolumeChanged()
        engine.complete(0)

        assertEquals(1f, engine.spoken.last().volume)
        assertEquals("Second sentence.", engine.spoken.last().text)
        assertEquals(sessionId, controller.state.value.sessionId)
    }

    /** Keeps pause and resume on the focus policy latched at session start. */
    @Test
    fun pauseAndResumeRetainTheMixFocusMode() {
        val engine = RecordingEngine()
        val focus = RecordingFocus()
        val controller = controller(focus, mixEnabled = { true }, mediaActive = { true })
        controller.attachEngine(engine)
        assertTrue(controller.speak("Pause and resume.", Locale.US))

        controller.pause()
        controller.resume()

        assertEquals(listOf(TtsAudioFocusMode.MediaMix, TtsAudioFocusMode.MediaMix), focus.modes)
    }

    /** Covers the background-preparation path used by auto-read and long messages. */
    @Test
    fun absentMediaAlsoFallsBackToFullFocusDuringAsyncPreparation() =
        runTest {
            val engine = RecordingEngine()
            val focus = RecordingFocus()
            val controller = controller(focus, mixEnabled = { true }, mediaActive = { false })
            controller.attachEngine(engine)

            assertTrue(
                controller.speakAsync(
                    entries = listOf(TtsSpeakableEntry("", "", "Prepared speech.")),
                    locale = Locale.US,
                    onPreparing = { true },
                ),
            )

            assertEquals(listOf(TtsAudioFocusMode.Full, TtsAudioFocusMode.Full), focus.modes)
            assertEquals(listOf("Prepared speech."), engine.spoken.map { it.text })
            assertEquals(listOf(null), engine.spoken.map { it.volume })
            assertTrue(controller.state.value is TtsState.Speaking)
            assertEquals(TtsStartFailure.None, controller.lastStartFailure)
        }

    /** A denied switch to Full focus restores the untouched mixed queue policy. */
    @Test
    fun fullFocusDenialDoesNotStrandTheExistingMediaMixQueue() {
        var mixEnabled = true
        val engine = RecordingEngine()
        val focus = RecordingFocus(acquireResults = ArrayDeque(listOf(true, false, true)))
        val controller =
            controller(
                focus,
                mixEnabled = { mixEnabled },
                mediaActive = { true },
            )
        controller.attachEngine(engine)
        assertTrue(controller.speak("Existing queue.", Locale.US))
        val existingState = controller.state.value

        mixEnabled = false
        assertFalse(controller.speak("Denied replacement.", Locale.US))

        assertEquals(existingState, controller.state.value)
        val expectedModes = listOf(TtsAudioFocusMode.MediaMix, TtsAudioFocusMode.Full, TtsAudioFocusMode.MediaMix)
        assertEquals(expectedModes, focus.modes)
        assertEquals(listOf("Existing queue."), engine.spoken.map { it.text })
    }

    /** Builds a controller whose three policy signals remain mutable by each test. */
    private fun controller(
        focus: RecordingFocus,
        mixEnabled: () -> Boolean,
        mediaActive: () -> Boolean,
        volume: () -> Float = { 0.6f },
    ) = TtsController(
        audioFocus = focus,
        maxChunkLength = 4_000,
        mediaMixEnabled = mixEnabled,
        mediaMixVolume = volume,
        isMediaPlaybackActive = mediaActive,
    )

    private data class Spoken(
        val text: String,
        val utteranceId: String,
        val volume: Float?,
    )

    private class RecordingEngine : TtsSpeechEngine {
        val spoken = mutableListOf<Spoken>()
        val locales = mutableListOf<Locale>()
        val languageCalls: Int
            get() = locales.size
        private var onDone: ((String?) -> Unit)? = null

        /** Records that language work happened after both initial gates. */
        override fun setLanguage(locale: Locale): Int {
            locales += locale
            return TextToSpeech.LANG_AVAILABLE
        }

        /** Rate is irrelevant to the media policy under test. */
        override fun setSpeechRate(rate: Float) = Unit

        /** Retains completion so tests can cross a real queue boundary. */
        override fun setCallbacks(
            onStart: (String?) -> Unit,
            onDone: (String?) -> Unit,
            onError: (String?, Int) -> Unit,
            onRangeStart: (String?, Int, Int, Int) -> Unit,
            onStop: (String?, Boolean) -> Unit,
        ) {
            this.onDone = onDone
        }

        /** Makes detached-engine completions inert. */
        override fun clearCallbacks() {
            onDone = null
        }

        /** Records an ordinary utterance without a volume parameter. */
        override fun speak(
            text: String,
            utteranceId: String,
        ): Int {
            spoken += Spoken(text, utteranceId, null)
            return TextToSpeech.SUCCESS
        }

        /** Records the exact bounded volume submitted for mixed speech. */
        override fun speak(
            text: String,
            utteranceId: String,
            volume: Float,
        ): Int {
            spoken += Spoken(text, utteranceId, volume)
            return TextToSpeech.SUCCESS
        }

        /** Queue cancellation has no external resource in this fake. */
        override fun stop() = Unit

        /** Completes one recorded utterance through the controller callback. */
        fun complete(index: Int) {
            onDone?.invoke(spoken[index].utteranceId)
        }
    }

    private class RecordingFocus(
        private val granted: Boolean = true,
        private val acquireResults: ArrayDeque<Boolean> = ArrayDeque(),
    ) : TtsAudioFocus {
        val modes = mutableListOf<TtsAudioFocusMode>()
        var releases = 0

        /** Models the legacy full-focus entry point for compatibility. */
        override fun acquire(
            onFocusLoss: () -> Unit,
            onOwnerSurrender: () -> Unit,
        ): Boolean = acquire(TtsAudioFocusMode.Full, onFocusLoss, onOwnerSurrender)

        /** Records the requested policy before returning the configured result. */
        override fun acquire(
            mode: TtsAudioFocusMode,
            onFocusLoss: () -> Unit,
            onOwnerSurrender: () -> Unit,
        ): Boolean {
            modes += mode
            return acquireResults.removeFirstOrNull() ?: granted
        }

        /** Counts every focus return for leak assertions. */
        override fun release() {
            releases += 1
        }
    }
}
