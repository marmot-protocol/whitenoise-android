package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsControllerTest {
    @Test
    fun speakChunksTextAndAdvancesFromEngineCallbacks() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)

        assertTrue(controller.speak("First sentence. Second sentence.", Locale.US))

        assertEquals(1, focus.acquireCalls)
        assertEquals(Locale.US, engine.locale)
        assertEquals(listOf("First sentence.", "Second sentence."), engine.spoken.map { it.text })
        assertEquals(TtsState.Speaking(chunkIndex = 0, chunkCount = 2), controller.state.value)

        engine.complete(0)
        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 2), controller.state.value)
        engine.complete(1)
        assertEquals(TtsState.Idle(chunkIndex = 2, chunkCount = 2), controller.state.value)
        assertEquals(1, focus.releaseCalls)
    }

    @Test
    fun focusLossPausesAndResumeRestartsTheCurrentChunk() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)
        controller.speak("First. Second.", Locale.US)
        engine.complete(0)

        focus.loseFocus()

        assertEquals(TtsState.Paused(chunkIndex = 1, chunkCount = 2), controller.state.value)
        assertEquals(1, focus.releaseCalls)

        controller.resume()

        assertEquals(2, focus.acquireCalls)
        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 2), controller.state.value)
        assertEquals("Second.", engine.spoken.last().text)
    }

    @Test
    fun networkCallbackStopsPlaybackAndReleasesFocus() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)
        controller.speak("One.", Locale.US)

        engine.fail(0, TextToSpeech.ERROR_NETWORK)

        assertEquals(TtsState.Error(TtsError.Network, chunkIndex = 0, chunkCount = 1), controller.state.value)
        assertEquals(1, focus.releaseCalls)
    }

    @Test
    fun ownerSurrenderStopsWithoutRedundantlyReleasingFocus() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)
        controller.speak("One.", Locale.US)

        focus.surrender()

        assertTrue(controller.state.value is TtsState.Idle)
        assertEquals(0, focus.releaseCalls)
    }

    @Test
    fun explicitStopCancelsEngineQueueAndReleasesFocus() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)
        controller.speak("One. Two.", Locale.US)

        controller.stop()

        assertTrue(controller.state.value is TtsState.Idle)
        assertEquals(1, focus.releaseCalls)
        assertEquals(2, engine.stopCalls)
    }

    @Test
    fun unavailableEngineOrBlankTextDoesNotAcquireFocus() {
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)

        assertFalse(controller.speak("message", Locale.US))
        controller.attachEngine(FakeTtsSpeechEngine())
        assertFalse(controller.speak("   ", Locale.US))

        assertEquals(0, focus.acquireCalls)
    }

    @Test
    fun unsupportedLocaleDoesNotStartPlaybackAndReleasesFocus() {
        val engine = FakeTtsSpeechEngine(languageResult = TextToSpeech.LANG_NOT_SUPPORTED)
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)

        assertFalse(controller.speak("One.", Locale.US))

        assertTrue(engine.spoken.isEmpty())
        assertEquals(TtsState.Error(TtsError.Synthesis, chunkIndex = 0, chunkCount = 1), controller.state.value)
        assertEquals(1, focus.acquireCalls)
        assertEquals(1, focus.releaseCalls)
    }

    @Test
    fun staleCallbacksFromAReplacedEngineCannotAdvanceTheNewQueue() {
        val firstEngine = FakeTtsSpeechEngine()
        val secondEngine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(firstEngine)
        controller.speak("Old one. Old two.", Locale.US)
        val staleCompletion = firstEngine.completionCallback
        val staleId = firstEngine.spoken.first().utteranceId

        controller.attachEngine(secondEngine)
        controller.speak("New one. New two.", Locale.US)
        staleCompletion?.invoke(staleId)

        assertEquals(TtsState.Speaking(chunkIndex = 0, chunkCount = 2), controller.state.value)
        assertEquals(listOf("New one.", "New two."), secondEngine.spoken.map { it.text })
    }

    @Test
    fun speechRateIsReReadAtEverySentenceBoundary() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        var rate = 1.0f
        val controller =
            TtsController(
                audioFocus = focus,
                chunkText = { text, locale -> TtsChunker.chunk(text, locale, maxChunkLength = 4_000) },
                speechRate = { rate },
            )
        controller.attachEngine(engine)

        assertTrue(controller.speak("First sentence. Second sentence.", Locale.US))
        rate = 1.5f
        controller.onSpeechRateChanged()

        // The current sentence keeps its rate; nothing is re-queued yet.
        assertEquals(1.0f, engine.appliedRates.last())

        engine.complete(0)

        // At the boundary the remaining chunks re-queue with the new rate.
        assertEquals(1.5f, engine.appliedRates.last())
        assertEquals("Second sentence.", engine.spoken.last().text)
        assertEquals(TtsState.Speaking(chunkIndex = 1, chunkCount = 2), controller.state.value)
    }

    private fun controller(focus: FakeTtsAudioFocus): TtsController =
        TtsController(
            audioFocus = focus,
            chunkText = { text, locale -> TtsChunker.chunk(text, locale, maxChunkLength = 4_000) },
        )

    private data class Spoken(
        val text: String,
        val utteranceId: String,
    )

    private class FakeTtsSpeechEngine(
        private val speakResult: Int = TextToSpeech.SUCCESS,
        private val languageResult: Int = TextToSpeech.LANG_AVAILABLE,
    ) : TtsSpeechEngine {
        val spoken = mutableListOf<Spoken>()
        var stopCalls = 0
            private set
        var locale: Locale? = null
        var completionCallback: ((String?) -> Unit)? = null
        private var errorCallback: ((String?, Int) -> Unit)? = null

        override fun setLanguage(locale: Locale): Int {
            this.locale = locale
            return languageResult
        }

        val appliedRates = mutableListOf<Float>()

        override fun setSpeechRate(rate: Float) {
            appliedRates += rate
        }

        override fun setCallbacks(
            onDone: (String?) -> Unit,
            onError: (String?, Int) -> Unit,
        ) {
            completionCallback = onDone
            errorCallback = onError
        }

        override fun clearCallbacks() {
            completionCallback = null
            errorCallback = null
        }

        override fun speak(
            text: String,
            utteranceId: String,
        ): Int {
            spoken += Spoken(text, utteranceId)
            return speakResult
        }

        override fun stop() {
            stopCalls += 1
        }

        fun complete(index: Int) {
            completionCallback?.invoke(spoken[index].utteranceId)
        }

        fun fail(
            index: Int,
            errorCode: Int,
        ) {
            errorCallback?.invoke(spoken[index].utteranceId, errorCode)
        }
    }

    private class FakeTtsAudioFocus : TtsAudioFocus {
        var acquireCalls = 0
        var releaseCalls = 0
        private var onFocusLoss: (() -> Unit)? = null
        private var onOwnerSurrender: (() -> Unit)? = null

        override fun acquire(
            onFocusLoss: () -> Unit,
            onOwnerSurrender: () -> Unit,
        ): Boolean {
            acquireCalls += 1
            this.onFocusLoss = onFocusLoss
            this.onOwnerSurrender = onOwnerSurrender
            return true
        }

        override fun release() {
            releaseCalls += 1
        }

        fun loseFocus() {
            onFocusLoss?.invoke()
        }

        fun surrender() {
            onOwnerSurrender?.invoke()
        }
    }
}
