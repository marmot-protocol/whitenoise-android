package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Behavior of the estimated word-timing lane through the real controller and
 * queue: synthetic range callbacks flow through the same validation as engine
 * ranges, real engine ranges win permanently, and the probe and calibrator
 * learn and persist per engine.
 *
 * What the harness does NOT model, so that claims resting on these are not
 * mistaken for tested ones: the engine is a fake that never delivers a callback
 * synchronously from inside speak() or stop(), never delivers one on another
 * thread, and imposes no ordering of its own. Anything asserted here about what
 * a real engine WILL do - that Android does not repeat onStart after onDone,
 * for instance - is a statement about the platform that this harness cannot
 * make, only a statement about how the controller reacts if it happens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // Estimated timing, range capability, and pace calibration share one engine/controller harness.
class TtsEstimatedTimingLaneTest {
    @Test
    fun rangeSilentEngineGetsEstimatedWordHighlights() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )

            advanceTimeBy(600)
            runCurrent()

            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 6, 11)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun aRealEngineRangeSilencesTheEstimatePermanently() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)

            assertEquals(true, harness.store.verdicts[ENGINE_KEY])

            // The estimate would move on to the second word around here; the
            // engine's word must survive untouched.
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun unusableEngineRangesDoNotDisableOrClobberTheEstimate() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(150)
            runCurrent()
            val estimatedPassage = harness.controller.state.value.passage
            val estimatedProgress =
                (harness.controller.state.value as TtsState.Speaking).messageProgressFraction

            harness.engine.range(index = 0, start = 0, end = 0)

            assertEquals(null, harness.store.verdicts[ENGINE_KEY])
            assertEquals(estimatedPassage, harness.controller.state.value.passage)
            assertEquals(
                estimatedProgress,
                (harness.controller.state.value as TtsState.Speaking).messageProgressFraction,
            )

            advanceTimeBy(500)
            runCurrent()
            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 6, 11)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun aRestoredCapableVerdictUsesEstimatesUntilAUsableRangeReconfirmsIt() =
        runTest {
            val harness = LaneHarness(this)
            harness.store.verdicts[ENGINE_KEY] = true
            harness.controller.detachEngine()
            harness.controller.attachEngine(harness.engine, engineKey = ENGINE_KEY)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(150)
            runCurrent()

            val estimatedPassage = harness.controller.state.value.passage
            assertEquals(0, estimatedPassage?.sentenceIndex)
            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
                estimatedPassage?.visibleWord,
            )

            // A provisional stored verdict must not let an unusable engine
            // callback erase the estimated word that is keeping speech visible.
            harness.engine.range(index = 0, start = 0, end = 0)
            assertEquals(estimatedPassage, harness.controller.state.value.passage)

            // The first usable real callback reconfirms the restored verdict,
            // persists that fresh evidence, and retires the estimator.
            harness.engine.range(index = 0, start = 6, end = 11)
            assertEquals(1, harness.store.rangeVerdictWrites)
            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 6, 11)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )

            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 6, 11)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun unusableRangeFromConfirmedCapableEngineClearsThePreviousWord() =
        runTest {
            val harness = LaneHarness(this)
            harness.store.verdicts[ENGINE_KEY] = true
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
    fun aPersistedSilentVerdictStillRunsTheEstimate() =
        runTest {
            val harness = LaneHarness(this)
            harness.store.verdicts[ENGINE_KEY] = false
            harness.controller.detachEngine()
            harness.controller.attachEngine(harness.engine, engineKey = ENGINE_KEY)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
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

            assertEquals(false, harness.store.verdicts[ENGINE_KEY])
        }

    @Test
    fun aPersistedCapableVerdictIsOverturnedWhenTheEngineStopsReportingRanges() =
        runTest {
            val harness = LaneHarness(this)
            harness.store.verdicts[ENGINE_KEY] = true
            harness.controller.detachEngine()
            harness.controller.attachEngine(harness.engine, engineKey = ENGINE_KEY)
            assertTrue(harness.controller.speak(listOf(plainEntry(OVERTURNING_TEXT)), Locale.US))

            harness.engine.start(index = 0)
            harness.engine.complete(index = 0)

            assertEquals(false, harness.store.verdicts[ENGINE_KEY])

            // ...and the estimate takes over from the next utterance, which is
            // the whole point of overturning it.
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 1)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
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

            assertEquals(null, harness.store.verdicts[ENGINE_KEY])

            // Witness: the same text WITH a visible mapping does conclude, so
            // the assertion above is about the mapping and not about length.
            assertTrue(harness.controller.speak(listOf(plainEntry(OVERTURNING_TEXT)), Locale.US))
            harness.engine.start(index = 1)
            harness.engine.complete(index = 1)

            assertEquals(false, harness.store.verdicts[ENGINE_KEY])
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

            assertEquals(null, harness.store.verdicts[ENGINE_KEY])

            // Witness: the same text fully mapped does conclude, so this is
            // about the mapping and not about the length.
            assertTrue(harness.controller.speak(listOf(plainEntry(OVERTURNING_TEXT)), Locale.US))
            harness.engine.start(index = 1)
            harness.engine.complete(index = 1)

            assertEquals(false, harness.store.verdicts[ENGINE_KEY])
        }

    @Test
    fun anEmojiOnlyMessageIsNotEvidenceAgainstTheEngine() =
        runTest {
            val harness = LaneHarness(this)
            val emoji = "\uD83D\uDE42".repeat(80)
            assertTrue(harness.controller.speak(listOf(plainEntry(emoji)), Locale.US))

            harness.engine.start(index = 0)
            harness.engine.complete(index = 0)

            assertEquals(null, harness.store.verdicts[ENGINE_KEY])
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
    fun aGapTooSmallForTheCalibratorLeavesTheBootstrapInPlace() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry(LONG_SHORT_THEN_EIGHT)), Locale.US))

            // A long first utterance gives the bootstrap something to learn.
            harness.engine.start(index = 0)
            advanceTimeBy(9_000)
            harness.engine.complete(index = 0)

            // The second sentence is too short for the calibrator to believe,
            // so its gap is measured and then rejected. The bootstrap must
            // survive that: it is still the best thing this process knows.
            harness.engine.start(index = 1)
            advanceTimeBy(2_000)
            harness.engine.complete(index = 1)
            harness.engine.start(index = 2)
            assertTrue(harness.store.paces.isEmpty())

            val fresh = LaneHarness(this)
            assertTrue(fresh.controller.speak(listOf(plainEntry(EIGHT_WORDS)), Locale.US))
            fresh.engine.start(index = 0)
            advanceTimeBy(1_500)
            runCurrent()

            val slowed =
                harness.controller.state.value.passage
                    ?.visibleWord
                    .orEmpty()
            val seeded =
                fresh.controller.state.value.passage
                    ?.visibleWord
                    .orEmpty()
            assertTrue("expected a word on both readers", slowed.isNotEmpty() && seeded.isNotEmpty())
            // The two readers paint the same words, but one is deep inside a
            // longer message, so compare each word offset against the start of
            // the sentence it belongs to rather than against the payload.
            val slowedWithinSentence = slowed.first().start - LONG_SHORT_THEN_EIGHT.indexOf(EIGHT_WORDS)
            assertTrue(
                "the rejected gap must not have discarded what the bootstrap learned",
                slowedWithinSentence < seeded.first().start,
            )
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
    fun aBootstrapSampleStillSteersTheEstimate() =
        runTest {
            val bootstrapped = LaneHarness(this)
            assertTrue(bootstrapped.controller.speak(listOf(plainEntry(ONE_LONG_SENTENCE)), Locale.US))
            bootstrapped.engine.start(index = 0)
            advanceTimeBy(9_000)
            bootstrapped.engine.complete(index = 0)

            // A second engine that has heard nothing keeps the seeded pace.
            val fresh = LaneHarness(this)
            assertTrue(bootstrapped.controller.speak(listOf(plainEntry(EIGHT_WORDS)), Locale.US))
            assertTrue(fresh.controller.speak(listOf(plainEntry(EIGHT_WORDS)), Locale.US))
            bootstrapped.engine.start(index = 1)
            fresh.engine.start(index = 0)
            advanceTimeBy(1_500)
            runCurrent()

            val slowed =
                bootstrapped.controller.state.value.passage
                    ?.visibleWord
                    .orEmpty()
            val seeded =
                fresh.controller.state.value.passage
                    ?.visibleWord
                    .orEmpty()
            assertTrue("expected a word on both readers", slowed.isNotEmpty() && seeded.isNotEmpty())
            assertTrue(
                "a slower measured voice must not be further along than the seeded one",
                slowed.first().start < seeded.first().start,
            )
        }

    @Test
    fun anUtteranceReportedStartedBeforeItsPredecessorFinishedIsNotTracked() =
        runTest {
            // An engine that pipelines - reporting the next start while the
            // previous utterance is still current - fails the queue's own
            // validation gate, so it opens no gap. It also arms no estimated
            // schedule, and Android will not repeat the callback: that
            // utterance simply has no word marker. Asserted so the limitation
            // is recorded where it lives rather than in a comment.
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
    fun pauseFreezesThePassageAndStopsTheSchedule() =
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
    fun aStaleSyntheticEventCannotPaintAfterNavigation() =
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
            assertEquals(null, harness.store.verdicts[ENGINE_KEY])

            // Ticks armed for the first utterance are stale for the new
            // generation; the new message keeps its sentence-level passage.
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
    fun aLateStopFromAReplacedUtteranceCannotKillTheNewSchedule() =
        runTest {
            val harness = LaneHarness(this)
            val first = plainEntry("Hello world.", messageIdHex = "m1")
            val second = plainEntry("Goodbye world.", messageIdHex = "m2")
            assertTrue(harness.controller.speak(listOf(first, second), Locale.US))
            harness.engine.start(index = 0)
            runCurrent()

            assertEquals(TtsNavigationOutcome.Moved, harness.controller.skipNextMessage())
            // The requeue re-submitted m2 as a new utterance...
            harness.engine.start(index = harness.engine.spoken.lastIndex)
            // ...and only then does the engine deliver the old utterance's stop.
            harness.engine.stopped(index = 0)

            advanceTimeBy(300)
            runCurrent()

            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 7)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun resumeReArmsTheScheduleForTheResumedUtterance() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            advanceTimeBy(150)
            runCurrent()
            harness.controller.pause()

            harness.controller.resume()
            // Resume re-enqueued the chunk as a fresh utterance.
            harness.engine.start(index = harness.engine.spoken.lastIndex)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
                harness.controller.state.value.passage
                    ?.visibleWord,
            )
        }

    @Test
    fun anEngineErrorEndsTheScheduleWithTheSession() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            runCurrent()

            harness.engine.error(index = 0)
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(harness.controller.state.value is TtsState.Error)
        }

    @Test
    fun verdictsNeverCrossEngines() =
        runTest {
            val harness = LaneHarness(this)
            // Engine A proves itself range-capable.
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 5)
            assertEquals(true, harness.store.verdicts[ENGINE_KEY])

            // A different engine must not inherit A's verdict: the estimate
            // runs for it while its own capability is unknown.
            val second = FakeLaneEngine()
            harness.controller.attachEngine(second, engineKey = "com.other.tts")
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))
            second.start(index = 0)
            advanceTimeBy(150)
            runCurrent()

            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
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
                wordTicker =
                    TtsEstimatedWordTicker(
                        dispatcher = StandardTestDispatcher(scope.testScheduler),
                        clock = { scope.testScheduler.currentTime },
                    ),
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

        const val EIGHT_WORDS = "Alpha bravo charlie delta echo foxtrot golf hotel."

        /**
         * A long sentence for the bootstrap to learn from, then one too short
         * for the calibrator to believe, then eight words to paint.
         */
        const val LONG_SHORT_THEN_EIGHT = "$ONE_LONG_SENTENCE Alpha beta. $EIGHT_WORDS"
    }
}
