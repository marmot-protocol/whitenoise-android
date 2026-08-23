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
 * End-to-end behavior of the estimated word-timing lane through the
 * controller: synthetic range callbacks flow through the same queue validation
 * as engine ranges, real engine ranges win permanently, and the probe and
 * calibrator learn and persist per engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
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
    fun anUnusableEngineRangeDoesNotDisableTheEstimate() =
        runTest {
            val harness = LaneHarness(this)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            harness.engine.range(index = 0, start = 0, end = 0)

            assertEquals(null, harness.store.verdicts[ENGINE_KEY])
            advanceTimeBy(150)
            runCurrent()
            assertEquals(
                listOf(TtsVisibleTextSpan("b0/n0", 0, 5)),
                harness.controller.state.value.passage?.visibleWord,
            )
        }

    @Test
    fun aPersistedCapableVerdictNeverArmsTheEstimate() =
        runTest {
            val harness = LaneHarness(this)
            harness.store.verdicts[ENGINE_KEY] = true
            harness.controller.detachEngine()
            harness.controller.attachEngine(harness.engine, engineKey = ENGINE_KEY)
            assertTrue(harness.controller.speak(listOf(plainEntry()), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(2_000)
            runCurrent()

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
    fun utteranceDurationCalibratesThePace() =
        runTest {
            val harness = LaneHarness(this)
            val text = "The quick brown fox jumps over the lazy dog while the calibrator listens carefully."
            assertTrue(harness.controller.speak(listOf(plainEntry(text)), Locale.US))

            harness.engine.start(index = 0)
            advanceTimeBy(6_000)
            harness.engine.complete(index = 0)

            val learned = harness.store.paces[ENGINE_KEY]
            assertTrue("expected a persisted pace, got $learned", learned != null)
            val expected =
                (6_000.0 - TTS_ESTIMATED_AUDIO_LEAD_IN_MS) /
                    TtsWordTimingEstimate.weightedLengthOf(text)
            // One sample moves the estimate a quarter of the way from the default.
            val blended = 17.5 * 0.75 + expected * 0.25
            assertEquals(blended, learned!!, 0.5)
        }

    @Test
    fun timingUsesTheRateAppliedWhenTheUtteranceWasEnqueued() =
        runTest {
            val harness = LaneHarness(this)
            val text = "The quick brown fox jumps over the lazy dog while the calibrator listens carefully."
            harness.rate = 1.0f
            assertTrue(harness.controller.speak(listOf(plainEntry(text)), Locale.US))

            harness.rate = 2.0f
            harness.engine.start(index = 0)
            advanceTimeBy(6_000)
            harness.engine.complete(index = 0)

            val expected =
                (6_000.0 - TTS_ESTIMATED_AUDIO_LEAD_IN_MS) /
                    TtsWordTimingEstimate.weightedLengthOf(text)
            val blended = 17.5 * 0.75 + expected * 0.25
            assertEquals(blended, harness.store.paces[ENGINE_KEY]!!, 0.5)
            assertEquals(listOf(1.0f), harness.engine.appliedRates)
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
        scope: TestScope,
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
    }

    private class FakeTimingStore : TtsTimingStore {
        val verdicts = mutableMapOf<String, Boolean>()
        val paces = mutableMapOf<String, Double>()

        override fun rangeVerdict(engineKey: String): Boolean? = verdicts[engineKey]

        override fun setRangeVerdict(
            engineKey: String,
            verdict: Boolean,
        ) {
            verdicts[engineKey] = verdict
        }

        override fun msPerUnitAt1x(engineKey: String): Double? = paces[engineKey]

        override fun setMsPerUnitAt1x(
            engineKey: String,
            value: Double,
        ) {
            paces[engineKey] = value
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
            rangeCallback = onRangeStart
            stopCallback = onStop
        }

        override fun clearCallbacks() {
            startCallback = null
            doneCallback = null
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
    }
}
