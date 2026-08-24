package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsControllerTest {
    @Test
    fun projectedMappingsSurviveChunkingAndEngineRangeCallbacks() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        val entry =
            TtsSpeakableEntry(
                senderKey = "alice",
                senderDisplayName = "Alice",
                text = "Hello world.",
                messageIdHex = "m1",
                spokenTextSpans =
                    listOf(
                        TtsSpokenTextSpan(
                            TtsTextRange(0, 11),
                            TtsVisibleTextSpan("b0/n0", 0, 11),
                        ),
                    ),
                projectionId = "projection-m1",
                timelineAt = 42uL,
            )

        assertTrue(controller.speak(listOf(entry), Locale.US))
        assertEquals(
            TtsPassage("m1", 0, "projection-m1", timelineAt = 42uL),
            controller.state.value.passage,
        )

        engine.range(index = 0, start = 13, end = 18)

        assertEquals(
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = "projection-m1",
                timelineAt = 42uL,
                visibleWord = listOf(TtsVisibleTextSpan("b0/n0", 6, 11)),
            ),
            controller.state.value.passage,
        )

        engine.stopped(index = 0)
        assertEquals(
            TtsPassage("m1", 0, "projection-m1", timelineAt = 42uL),
            controller.state.value.passage,
        )
    }

    @Test
    fun projectedMappingsWithLeadingWhitespacePublishExactVisibleWord() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        val entry =
            TtsSpeakableEntry(
                senderKey = "alice",
                senderDisplayName = "Alice",
                text = "  Hello world.  ",
                messageIdHex = "m1",
                spokenTextSpans =
                    listOf(
                        TtsSpokenTextSpan(
                            TtsTextRange(2, 13),
                            TtsVisibleTextSpan("b0/n0", 0, 11),
                        ),
                    ),
                projectionId = "projection-m1",
            )

        assertTrue(controller.speak(listOf(entry), Locale.US))
        assertEquals("Alice: Hello world.", engine.spoken.single().text)

        engine.range(index = 0, start = 13, end = 18)

        assertEquals(
            TtsPassage(
                messageIdHex = "m1",
                sentenceIndex = 0,
                projectionId = "projection-m1",
                visibleWord = listOf(TtsVisibleTextSpan("b0/n0", 6, 11)),
            ),
            controller.state.value.passage,
        )
    }

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
    fun speakStartsNewQueueAtRequestedSentence() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        val text = "First. Second. Third."

        assertTrue(
            controller.speak(
                entries = listOf(TtsSpeakableEntry("alice", "Alice", text)),
                locale = Locale.US,
                startSentenceIndex = 1,
            ),
        )

        assertEquals(listOf("Alice: Second.", "Third."), engine.spoken.map { it.text })
        assertEquals(
            speakingTts(1, 3, 0, 1, text, sentenceIndex = 1, sentenceCount = 3),
            controller.state.value,
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
            speakingTts(
                0,
                3,
                0,
                1,
                "First. Second. Third.",
                sentenceIndex = 0,
                sentenceCount = 3,
                messageProgressFraction = 1f / 3f,
            ),
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
            errorTts(TtsError.Synthesis, 0, 1, 0, 1, "One.", messageProgressGeneration = 0L),
            controller.state.value,
        )
        assertEquals(1, focus.acquireCalls)
        assertEquals(1, focus.releaseCalls)
    }

    @Test
    fun staleCallbacksFromAReplacedEngineCannotMutateTheNewQueue() {
        val firstEngine = FakeTtsSpeechEngine()
        val secondEngine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(firstEngine)
        controller.speak("Old one. Old two.", Locale.US)
        val staleCompletion = firstEngine.completionCallback
        val staleError = firstEngine.errorCallback
        val staleRange = firstEngine.rangeCallback
        val staleStop = firstEngine.stopCallback
        val staleId = firstEngine.spoken.first().utteranceId

        controller.attachEngine(secondEngine)
        controller.speak("New one. New two.", Locale.US)
        staleCompletion?.invoke(staleId)
        staleError?.invoke(staleId, TextToSpeech.ERROR_SYNTHESIS)
        staleRange?.invoke(staleId, 0, 3, 0)
        staleStop?.invoke(staleId, true)

        assertEquals(
            speakingTts(
                0,
                2,
                0,
                1,
                "New one. New two.",
                sentenceIndex = 0,
                sentenceCount = 2,
                messageProgressGeneration = 2L,
            ).copy(sessionId = 1L),
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
    fun historyWindowExtensionIgnoresTheAutoReadCapAndEvictsAtItsOwnBound() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        val initial = (1..55).map { entryWithId("m%02d".format(it)) }
        assertTrue(controller.speak(initial, Locale.US))
        assertEquals(TTS_AUTO_READ_MAX_MESSAGES, controller.queuedMessageIds().size)

        val newer = (51..65).map { entryWithId("m%02d".format(it)) }
        val extended =
            controller.extendReadAloudWindow(
                direction = TtsHistoryDirection.Newer,
                entries = newer,
                targetMessageIdHex = "m51",
                targetSentence = TtsWindowSentenceTarget.First,
            )

        assertTrue(extended)
        // 50 + 15 exceeds the history bound, so the oldest head evicts — the
        // deliberate session is capped by eviction, never by the auto-read cap.
        assertEquals(TTS_HISTORY_WINDOW_MAX_MESSAGES, controller.queuedMessageIds().size)
        assertEquals("m06", controller.queuedMessageIds().first())
        assertEquals("m65", controller.queuedMessageIds().last())
        val state = controller.state.value as TtsState.Speaking
        assertEquals(45, state.messageIndex)
        assertEquals("Text m51.", state.messagePreview)
    }

    @Test
    fun historyWindowExtensionRefusesWhenNoSessionIsActive() {
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(FakeTtsSpeechEngine())

        assertFalse(
            controller.extendReadAloudWindow(
                direction = TtsHistoryDirection.Older,
                entries = listOf(entryWithId("m1")),
                targetMessageIdHex = "m1",
                targetSentence = TtsWindowSentenceTarget.First,
            ),
        )
    }

    @Test
    fun historyWindowExtensionRefusesAnEmptyExtensionInsteadOfMovingTheCursor() {
        val engine = FakeTtsSpeechEngine()
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(engine)
        assertTrue(controller.speak(listOf(entryWithId("m1"), entryWithId("m2")), Locale.US))
        controller.skipNextMessage()
        val stateBefore = controller.state.value
        val spokenBefore = engine.spoken.size

        // Nothing in the page projected to speech, so the extension is empty
        // while its target is already queued.
        assertFalse(
            controller.extendReadAloudWindow(
                direction = TtsHistoryDirection.Older,
                entries =
                    listOf(
                        TtsSpeakableEntry(
                            senderKey = "alice",
                            senderDisplayName = "Alice",
                            text = "   ",
                            messageIdHex = "m1",
                        ),
                    ),
                targetMessageIdHex = "m1",
                targetSentence = TtsWindowSentenceTarget.First,
            ),
        )

        assertEquals(listOf("m1", "m2"), controller.queuedMessageIds())
        assertEquals(stateBefore, controller.state.value)
        assertEquals(spokenBefore, engine.spoken.size)
    }

    @Test
    fun deferredMessageNavigationReportsEdgesThroughTheController() {
        val controller = controller(FakeTtsAudioFocus())
        controller.attachEngine(FakeTtsSpeechEngine())
        controller.speak(listOf(entryWithId("m1")), Locale.US)

        assertEquals(TtsNavigationOutcome.AtOlderEdge, controller.skipPreviousMessage(deferAtEdge = true))
        assertEquals(TtsNavigationOutcome.AtNewerEdge, controller.skipNextMessage(deferAtEdge = true))
        assertTrue(controller.state.value is TtsState.Speaking)

        controller.stop()

        assertEquals(TtsNavigationOutcome.Inactive, controller.skipNextMessage(deferAtEdge = true))
    }

    private fun entryWithId(id: String): TtsSpeakableEntry =
        TtsSpeakableEntry(
            senderKey = "alice",
            senderDisplayName = "Alice",
            text = "Text $id.",
            messageIdHex = id,
        )

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
        var startCallback: ((String?) -> Unit)? = null
        var completionCallback: ((String?) -> Unit)? = null
        var errorCallback: ((String?, Int) -> Unit)? = null
        var rangeCallback: ((String?, Int, Int, Int) -> Unit)? = null
        var stopCallback: ((String?, Boolean) -> Unit)? = null

        override fun setLanguage(locale: Locale): Int {
            this.locale = locale
            return languageResult
        }

        val appliedRates = mutableListOf<Float>()

        override fun setSpeechRate(rate: Float) {
            appliedRates += rate
        }

        override fun setCallbacks(
            onStart: (String?) -> Unit,
            onDone: (String?) -> Unit,
            onError: (String?, Int) -> Unit,
            onRangeStart: (String?, Int, Int, Int) -> Unit,
            onStop: (String?, Boolean) -> Unit,
        ) {
            startCallback = onStart
            completionCallback = onDone
            errorCallback = onError
            rangeCallback = onRangeStart
            stopCallback = onStop
        }

        override fun clearCallbacks() {
            startCallback = null
            completionCallback = null
            errorCallback = null
            rangeCallback = null
            stopCallback = null
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

        fun start(index: Int) {
            startCallback?.invoke(spoken[index].utteranceId)
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

        fun range(
            index: Int,
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken[index].utteranceId, start, end, 0)
        }

        fun stopped(index: Int) {
            stopCallback?.invoke(spoken[index].utteranceId, true)
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
