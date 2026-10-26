package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import dev.ipf.whitenoise.android.ui.conversation.timelineRowTtsHighlightPassage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Controller-level playback timing: only native callbacks move words; ETA remains approximate. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // Native timing, capability, and ETA calibration share one controller harness.
class TtsPlaybackTimingCoverageTest {
    @Test
    fun silentEngineKeepsSentenceAndProgressDuringFiveSecondStall() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            val initial = harness.controller.state.value
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(initial, harness.controller.state.value)
            assertEquals(emptyList<TtsVisibleTextSpan>(), initial.passage?.visibleWord)
            assertEquals(initial.passage, timelineRowTtsHighlightPassage("m1", initial))

            harness.engine.range(index = 0, start = 6, end = 11)
            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 6, 11)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun nativeRangesDoNotCarryIntoSilentFollowingUtterance() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(), plainEntry(messageIdHex = "m2")), Locale.US))
            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)
            harness.engine.complete(index = 0)
            // An invalid range before onStart must not resurrect the preceding word.
            harness.engine.range(index = 1, start = 0, end = 0)
            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
            harness.engine.start(index = 1)
            harness.engine.range(index = 1, start = 0, end = 0)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(
                "m2",
                harness.controller.state.value.passage
                    ?.messageIdHex,
            )
            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun resumedUtteranceWaitsForItsOwnNativeRangeAndRejectsOldCallbacks() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)
            harness.controller.pause()
            harness.controller.resume()
            val resumedIndex = harness.engine.spoken.lastIndex
            harness.engine.start(index = resumedIndex)
            harness.engine.range(index = 0, start = 6, end = 11)
            harness.engine.stopped(index = 0)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
            harness.engine.range(index = resumedIndex, start = 6, end = 11)
            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 6, 11)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun nativeWordAndProgressDoNotAdvanceWhileAudioIsStalled() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)

            assertEquals(true, harness.store.verdicts[verdictKey()])

            val beforeStall = harness.controller.state.value
            // No playback callback arrives while the engine is stalled.
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(beforeStall, harness.controller.state.value)
        }

    @Test
    fun aLegacyEngineVerdictMigratesOnlyAfterTheLocaleConfirmsIt() =
        runTest {
            val harness = LaneHarness(this)
            harness.store.verdicts[ENGINE_KEY] = true
            harness.controller.detachEngine()
            harness.controller.attachEngine(harness.engine, engineKey = ENGINE_KEY)

            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            assertEquals(null, harness.store.verdicts[verdictKey()])
            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)

            assertEquals(true, harness.store.verdicts[verdictKey()])
            assertEquals(1, harness.store.rangeVerdictWrites)
        }

    @Test
    fun unusableRangeFromConfirmedCapableEngineClearsThePreviousWord() =
        runTest {
            val harness = LaneHarness(this)
            harness.store.verdicts[verdictKey()] = true
            harness.controller.detachEngine()
            harness.controller.attachEngine(harness.engine, engineKey = ENGINE_KEY)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)
            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )

            harness.engine.range(index = 0, start = 0, end = 0)

            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun enoughSilentSpeechPersistsARangeSilentVerdict() =
        runTest {
            val harness = LaneHarness(this)
            val text =
                "This message is deliberately long enough that a single spoken utterance accumulates " +
                    "the hundred and twenty silent characters the capability probe needs to conclude"
            assertTrue(harness.controller.speak(listOf(plainEntry(text)), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(3_000)
            harness.engine.complete(index = 0)

            assertEquals(false, harness.store.verdicts[verdictKey()])
        }

    @Test
    fun aPersistedCapableVerdictIsOverturnedWhenTheEngineStopsReportingRanges() =
        runTest {
            val harness = LaneHarness(this)
            harness.store.verdicts[verdictKey()] = true
            harness.controller.detachEngine()
            harness.controller.attachEngine(harness.engine, engineKey = ENGINE_KEY)
            assertTrue(harness.controller.speak(listOf(plainEntry(OVERTURNING_TEXT)), Locale.US))

            harness.engine.start(index = 0)
            harness.engine.complete(index = 0)

            assertEquals(false, harness.store.verdicts[verdictKey()])

            // Later silent utterances keep sentence fallback, regardless of the verdict.
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 1)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
            assertEquals(
                harness.controller.state.value.passage,
                timelineRowTtsHighlightPassage("m1", harness.controller.state.value),
            )
        }

    @Test
    fun confirmedCapabilityIsReexaminedWhenTheLocaleChangesVoice() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)
            harness.engine.complete(index = 0)
            assertEquals(true, harness.store.verdicts[verdictKey()])

            assertTrue(
                harness.controller.speak(
                    listOf(plainEntry(OVERTURNING_TEXT, messageIdHex = "m2")),
                    Locale.FRANCE,
                ),
            )
            harness.engine.start(index = 1)
            harness.engine.complete(index = 1)

            assertEquals(false, harness.store.verdicts[verdictKey(Locale.FRANCE)])
            assertEquals(true, harness.store.verdicts[verdictKey(Locale.US)])
            assertTrue(harness.controller.speak(listOf(plainEntry(messageIdHex = "m3")), Locale.FRANCE))
            harness.engine.start(index = 2)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
            harness.engine.complete(index = 2)

            // Returning to US keeps its historical verdict without inventing word positions.
            assertTrue(harness.controller.speak(listOf(plainEntry(messageIdHex = "m4")), Locale.US))
            harness.engine.start(index = 3)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
            assertEquals(true, harness.store.verdicts[verdictKey(Locale.US)])
        }

    @Test
    fun aPayloadWithNoVisibleMappingIsNotEvidenceAgainstTheEngine() =
        runTest {
            val harness = LaneHarness(this)
            // The plain speak overload carries no visible spans, so no engine
            // range from it could ever resolve to a word. Its silence says
            // nothing about the engine and must not be credited against it.
            assertTrue(harness.controller.speak(OVERTURNING_TEXT, Locale.US))
            harness.engine.start(index = 0)
            harness.engine.complete(index = 0)

            assertEquals(null, harness.store.verdicts[verdictKey()])

            // Witness: the same text WITH a visible mapping does conclude, so
            // the assertion above is about the mapping and not about length.
            assertTrue(harness.controller.speak(listOf(plainEntry(OVERTURNING_TEXT)), Locale.US))
            harness.engine.start(index = 1)
            harness.engine.complete(index = 1)

            assertEquals(false, harness.store.verdicts[verdictKey()])
        }

    @Test
    fun textOutsideAVisibleSpanIsNotEvidenceAgainstTheEngine() =
        runTest {
            val harness = LaneHarness(this)
            // Only the opening words are mapped to visible text. A range
            // anywhere else could never resolve to a word whatever the engine
            // does, so the engine has not been asked a question there.
            assertTrue(
                harness.controller.speak(
                    listOf(partiallyMappedEntry(OVERTURNING_TEXT, mappedLength = 20)),
                    Locale.US,
                ),
            )
            harness.engine.start(index = 0)
            harness.engine.complete(index = 0)

            assertEquals(null, harness.store.verdicts[verdictKey()])

            // Witness: the same text fully mapped does conclude, so this is
            // about the mapping and not about the length.
            assertTrue(harness.controller.speak(listOf(plainEntry(OVERTURNING_TEXT)), Locale.US))
            harness.engine.start(index = 1)
            harness.engine.complete(index = 1)

            assertEquals(false, harness.store.verdicts[verdictKey()])
        }

    @Test
    fun mappedMidWordFragmentsAreNotEvidenceAgainstTheEngine() =
        runTest {
            val harness = LaneHarness(this)
            val text = "hello ".repeat(80).trimEnd()
            val fragments =
                Regex("hello")
                    .findAll(text)
                    .map { match ->
                        val start = match.range.first + 1
                        TtsSpokenTextSpan(
                            TtsTextRange(start, start + 3),
                            TtsVisibleTextSpan("b0/n0", start, start + 3),
                        )
                    }.toList()
            val fragmented = plainEntry(text).copy(spokenTextSpans = fragments)

            assertTrue(harness.controller.speak(listOf(fragmented), Locale.US))
            harness.engine.start(index = 0)
            harness.engine.complete(index = 0)
            assertEquals(null, harness.store.verdicts[verdictKey()])

            assertTrue(harness.controller.speak(listOf(plainEntry(text, "m2")), Locale.US))
            harness.engine.start(index = 1)
            harness.engine.complete(index = 1)
            assertEquals(false, harness.store.verdicts[verdictKey()])
        }

    @Test
    fun anEmojiOnlyMessageIsNotEvidenceAgainstTheEngine() =
        runTest {
            val harness = LaneHarness(this)
            val emoji = "\uD83D\uDE42".repeat(80)
            assertTrue(harness.controller.speak(listOf(plainEntry(emoji)), Locale.US))

            harness.engine.start(index = 0)
            harness.engine.complete(index = 0)

            assertEquals(null, harness.store.verdicts[verdictKey()])
        }

    @Test
    fun aGapBetweenTwoWarmUtterancesMeasuresAndPersistsThePace() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(THREE_SENTENCES)), Locale.US))

            // The engine is cold for its first utterance, so the gap it opens
            // is refused: the interval between onStart and audible speech is at
            // its longest there and does not cancel against the next one.
            harness.engine.start(index = 0)
            advanceTimeBy(4_000)
            harness.engine.complete(index = 0)
            harness.engine.start(index = 1)
            assertTrue(
                "a cold first utterance must not teach a rate",
                harness.store.paces.isEmpty(),
            )

            advanceTimeBy(4_000)
            harness.engine.complete(index = 1)
            harness.engine.start(index = 2)

            val units = TtsWordTimingEstimate.weightedLengthOf(harness.engine.spoken[1].text)
            val expected = (4_000.0 - SENTENCE_SEAM_MS) / units
            // One sample moves the estimate a quarter of the way from the default.
            assertEquals(17.5 * 0.75 + expected * 0.25, harness.store.paces[ENGINE_KEY]!!, 0.5)
        }

    @Test
    fun timingUsesTheRateAppliedWhenTheUtteranceWasEnqueued() =
        runTest {
            val harness = LaneHarness(this)
            harness.rate = 1.0f
            assertTrue(harness.controller.speak(listOf(plainEntry(THREE_SENTENCES)), Locale.US))

            // Every chunk was submitted at 1x; a later preference change cannot
            // retroactively redenominate what the engine is already speaking.
            harness.rate = 2.0f
            harness.warmUpThenGap(gapMs = 4_000)

            val units = TtsWordTimingEstimate.weightedLengthOf(harness.engine.spoken[1].text)
            val expected = (4_000.0 - SENTENCE_SEAM_MS) / units
            assertEquals(17.5 * 0.75 + expected * 0.25, harness.store.paces[ENGINE_KEY]!!, 0.5)
            assertEquals(listOf(1.0f, 1.0f, 1.0f), harness.engine.appliedRates)
        }

    @Test
    fun aVoiceWhoseMeasuredPaceMatchesWhatIsHeldIsStillRecordedAsMeasured() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(THREE_SENTENCES)), Locale.US))
            harness.engine.start(index = 0)

            // Seed the store with what this gap is about to measure, so the
            // blend cannot move. The measurement still happened, and a store
            // that only remembers changes forgets exactly the voices that
            // already agree with it - which then re-measure from nothing in
            // every future process.
            val units = TtsWordTimingEstimate.weightedLengthOf(harness.engine.spoken[1].text)
            harness.store.paces[ENGINE_KEY] = (4_000.0 - SENTENCE_SEAM_MS) / units
            harness.store.paceWrites = 0

            advanceTimeBy(4_000)
            harness.engine.complete(index = 0)
            harness.engine.start(index = 1)
            advanceTimeBy(4_000)
            harness.engine.complete(index = 1)
            harness.engine.start(index = 2)

            assertEquals(1, harness.store.paceWrites)
        }

    @Test
    fun aPauseBetweenTwoUtterancesRefusesTheGap() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(THREE_SENTENCES)), Locale.US))

            // Warm the engine, then open a gap on chunk 1 and complete it, so a
            // measurable opener really is in hand when the pause happens. The
            // gap is closed by the first start AFTER the resume, which is the
            // sequence the refusal is about.
            harness.engine.start(index = 0)
            advanceTimeBy(4_000)
            harness.engine.complete(index = 0)
            harness.engine.start(index = 1)
            advanceTimeBy(4_000)
            harness.engine.complete(index = 1)

            harness.controller.pause()
            // Short enough that the gap across the pause would still land in
            // the calibrator plausible band. A longer wait would be refused for
            // being implausible whatever this rule did, and the test would pass
            // without exercising it.
            advanceTimeBy(1_500)
            harness.controller.resume()
            harness.engine.start(index = harness.engine.spoken.lastIndex)

            assertTrue(
                "a gap that spans a pause measures the pause, not the voice",
                harness.store.paces.isEmpty(),
            )

            // Witness: the same sequence without the pause does persist a rate,
            // so the assertion above is about the pause and not about the setup.
            val uninterrupted = LaneHarness(this)
            assertTrue(uninterrupted.controller.speak(listOf(plainEntry(THREE_SENTENCES)), Locale.US))
            uninterrupted.warmUpThenGap(gapMs = 4_000)
            assertTrue(uninterrupted.store.paces.containsKey(ENGINE_KEY))
        }

    @Test
    fun aSpeechRateChangeLandingAtTheBoundaryRefusesTheGap() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(THREE_SENTENCES)), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(4_000)
            harness.engine.complete(index = 0)
            harness.engine.start(index = 1)

            // The engine has already pre-buffered the rest of the window, so a
            // rate change only lands by re-queueing at the next boundary. The
            // gap either side of that re-queue is denominated in two different
            // rates and measures neither.
            harness.rate = 2.0f
            harness.controller.onSpeechRateChanged()
            advanceTimeBy(4_000)
            harness.engine.complete(index = 1)
            harness.engine.start(index = harness.engine.spoken.lastIndex)

            assertTrue(harness.store.paces.isEmpty())

            val unchanged = LaneHarness(this)
            assertTrue(unchanged.controller.speak(listOf(plainEntry(THREE_SENTENCES)), Locale.US))
            unchanged.warmUpThenGap(gapMs = 4_000)
            assertTrue(unchanged.store.paces.containsKey(ENGINE_KEY))
        }

    @Test
    fun aMessageAppendedAfterTheQueueRanDryRefusesTheGap() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(TWO_SENTENCES)), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(4_000)
            harness.engine.complete(index = 0)
            harness.engine.start(index = 1)

            // A deferred edge request parks the terminal chunk instead of
            // ending the session, so the queue stays Speaking with nothing left
            // to say. Whatever is appended later did not follow the opener
            // through the engine; the gap holds however long the listener
            // waited.
            assertEquals(
                TtsNavigationOutcome.AtNewerEdge,
                harness.controller.skipNextMessage(deferAtEdge = true),
            )
            advanceTimeBy(4_000)
            harness.engine.complete(index = 1)
            // Same reasoning as the pause test: the wait is short enough that
            // the resulting rate would be plausible, so only the rule under
            // test can refuse it.
            advanceTimeBy(1_500)
            assertTrue(harness.controller.appendSpeech(plainEntry(messageIdHex = "m2"), Locale.US))
            harness.engine.start(index = 2)

            assertTrue(harness.store.paces.isEmpty())

            // Witness: the same append arriving BEFORE the opener completed is
            // measured, so the refusal is about the engine having run dry and
            // not about appending.
            val stillSpeaking = LaneHarness(this)
            assertTrue(stillSpeaking.controller.speak(listOf(plainEntry(TWO_SENTENCES)), Locale.US))
            stillSpeaking.engine.start(index = 0)
            advanceTimeBy(4_000)
            stillSpeaking.engine.complete(index = 0)
            stillSpeaking.engine.start(index = 1)
            assertTrue(stillSpeaking.controller.appendSpeech(plainEntry(messageIdHex = "m2"), Locale.US))
            advanceTimeBy(4_000)
            stillSpeaking.engine.complete(index = 1)
            stillSpeaking.engine.start(index = 2)
            assertTrue(stillSpeaking.store.paces.containsKey(ENGINE_KEY))
        }

    @Test
    fun anAutoReadMessageAppendedBehindASpeakingUtteranceStillMeasures() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(TWO_SENTENCES)), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(4_000)
            harness.engine.complete(index = 0)
            harness.engine.start(index = 1)

            // A message arriving while the queue is still speaking is appended
            // behind the utterance in flight, so it really did follow it
            // through the engine. Refusing this would starve auto-read of every
            // sample it could ever take.
            assertTrue(
                harness.controller.appendSpeech(plainEntry(messageIdHex = "m2"), Locale.US),
            )
            advanceTimeBy(4_000)
            harness.engine.complete(index = 1)
            harness.engine.start(index = 2)

            assertTrue(harness.store.paces.containsKey(ENGINE_KEY))
        }

    @Test
    fun aBootstrapSampleIsNeverPersisted() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(ONE_LONG_SENTENCE)), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(9_000)
            harness.engine.complete(index = 0)

            // One utterance's own start-to-done interval carries an offset this
            // process cannot measure. It may steer the estimate here; it may
            // not be written against the voice for every future session.
            assertTrue(harness.store.paces.isEmpty())
        }

    @Test
    fun anUtteranceReportedStartedBeforeItsPredecessorFinishedIsNotTracked() =
        runTest {
            // An engine that pipelines - reporting the next start while the
            // previous utterance is still current - fails the queue's own
            // validation gate, so it opens no gap and cannot affect calibration.
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(TWO_SENTENCES)), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(2_000)
            harness.engine.start(index = 1)
            harness.engine.complete(index = 0)
            advanceTimeBy(4_000)
            runCurrent()

            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
            assertTrue(harness.store.paces.isEmpty())
        }

    @Test
    fun pauseFreezesThePassage() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            advanceTimeBy(150)
            runCurrent()

            harness.controller.pause()
            val frozen = harness.controller.state.value.passage

            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(harness.controller.state.value is TtsState.Paused)
            assertEquals(frozen, harness.controller.state.value.passage)
        }

    @Test
    fun aStaleNativeRangeCannotPaintAfterNavigation() =
        runTest {
            val harness = LaneHarness(this)
            val first = plainEntry("Hello world.", messageIdHex = "m1")
            val second = plainEntry("Goodbye world.", messageIdHex = "m2")
            assertTrue(harness.controller.speak(listOf(first, second), Locale.US))
            harness.engine.start(index = 0)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(TtsNavigationOutcome.Moved, harness.controller.skipNextMessage())
            harness.engine.range(index = 0, start = 0, end = 5)
            assertEquals(null, harness.store.verdicts[verdictKey()])

            // The new generation keeps its sentence-level passage.
            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(
                "m2",
                harness.controller.state.value.passage
                    ?.messageIdHex,
            )
            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun anEngineErrorRetainsThePausedSession() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            runCurrent()

            harness.engine.error(index = 0)
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(harness.controller.state.value is TtsState.Paused)
            assertEquals(null, harness.controller.state.value.passage)
        }

    @Test
    fun verdictsNeverCrossEngines() =
        runTest {
            val harness = LaneHarness(this)
            // Engine A proves itself range-capable.
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)
            assertEquals(true, harness.store.verdicts[verdictKey()])

            // A different engine must not inherit A's verdict or word position.
            val second = FakeLaneEngine()
            harness.controller.attachEngine(second, engineKey = "com.other.tts")
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            second.start(index = 0)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(
                emptyList<TtsVisibleTextSpan>(),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    private fun partiallyMappedEntry(
        text: String,
        mappedLength: Int,
        messageIdHex: String = "m1",
    ): TtsSpeakableEntry =
        TtsSpeakableEntry(
            senderKey = "",
            senderDisplayName = "",
            text = text,
            messageIdHex = messageIdHex,
            spokenTextSpans =
                listOf(
                    TtsSpokenTextSpan(
                        TtsTextRange(0, mappedLength),
                        TtsVisibleTextSpan("b0/n0", 0, mappedLength),
                    ),
                ),
            projectionId = "projection-",
        )

    private fun plainEntry(
        text: String = "Hello world.",
        messageIdHex: String = "m1",
    ): TtsSpeakableEntry =
        TtsSpeakableEntry(
            senderKey = "",
            senderDisplayName = "",
            text = text,
            messageIdHex = messageIdHex,
            spokenTextSpans =
                listOf(
                    TtsSpokenTextSpan(
                        TtsTextRange(0, text.length),
                        TtsVisibleTextSpan("b0/n0", 0, text.length),
                    ),
                ),
            projectionId = "projection-$messageIdHex",
        )

    private class LaneHarness(
        private val scope: TestScope,
    ) {
        var rate = 1.0f
        val engine = FakeLaneEngine()
        val store = FakeTimingStore()
        val controller =
            TtsController(
                audioFocus = FakeLaneFocus(),
                maxChunkLength = 4_000,
                speechRate = { rate },
                timingStore = store,
                clock = { scope.testScheduler.currentTime },
            )

        init {
            controller.attachEngine(engine, engineKey = ENGINE_KEY)
        }

        /**
         * Speaks chunk 0 to warm the engine, then opens and closes a gap of
         * [gapMs] around chunk 1. The first gap an engine offers is always
         * refused, so a test about anything else has to get past it.
         */
        fun warmUpThenGap(gapMs: Long) {
            engine.start(index = 0)
            scope.testScheduler.advanceTimeBy(gapMs)
            engine.complete(index = 0)
            engine.start(index = 1)
            scope.testScheduler.advanceTimeBy(gapMs)
            engine.complete(index = 1)
            engine.start(index = 2)
        }
    }

    private class FakeTimingStore : TtsTimingStore {
        val verdicts = mutableMapOf<String, Boolean>()
        val paces = mutableMapOf<String, Double>()
        var rangeVerdictWrites = 0
        var paceWrites = 0

        override fun rangeVerdict(engineKey: String): Boolean? = verdicts[engineKey]

        override fun setRangeVerdict(
            engineKey: String,
            verdict: Boolean,
        ) {
            verdicts[engineKey] = verdict
            rangeVerdictWrites++
        }

        override fun msPerUnitAt1x(engineKey: String): Double? = paces[engineKey]

        override fun setMsPerUnitAt1x(
            engineKey: String,
            value: Double,
        ) {
            paces[engineKey] = value
            paceWrites++
        }
    }

    private class FakeLaneEngine : TtsSpeechEngine {
        data class Spoken(
            val text: String,
            val utteranceId: String,
        )

        val spoken = mutableListOf<Spoken>()
        val appliedRates = mutableListOf<Float>()
        private var startCallback: ((String?) -> Unit)? = null
        private var doneCallback: ((String?) -> Unit)? = null
        private var errorCallback: ((String?, Int) -> Unit)? = null
        private var rangeCallback: ((String?, Int, Int, Int) -> Unit)? = null
        private var stopCallback: ((String?, Boolean) -> Unit)? = null

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

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
            doneCallback = onDone
            errorCallback = onError
            rangeCallback = onRangeStart
            stopCallback = onStop
        }

        override fun clearCallbacks() {
            startCallback = null
            doneCallback = null
            errorCallback = null
            rangeCallback = null
            stopCallback = null
        }

        override fun speak(
            text: String,
            utteranceId: String,
        ): Int {
            spoken += Spoken(text, utteranceId)
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit

        fun start(index: Int) {
            startCallback?.invoke(spoken[index].utteranceId)
        }

        fun complete(index: Int) {
            doneCallback?.invoke(spoken[index].utteranceId)
        }

        fun range(
            index: Int,
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken[index].utteranceId, start, end, 0)
        }

        fun error(index: Int) {
            errorCallback?.invoke(spoken[index].utteranceId, TextToSpeech.ERROR_SYNTHESIS)
        }

        fun stopped(index: Int) {
            stopCallback?.invoke(spoken[index].utteranceId, true)
        }
    }

    private class FakeLaneFocus : TtsAudioFocus {
        override fun acquire(
            onFocusLoss: () -> Unit,
            onOwnerSurrender: () -> Unit,
        ): Boolean = true

        override fun release() = Unit
    }

    private companion object {
        const val ENGINE_KEY = "com.example.tts"

        fun verdictKey(locale: Locale = Locale.US): String = ttsRangeVerdictKey(ENGINE_KEY, locale)

        /**
         * Long enough to clear the probe's overturn threshold in one utterance,
         * and free of terminal punctuation so the chunker keeps it whole.
         */
        val OVERTURNING_TEXT =
            (
                "The capability probe needs a great deal of answerable text before it will " +
                    "overturn a verdict that storage already claims and that is deliberate "
            ).repeat(3)

        /** A gap that closes a sentence carries the handover and the breath. */
        const val SENTENCE_SEAM_MS = TTS_UTTERANCE_HANDOVER_MS + TTS_SENTENCE_BREATH_MS

        const val THREE_SENTENCES =
            "Alpha beta gamma delta epsilon zeta eta theta. " +
                "Iota kappa lambda mu nu xi omicron pi. " +
                "Rho sigma tau upsilon phi chi psi omega."

        const val TWO_SENTENCES =
            "Alpha beta gamma delta epsilon zeta eta theta. " +
                "Iota kappa lambda mu nu xi omicron pi."

        const val ONE_LONG_SENTENCE =
            "The quick brown fox jumps over the lazy dog while the calibrator listens carefully."
    }
}
