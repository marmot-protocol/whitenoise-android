package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class AndroidTtsSpeechEngineTest {
    /** Runs each hand-off inside [AndroidTtsSpeechEngine.speak], so a test observes it at once. */
    private val inline = Executor(Runnable::run)

    /** Holds hand-offs until [runHandOffs], so a test can act between submission and hand-off. */
    private val held = ArrayDeque<Runnable>()

    /** Queues a hand-off into [held]. */
    private val holdHandOffs = Executor { held.add(it) }

    /** Runs every held hand-off in submission order. */
    private fun runHandOffs() {
        while (held.isNotEmpty()) held.removeFirst().run()
    }

    /** An engine that requires an installed offline voice, as a saved or discovered one does. */
    private fun offlineEngine(
        tts: TextToSpeech,
        handOff: Executor = inline,
    ) = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a", requireOfflineVoice = true, handOff = handOff)

    /** Records refused sentences the way the queue's error callback receives them. */
    private fun AndroidTtsSpeechEngine.recordRefusals(): MutableList<String> {
        val refused = mutableListOf<String>()
        setCallbacks({}, {}, { id, code -> refused += "$id:$code" }, { _, _, _, _ -> }, { _, _ -> })
        return refused
    }

    /** Verifies the framework listener forwards every callback with its payload intact. */
    @Test
    fun progressListenerForwardsRangesStopsCompletionAndDetailedErrors() {
        val calls = mutableListOf<String>()
        val listener =
            androidTtsProgressListener(
                onStart = { calls += "start:$it" },
                onDone = { calls += "done:$it" },
                onError = { id, code -> calls += "error:$id:$code" },
                onRangeStart = { id, start, end, frame -> calls += "range:$id:$start:$end:$frame" },
                onStop = { id, interrupted -> calls += "stop:$id:$interrupted" },
            )

        listener.onStart("u1")
        listener.onRangeStart("u1", 2, 7, 11)
        listener.onStop("u1", true)
        listener.onDone("u1")
        listener.onError("u1", TextToSpeech.ERROR_NETWORK)

        assertEquals(
            listOf("start:u1", "range:u1:2:7:11", "stop:u1:true", "done:u1", "error:u1:${TextToSpeech.ERROR_NETWORK}"),
            calls,
        )
    }

    /** Preserves completion when an engine omits optional range callbacks. */
    @Test
    fun completionWithoutARangeStillForwardsDone() {
        val calls = mutableListOf<String>()
        val listener = listener(calls)

        listener.onDone("u1")

        assertEquals(listOf("done:u1"), calls)
    }

    /** Keeps an interrupted stop distinct from successful completion. */
    @Test
    fun stopWithoutDoneDoesNotSynthesizeCompletion() {
        val calls = mutableListOf<String>()
        val listener = listener(calls)

        listener.onStop("u1", true)

        assertEquals(listOf("stop:u1:true"), calls)
    }

    /** Carries the framework frame index through the adapter callback. */
    @Test
    fun adapterForwardsRangeFrame() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)
        var received: String? = null
        engine.setCallbacks(
            onStart = {},
            onDone = {},
            onError = { _, _ -> },
            onRangeStart = { id, start, end, frame -> received = "$id:$start:$end:$frame" },
            onStop = { _, _ -> },
        )

        tts.listener?.onRangeStart("whitenoise.tts.1.0", 4, 9, 12)

        assertEquals("whitenoise.tts.1.0:4:9:12", received)
    }

    /** Removes the framework listener when controller ownership ends. */
    @Test
    fun clearCallbacksRemovesTheUtteranceListener() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts)
        engine.setCallbacks(
            onStart = {},
            onDone = {},
            onError = { _, _ -> },
            onRangeStart = { _, _, _, _ -> },
            onStop = { _, _ -> },
        )

        engine.clearCallbacks()

        assertNull(tts.listener)
    }

    /** Confirms only media-mix speech carries a clamped Android volume bundle. */
    @Test
    fun mediaMixSpeechUsesBoundedFrameworkVolumeWhileOrdinarySpeechUsesNoBundle() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        val engine = AndroidTtsSpeechEngine(tts, handOff = inline)

        engine.speak("ordinary", "u1")
        assertNull(tts.lastParams)

        engine.speak("mixed", "u2", 3f)
        assertEquals(1f, tts.lastParams?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME))
        assertEquals("u2", tts.lastUtteranceId)
    }

    /** Applies the exact saved installed voice for the current engine. */
    @Test
    fun languageSelectionAppliesTheExactSavedOfflineVoice() {
        val fallback = voice("Fallback", Locale.US, 300)
        val selected = voice("Selected", Locale.UK, 200)
        val tts = VoiceSelectingTextToSpeech(fallback, setOf(fallback, selected))
        var resolution: TtsVoiceResolution? = null
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = tts,
                enginePackage = "engine.a",
                selectedVoice = { TtsVoiceKey("engine.a", "Selected", "en-GB") },
                onVoiceResolved = { resolution = it },
            )

        assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
        assertSame(selected, tts.voice)
        assertTrue(resolution?.isUsingRequestedVoice == true)
    }

    /** Falls back to an installed offline voice when the saved key disappears. */
    @Test
    fun missingSavedVoiceFallsBackDeterministicallyWithoutUsingNetworkSpeech() {
        val network = voice("Network", Locale.US, 500, networkRequired = true)
        val offline = voice("Offline", Locale.UK, 300)
        val tts = VoiceSelectingTextToSpeech(network, setOf(network, offline))
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = tts,
                enginePackage = "engine.a",
                selectedVoice = { TtsVoiceKey("engine.a", "Gone", "en-US") },
            )

        assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
        assertSame(offline, tts.voice)
    }

    /** A healthy engine can reject a preferred voice while accepting another installed offline voice. */
    @Test
    fun normalVoiceRejectionDoesNotRetireTheConnection() {
        val selected = voice("Selected", Locale.US, 500)
        val fallback = voice("Fallback", Locale.UK, 300)
        val tts = VoiceSelectingTextToSpeech(selected, setOf(selected, fallback), rejectedVoices = setOf(selected))
        var retirements = 0
        val engine =
            AndroidTtsSpeechEngine(
                tts,
                enginePackage = "engine.a",
                selectedVoice = { TtsVoiceKey("engine.a", "Selected", "en-US") },
                onConnectionUncertain = { retirements++ },
            )

        assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
        assertSame(fallback, tts.voice)
        assertEquals(0, retirements)
    }

    /** Refuses a language whose engine exposes only network speech. */
    @Test
    fun networkOnlyLanguageIsRejectedBeforeSpeechSubmission() {
        val network = voice("Network", Locale.US, 500, networkRequired = true)
        val tts = VoiceSelectingTextToSpeech(network, setOf(network))
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a")

        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, engine.setLanguage(Locale.US))
    }

    /** Keeps an installed ISO-639-2 voice usable for the equivalent ISO-639-1 utterance locale. */
    @Test
    fun equivalentThreeLetterLanguageVoiceRemainsUsable() {
        val english = voice("English ISO3", Locale.forLanguageTag("eng"), 300)
        val tts = VoiceSelectingTextToSpeech(english, setOf(english))
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a")

        assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
        assertSame(english, tts.voice)
    }

    /** Preserves an accepted framework language when an engine has no optional voice catalog. */
    @Test
    fun emptyVoiceCatalogKeepsTheFrameworkLanguageStatus() {
        val tts = EmptyCatalogTextToSpeech()
        var resolution: TtsVoiceResolution? = null
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = tts,
                enginePackage = "engine.a",
                onVoiceResolved = { resolution = it },
            )

        assertEquals(TextToSpeech.LANG_COUNTRY_AVAILABLE, engine.setLanguage(Locale.US))
        assertEquals(Locale.US.toLanguageTag(), resolution?.localeTag)
        assertTrue(resolution?.options?.isEmpty() == true)
        assertNull(resolution?.effectiveKey)
    }

    /** A required installed voice must still be available after language selection. */
    @Test
    fun offlinePlaybackRejectsAnEmptyCatalogAfterLanguageSelection() {
        val tts = EmptyCatalogTextToSpeech()
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = tts,
                enginePackage = "engine.a",
                requireOfflineVoice = true,
                handOff = inline,
            )
        val refused = engine.recordRefusals()

        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, engine.setLanguage(Locale.FRANCE))
        engine.speak("Private text", "start")
        engine.speak("Private text", "mix", volume = 0.5f)

        assertEquals(listOf("start:${TextToSpeech.ERROR}"), refused)
        assertEquals(0, tts.spoken)
    }

    /** A failed framework call retires authorization for all later chunks on that adapter. */
    @Test
    fun frameworkFailureRetiresTheConnectionEvenWhenTheSameObjectRecovers() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        var retirements = 0
        val engine = AndroidTtsSpeechEngine(tts, onConnectionUncertain = { retirements++ }, handOff = inline)
        val refused = engine.recordRefusals()
        tts.speakResult = TextToSpeech.ERROR
        engine.speak("First chunk", "first")

        tts.speakResult = TextToSpeech.SUCCESS
        assertEquals(TextToSpeech.ERROR, engine.speak("Later chunk", "later"))
        assertEquals("first", tts.lastUtteranceId)
        assertEquals(listOf("first:${TextToSpeech.ERROR}"), refused)
        assertEquals(1, retirements)
    }

    /** A saved offline preference still applies when an engine stops exposing a voice catalog. */
    @Test
    fun savedOfflinePreferenceCannotBeLostWithAnEmptyCatalog() {
        val tts = EmptyCatalogTextToSpeech()
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = tts,
                enginePackage = "engine.a",
                selectedVoice = { TtsVoiceKey("engine.a", "Saved", "en-US") },
                handOff = inline,
            )
        val refused = engine.recordRefusals()

        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, engine.setLanguage(Locale.US))
        engine.speak("Fixture text", "saved")
        assertEquals(listOf("saved:${TextToSpeech.ERROR}"), refused)
        assertEquals(0, tts.spoken)
    }

    /** The installed requirement also applies when Android advertises a non-network voice that is downloading. */
    @Test
    fun voiceMarkedNotInstalledCannotReceiveSpeech() {
        val voice =
            Voice(
                "Downloading",
                Locale.US,
                300,
                100,
                false,
                setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
            )
        val tts = CountingVoiceTextToSpeech(voice)
        val engine = offlineEngine(tts)
        val refused = engine.recordRefusals()

        engine.speak("Fixture text", "unavailable")
        assertEquals(listOf("unavailable:${TextToSpeech.ERROR}"), refused)
        assertEquals(emptyList<String>(), tts.spokenIds)
    }

    /**
     * Every sentence is checked and handed to the service on the hand-off thread, never on the caller's thread, so a
     * long message neither blocks the caller on the voice catalogue nor skips a sentence's check.
     */
    @Test
    fun everySentenceIsCheckedOffTheCallingThread() {
        val tts = CountingVoiceTextToSpeech(voice("Offline", Locale.US, 300), expectedSpeaks = 50)
        // The production hand-off thread, not a test executor.
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a", requireOfflineVoice = true)

        repeat(50) { assertEquals(TextToSpeech.SUCCESS, engine.speak("Sentence $it.", "u$it")) }

        assertTrue(tts.allSpoken.await(5, TimeUnit.SECONDS))
        assertEquals((0 until 50).map { "u$it" }, tts.spokenIds)
        assertEquals(50, tts.voiceReads)
        assertTrue(Thread.currentThread() !in tts.callingThreads)
    }

    /** A voice withdrawn after one sentence is handed off keeps the next one, and the rest of its queue, in the app. */
    @Test
    fun voiceWithdrawnBetweenHandOffsKeepsLaterSentencesInTheApp() {
        val offline = voice("Offline", Locale.US, 300)
        val tts = CountingVoiceTextToSpeech(offline)
        val engine = offlineEngine(tts, holdHandOffs)
        val refused = engine.recordRefusals()
        engine.speak("First.", "u1")
        engine.speak("Second.", "u2")
        engine.speak("Third.", "u3")

        held.removeFirst().run()
        tts.activeVoice = voice("Network", Locale.US, 300, networkRequired = true)
        runHandOffs()

        assertEquals(listOf("u1"), tts.spokenIds)
        assertEquals(listOf("u2:${TextToSpeech.ERROR}"), refused)

        // The queue's stop starts a new queue, which checks the voice afresh.
        engine.stop()
        tts.activeVoice = offline
        engine.speak("Again.", "u4")
        runHandOffs()
        assertEquals(listOf("u1", "u4"), tts.spokenIds)
    }

    /** Sentences submitted before a stop are dropped, not handed to the service after it. */
    @Test
    fun stopDropsSentencesNotYetHandedOff() {
        val tts = CountingVoiceTextToSpeech(voice("Offline", Locale.US, 300))
        val engine = offlineEngine(tts, holdHandOffs)
        engine.speak("Old first.", "u1")
        engine.speak("Old second.", "u2")

        engine.stop()
        engine.speak("New.", "u3")
        runHandOffs()

        assertEquals(listOf("stop", "u3"), tts.events)
    }

    /**
     * With a catalogue connection, a stop during a blocked voice lookup reaches the framework at once, without waiting
     * for the lookup, and the stopped sentence never reaches the engine once the lookup returns.
     */
    @Test
    fun stopDuringACatalogueLookupStopsAtOnceAndNeverSpeaks() {
        val offline = voice("Offline", Locale.US, 300)
        val tts = CountingVoiceTextToSpeech(offline)
        val lookup = CountDownLatch(1)
        val catalogue = FakeCatalogue(listOf(offline), gate = lookup)
        onHandOffThread { thread ->
            val engine = catalogueEngine(tts, catalogue, thread)
            engine.speak("Old account.", "u1")
            assertTrue(catalogue.started.await(5, TimeUnit.SECONDS))

            engine.stop()
            assertEquals("the framework stop must not wait for the lookup", listOf("stop"), tts.events)
            assertTrue(!catalogue.finished)
            lookup.countDown()
            thread.submit {}.get(5, TimeUnit.SECONDS)

            assertEquals(listOf("stop"), tts.events)
        }
    }

    /** A stop while a sentence's rate is being applied still keeps that sentence from reaching the engine. */
    @Test
    fun stopWhileTheRateIsAppliedNeverSpeaks() {
        val offline = voice("Offline", Locale.US, 300)
        val rateWrite = CountDownLatch(1)
        val tts = CountingVoiceTextToSpeech(offline, rateGate = rateWrite)
        onHandOffThread { thread ->
            val engine = catalogueEngine(tts, FakeCatalogue(listOf(offline)), thread)
            engine.setSpeechRate(1.5f)
            engine.speak("Old account.", "u1")
            assertTrue(tts.rateStarted.await(5, TimeUnit.SECONDS))

            engine.stop()
            rateWrite.countDown()
            thread.submit {}.get(5, TimeUnit.SECONDS)

            assertEquals(listOf("stop"), tts.events)
        }
    }

    /** A language change during a blocked lookup returns without waiting for it, and the sentence checks again. */
    @Test
    fun languageChangeDuringACatalogueLookupDoesNotWaitAndRechecks() {
        val offline = voice("Offline", Locale.US, 300)
        val tts = CountingVoiceTextToSpeech(offline)
        val lookup = CountDownLatch(1)
        val catalogue = FakeCatalogue(listOf(offline), gate = lookup)
        onHandOffThread { thread ->
            val engine = catalogueEngine(tts, catalogue, thread)
            engine.speak("First.", "u1")
            assertTrue(catalogue.started.await(5, TimeUnit.SECONDS))

            assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
            assertTrue("setLanguage waited for the lookup", !catalogue.finished)
            lookup.countDown()
            thread.submit {}.get(5, TimeUnit.SECONDS)

            assertEquals(listOf("u1"), tts.spokenIds)
            assertEquals("checked again after the language change", 2, catalogue.reads.get())
        }
    }

    /** Each sentence's check reads the catalogue connection, never the playback connection's voice. */
    @Test
    fun voiceChecksReadTheCatalogueConnectionNotPlayback() {
        val offline = voice("Offline", Locale.US, 300)
        val tts = CountingVoiceTextToSpeech(offline)
        val catalogue = FakeCatalogue(listOf(offline))
        val engine = catalogueEngine(tts, catalogue, inline)
        val readsAfterSetup = tts.voiceReads

        repeat(3) { engine.speak("Sentence $it.", "u$it") }

        assertEquals(listOf("u0", "u1", "u2"), tts.spokenIds)
        assertEquals(3, catalogue.reads.get())
        assertEquals(readsAfterSetup, tts.voiceReads)
    }

    /** A voice the catalogue stops listing as installed refuses the next sentence and keeps the rest in the app. */
    @Test
    fun voiceWithdrawnFromTheCatalogueRefusesTheNextSentence() {
        val offline = voice("Offline", Locale.US, 300)
        val tts = CountingVoiceTextToSpeech(offline)
        val catalogue = FakeCatalogue(listOf(offline))
        val engine = catalogueEngine(tts, catalogue, holdHandOffs)
        val refused = engine.recordRefusals()
        repeat(3) { engine.speak("Sentence $it.", "u$it") }

        held.removeFirst().run()
        catalogue.voices = listOf(voice("Offline", Locale.US, 300, networkRequired = true))
        runHandOffs()

        assertEquals(listOf("u0"), tts.spokenIds)
        assertEquals(listOf("u1:${TextToSpeech.ERROR}"), refused)
    }

    /** An offline engine whose voice checks read [catalogue], after the language change that applies its voice. */
    private fun catalogueEngine(
        tts: CountingVoiceTextToSpeech,
        catalogue: FakeCatalogue,
        handOff: Executor,
    ): AndroidTtsSpeechEngine {
        val engine =
            AndroidTtsSpeechEngine(
                tts,
                enginePackage = "engine.a",
                requireOfflineVoice = true,
                voiceCatalogue = catalogue,
                handOff = handOff,
            )
        assertEquals(TextToSpeech.LANG_AVAILABLE, engine.setLanguage(Locale.US))
        catalogue.reads.set(0)
        return engine
    }

    /** Runs [block] with a fresh single hand-off thread, shut down afterwards. */
    private fun onHandOffThread(block: (ExecutorService) -> Unit) {
        val thread = Executors.newSingleThreadExecutor()
        try {
            block(thread)
        } finally {
            thread.shutdownNow()
        }
    }

    /** Each sentence reaches the service at the rate the controller set before submitting it. */
    @Test
    fun eachSentenceKeepsTheRateSetBeforeIt() {
        val tts = CountingVoiceTextToSpeech(voice("Offline", Locale.US, 300))
        val engine = offlineEngine(tts, holdHandOffs)
        engine.setSpeechRate(1.5f)
        engine.speak("Slow.", "u1")
        engine.setSpeechRate(2f)
        engine.speak("Fast.", "u2")

        runHandOffs()

        assertEquals(listOf(1.5f, 2f), tts.spokenRates)
    }

    /** Builds a listener that records each lifecycle callback in order. */
    private fun listener(calls: MutableList<String>) =
        androidTtsProgressListener(
            onStart = { calls += "start:$it" },
            onDone = { calls += "done:$it" },
            onError = { id, code -> calls += "error:$id:$code" },
            onRangeStart = { id, start, end, frame -> calls += "range:$id:$start:$end:$frame" },
            onStop = { id, interrupted -> calls += "stop:$id:$interrupted" },
        )

    /** Creates framework voices with the exact availability needed by each case. */
    private fun voice(
        name: String,
        locale: Locale,
        quality: Int,
        networkRequired: Boolean = false,
    ) = Voice(name, locale, quality, 100, networkRequired, emptySet())

    private class VoiceSelectingTextToSpeech(
        initialVoice: Voice,
        private val availableVoices: Set<Voice>,
        private val rejectedVoices: Set<Voice> = emptySet(),
    ) : TextToSpeech(RuntimeEnvironment.getApplication(), {}) {
        private var activeVoice = initialVoice

        /** Models an engine that accepts the requested locale. */
        override fun setLanguage(locale: Locale?): Int = TextToSpeech.LANG_AVAILABLE

        /** Returns installed and unavailable voices exactly as configured. */
        override fun getVoices(): MutableSet<Voice> = availableVoices.toMutableSet()

        /** Exposes the voice last accepted by the fake engine. */
        override fun getVoice(): Voice = activeVoice

        /** Accepts only catalog voices so fallback failures remain observable. */
        override fun setVoice(voice: Voice?): Int {
            if (voice == null || voice !in availableVoices || voice in rejectedVoices) return TextToSpeech.ERROR
            activeVoice = voice
            return TextToSpeech.SUCCESS
        }
    }

    /** Models a usable engine that relies on its default voice and exposes no catalog. */
    private class EmptyCatalogTextToSpeech : TextToSpeech(RuntimeEnvironment.getApplication(), {}) {
        var spoken = 0
            private set

        override fun setLanguage(locale: Locale?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

        override fun getVoices(): MutableSet<Voice> = mutableSetOf()

        /** Counts utterances that reach the engine. */
        override fun speak(
            text: CharSequence?,
            queueMode: Int,
            params: Bundle?,
            utteranceId: String?,
        ): Int {
            spoken++
            return TextToSpeech.SUCCESS
        }
    }

    /** Counts voice reads and records each utterance, its rate and the thread that handed it off. */
    private class CountingVoiceTextToSpeech(
        @Volatile var activeVoice: Voice,
        expectedSpeaks: Int = 0,
        private val lookupGate: CountDownLatch? = null,
        private val rateGate: CountDownLatch? = null,
    ) : TextToSpeech(RuntimeEnvironment.getApplication(), {}) {
        val allSpoken = CountDownLatch(expectedSpeaks)
        val lookupStarted = CountDownLatch(1)
        val rateStarted = CountDownLatch(1)

        @Volatile
        var lookupFinished = false
            private set
        val spokenIds: MutableList<String?> = Collections.synchronizedList(mutableListOf())

        /** Utterance ids and framework stops, in the order the engine received them. */
        val events: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val spokenRates = mutableListOf<Float>()
        val callingThreads: MutableSet<Thread> = Collections.synchronizedSet(mutableSetOf())

        @Volatile
        var voiceReads = 0
            private set
        private var rate = 1f

        /** Counts each binder-backed voice read and the thread making it. */
        override fun getVoice(): Voice {
            callingThreads += Thread.currentThread()
            voiceReads++
            lookupStarted.countDown()
            lookupGate?.await(5, TimeUnit.SECONDS)
            lookupFinished = true
            return activeVoice
        }

        /** Records the rate the next utterance uses, optionally blocking while a test acts. */
        override fun setSpeechRate(speechRate: Float): Int {
            rateStarted.countDown()
            rateGate?.await(5, TimeUnit.SECONDS)
            rate = speechRate
            return TextToSpeech.SUCCESS
        }

        /** Models an engine that accepts the requested locale. */
        override fun setLanguage(locale: Locale?): Int = TextToSpeech.LANG_AVAILABLE

        /** Offers only the active voice. */
        override fun getVoices(): MutableSet<Voice> = mutableSetOf(activeVoice)

        /** Accepts the active voice. */
        override fun setVoice(voice: Voice?): Int {
            val accepted = voice == activeVoice
            return if (accepted) TextToSpeech.SUCCESS else TextToSpeech.ERROR
        }

        /** Accepts every utterance without synthesizing audio. */
        override fun speak(
            text: CharSequence?,
            queueMode: Int,
            params: Bundle?,
            utteranceId: String?,
        ): Int {
            callingThreads += Thread.currentThread()
            spokenIds += utteranceId
            events += utteranceId
            spokenRates += rate
            allSpoken.countDown()
            return TextToSpeech.SUCCESS
        }

        /** Records the stop without a framework connection. */
        override fun stop(): Int {
            events += "stop"
            return TextToSpeech.SUCCESS
        }
    }

    /** A catalogue connection whose reads a test can count, hold, and change. */
    private class FakeCatalogue(
        @Volatile var voices: List<Voice>,
        private val gate: CountDownLatch? = null,
    ) : TtsVoiceCatalogue {
        val reads = AtomicInteger()
        val started = CountDownLatch(1)

        @Volatile
        var finished = false
            private set

        /** Counts the read, then holds it on [gate] before returning [voices]. */
        override fun voices(): Collection<Voice> {
            reads.incrementAndGet()
            started.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            finished = true
            return voices
        }
    }

    private class CapturingTextToSpeech(
        context: Context,
    ) : TextToSpeech(context, {}) {
        var listener: UtteranceProgressListener? = null
            private set
        var lastParams: Bundle? = null
            private set
        var lastUtteranceId: String? = null
            private set
        var speakResult: Int = TextToSpeech.SUCCESS

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
            this.listener = listener
            return super.setOnUtteranceProgressListener(listener)
        }

        /** Captures the framework bundle and utterance identity without synthesizing audio. */
        override fun speak(
            text: CharSequence?,
            queueMode: Int,
            params: Bundle?,
            utteranceId: String?,
        ): Int {
            lastParams = params
            lastUtteranceId = utteranceId
            return speakResult
        }
    }
}
