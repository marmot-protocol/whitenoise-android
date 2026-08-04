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
        assertEquals(
            speakingTts(0, 2, 0, 1, "First sentence. Second sentence.", sentenceIndex = 0, sentenceCount = 2),
            controller.state.value,
        )

        engine.complete(0)
        assertEquals(
            speakingTts(1, 2, 0, 1, "First sentence. Second sentence.", sentenceIndex = 1, sentenceCount = 2),
            controller.state.value,
        )
        engine.complete(1)
        assertEquals(
            idleTts(2, 2, 1, 1, "First sentence. Second sentence.", sentenceIndex = 2, sentenceCount = 2),
            controller.state.value,
        )
        assertEquals(1, focus.releaseCalls)
    }

    @Test
    fun speakableEntryTextIsAlsoTheTransportPreview() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)
        val projectedText = "Status. This is important."

        assertTrue(
            controller.speak(
                listOf(TtsSpeakableEntry("alice", "Alice", projectedText)),
                Locale.US,
            ),
        )

        assertEquals(projectedText, (controller.state.value as TtsState.Speaking).messagePreview)
        assertEquals(
            listOf("Alice: Status.", "This is important."),
            engine.spoken.map { it.text },
        )
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

        assertEquals(
            pausedTts(1, 2, 0, 1, "First. Second.", sentenceIndex = 1, sentenceCount = 2),
            controller.state.value,
        )
        assertEquals(1, focus.releaseCalls)

        controller.resume()

        assertEquals(2, focus.acquireCalls)
        assertEquals(
            speakingTts(1, 2, 0, 1, "First. Second.", sentenceIndex = 1, sentenceCount = 2),
            controller.state.value,
        )
        assertEquals("Second.", engine.spoken.last().text)
    }

    @Test
    fun pausedNavigationRepositionsWithoutFocusOrSpeechUntilPlay() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)
        controller.speak(
            listOf(
                TtsSpeakableEntry("alice", "Alice", "First."),
                TtsSpeakableEntry("bob", "Bob", "Second. Third."),
            ),
            Locale.US,
        )
        controller.pause()
        val spokenBeforeNavigation = engine.spoken.size

        controller.skipNextMessage()
        controller.skipNextSentence()

        assertEquals(1, focus.acquireCalls)
        assertEquals(spokenBeforeNavigation, engine.spoken.size)
        assertEquals(
            pausedTts(2, 3, 1, 2, "Second. Third.", sentenceIndex = 1, sentenceCount = 2),
            controller.state.value,
        )

        controller.resume()

        assertEquals(2, focus.acquireCalls)
        assertEquals(
            speakingTts(2, 3, 1, 2, "Second. Third.", sentenceIndex = 1, sentenceCount = 2),
            controller.state.value,
        )
        assertEquals("Bob: Third.", engine.spoken.last().text)
    }

    @Test
    fun sentenceNavigationDelegatesWhileSpeaking() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        controller.speak("First. Second. Third.", Locale.US)

        controller.skipNextSentence()

        assertEquals(
            speakingTts(1, 3, 0, 1, "First. Second. Third.", sentenceIndex = 1, sentenceCount = 3),
            controller.state.value,
        )
        assertEquals(listOf("Second.", "Third."), engine.spoken.takeLast(2).map { it.text })

        controller.skipPreviousSentence()

        assertEquals(
            speakingTts(0, 3, 0, 1, "First. Second. Third.", sentenceIndex = 0, sentenceCount = 3),
            controller.state.value,
        )
        assertEquals(listOf("First.", "Second.", "Third."), engine.spoken.takeLast(3).map { it.text })
    }

    @Test
    fun messageNavigationDelegatesWhileSpeaking() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        controller.speak(
            listOf(
                TtsSpeakableEntry("alice", "Alice", "First."),
                TtsSpeakableEntry("bob", "Bob", "Second."),
            ),
            Locale.US,
        )

        controller.skipNextMessage()

        assertEquals(speakingTts(1, 2, 1, 2, "Second."), controller.state.value)
        assertEquals("Bob: Second.", engine.spoken.last().text)

        controller.skipPreviousMessage()

        assertEquals(speakingTts(0, 2, 0, 2, "First."), controller.state.value)
        assertEquals(
            "Alice: First.",
            engine.spoken
                .takeLast(2)
                .first()
                .text,
        )
    }

    @Test
    fun navigationIsIgnoredWhileIdleAndErrored() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)

        controller.skipNextSentence()
        controller.skipPreviousSentence()
        controller.skipNextMessage()
        controller.skipPreviousMessage()

        assertTrue(controller.state.value is TtsState.Idle)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, focus.acquireCalls)

        controller.speak("One.", Locale.US)
        engine.fail(0, TextToSpeech.ERROR_NETWORK)
        val errored = controller.state.value
        val spokenAfterError = engine.spoken.size

        controller.skipNextSentence()
        controller.skipNextMessage()

        assertEquals(errored, controller.state.value)
        assertEquals(spokenAfterError, engine.spoken.size)
        assertEquals(1, focus.acquireCalls)
    }

    @Test
    fun networkCallbackStopsPlaybackAndReleasesFocus() {
        val engine = FakeTtsSpeechEngine()
        val focus = FakeTtsAudioFocus()
        val controller = controller(focus)
        controller.attachEngine(engine)
        controller.speak("One.", Locale.US)

        engine.fail(0, TextToSpeech.ERROR_NETWORK)

        assertEquals(
            errorTts(TtsError.Network, 0, 1, 0, 1, "One.", sentenceIndex = 0, sentenceCount = 1),
            controller.state.value,
        )
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
        assertEquals(
            errorTts(TtsError.Synthesis, 0, 1, 0, 1, "One."),
            controller.state.value,
        )
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

        assertEquals(
            speakingTts(0, 2, 0, 1, "New one. New two.", sentenceIndex = 0, sentenceCount = 2),
            controller.state.value,
        )
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
                maxChunkLength = 4_000,
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
        assertEquals(
            speakingTts(1, 2, 0, 1, "First sentence. Second sentence.", sentenceIndex = 1, sentenceCount = 2),
            controller.state.value,
        )
    }

    @Test
    fun naturalPlaybackDoesNotRepeatAnUnchangedSenderAnnouncement() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)

        controller.speak(
            listOf(
                TtsSpeakableEntry("alice", "Alice", "First."),
                TtsSpeakableEntry("ALICE", "Alice", "Second."),
            ),
            Locale.US,
        )

        assertEquals(listOf("Alice: First.", "Second."), engine.spoken.map { it.text })
    }

    @Test
    fun appendExtendsAnActiveQueueAndSpeaksTheNewSentences() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        controller.speak("First. Second.", Locale.US)

        assertTrue(controller.appendSpeech(TtsSpeakableEntry("", "", "Third."), Locale.US))

        assertEquals(
            speakingTts(0, 3, 0, 2, "First. Second.", sentenceIndex = 0, sentenceCount = 2),
            controller.state.value,
        )
        assertEquals(listOf("First.", "Second.", "Third."), engine.spoken.map { it.text })
        engine.complete(0)
        engine.complete(1)
        engine.complete(2)
        assertEquals(idleTts(3, 3, 2, 2, "Third.", sentenceIndex = 1, sentenceCount = 1), controller.state.value)
    }

    @Test
    fun appendRejectsBlankEntryWithoutChangingActiveQueue() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        controller.speak("One.", Locale.US)
        val stateBeforeAppend = controller.state.value

        assertFalse(controller.appendSpeech(TtsSpeakableEntry("bob", "Bob", "   "), Locale.US))

        assertEquals(stateBeforeAppend, controller.state.value)
        assertEquals(listOf("One."), engine.spoken.map { it.text })
    }

    @Test
    fun everyFinalEnginePayloadStaysWithinMaxSpeechInputLength() {
        val maxLen = 30
        val displayName = "Alexandra"
        val announcementPrefix = "$displayName: "
        val longBody = "alpha beta gamma delta epsilon zeta eta theta iota"
        val engine = FakeTtsSpeechEngine()
        val controller =
            TtsController(
                audioFocus = FakeTtsAudioFocus(),
                maxChunkLength = maxLen,
            )
        controller.attachEngine(engine)

        controller.speak(
            listOf(TtsSpeakableEntry("alice", "  $displayName  ", longBody)),
            Locale.US,
        )
        val initialPayloads = engine.spoken.map { it.text }
        assertTrue(
            "initial playback must keep every engine payload within the limit",
            initialPayloads.all { it.length <= maxLen },
        )
        assertEquals(longBody, bodyTextFromEnginePayloads(initialPayloads, displayName))
        assertTrue(initialPayloads.size > 1)
        assertTrue(initialPayloads.first().startsWith(announcementPrefix))

        controller.speak(
            listOf(
                TtsSpeakableEntry("alice", displayName, "First bit."),
                TtsSpeakableEntry("alice", displayName, "Second bit."),
            ),
            Locale.US,
        )
        val naturalPayloads = engine.spoken.drop(initialPayloads.size).map { it.text }
        assertEquals(listOf("$displayName: First bit.", "Second bit."), naturalPayloads)

        controller.speak(
            listOf(
                TtsSpeakableEntry("alice", displayName, "Skip me."),
                TtsSpeakableEntry("alice", displayName, longBody),
            ),
            Locale.US,
        )
        val beforeJumpCount = engine.spoken.size
        controller.skipNextMessage()
        val jumpPayloads = engine.spoken.drop(beforeJumpCount).map { it.text }
        assertTrue(
            "message jumps must keep every engine payload within the limit",
            jumpPayloads.all { it.length <= maxLen },
        )
        assertEquals(longBody, bodyTextFromEnginePayloads(jumpPayloads, displayName))
        assertTrue(jumpPayloads.first().startsWith(announcementPrefix))
    }

    @Test
    fun appendNeverResurrectsAnIdleSession() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)

        assertFalse(controller.appendSpeech(TtsSpeakableEntry("", "", "Orphan."), Locale.US))
        assertTrue(engine.spoken.isEmpty())
    }

    private fun controller(focus: FakeTtsAudioFocus): TtsController =
        TtsController(
            audioFocus = focus,
            maxChunkLength = 4_000,
        )

    private fun bodyTextFromEnginePayloads(
        payloads: List<String>,
        displayName: String,
    ): String {
        val prefix = "$displayName: "
        return payloads.joinToString(" ") { payload ->
            if (payload.startsWith(prefix)) payload.removePrefix(prefix) else payload
        }
    }

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
