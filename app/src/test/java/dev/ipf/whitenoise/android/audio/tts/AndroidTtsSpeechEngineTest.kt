package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.os.Bundle
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AndroidTtsSpeechEngineTest {
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
        val engine = AndroidTtsSpeechEngine(tts)

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
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = EmptyCatalogTextToSpeech(),
                enginePackage = "engine.a",
                requireOfflineVoice = true,
            )

        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, engine.setLanguage(Locale.FRANCE))
        assertEquals(TextToSpeech.ERROR, engine.speak("Private text", "start"))
        assertEquals(TextToSpeech.ERROR, engine.speak("Private text", "mix", volume = 0.5f))
    }

    /** A failed framework call retires authorization for all later chunks on that adapter. */
    @Test
    fun frameworkFailureRetiresTheConnectionEvenWhenTheSameObjectRecovers() {
        val tts = CapturingTextToSpeech(RuntimeEnvironment.getApplication())
        var retirements = 0
        val engine = AndroidTtsSpeechEngine(tts, onConnectionUncertain = { retirements++ })
        tts.speakResult = TextToSpeech.ERROR
        assertEquals(TextToSpeech.ERROR, engine.speak("First chunk", "first"))

        tts.speakResult = TextToSpeech.SUCCESS
        assertEquals(TextToSpeech.ERROR, engine.speak("Later chunk", "later"))
        assertEquals("first", tts.lastUtteranceId)
        assertEquals(1, retirements)
    }

    /** A saved offline preference still applies when an engine stops exposing a voice catalog. */
    @Test
    fun savedOfflinePreferenceCannotBeLostWithAnEmptyCatalog() {
        val engine =
            AndroidTtsSpeechEngine(
                textToSpeech = EmptyCatalogTextToSpeech(),
                enginePackage = "engine.a",
                selectedVoice = { TtsVoiceKey("engine.a", "Saved", "en-US") },
            )

        assertEquals(TextToSpeech.LANG_NOT_SUPPORTED, engine.setLanguage(Locale.US))
        assertEquals(TextToSpeech.ERROR, engine.speak("Fixture text", "saved"))
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
        val tts = VoiceSelectingTextToSpeech(voice, setOf(voice))
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a", requireOfflineVoice = true)

        assertEquals(TextToSpeech.ERROR, engine.speak("Fixture text", "unavailable"))
    }

    /** A batch submitted in one main-thread task checks the framework voice once, not once per sentence. */
    @Test
    fun oneBatchChecksTheFrameworkVoiceOnce() {
        val tts = CountingVoiceTextToSpeech(voice("Offline", Locale.US, 300))
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a", requireOfflineVoice = true)
        engine.setLanguage(Locale.US)
        shadowOf(Looper.getMainLooper()).idle()
        tts.voiceReads = 0

        repeat(50) { assertEquals(TextToSpeech.SUCCESS, engine.speak("Sentence $it.", "u$it")) }

        assertEquals(1, tts.voiceReads)
        assertEquals(50, tts.spoken)
    }

    /** The next task checks the voice again, so a voice that stopped being installed refuses the next batch. */
    @Test
    fun aLaterBatchChecksTheVoiceAgain() {
        val offline = voice("Offline", Locale.US, 300)
        val tts = CountingVoiceTextToSpeech(offline)
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a", requireOfflineVoice = true)
        engine.setLanguage(Locale.US)
        assertEquals(TextToSpeech.SUCCESS, engine.speak("First batch.", "u1"))

        tts.activeVoice = voice("Network", Locale.US, 300, networkRequired = true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(TextToSpeech.ERROR, engine.speak("Second batch.", "u2"))
        assertEquals(1, tts.spoken)
    }

    /** A stop inside the task drops the confirmation, so speech queued after it checks the voice again. */
    @Test
    fun stopDropsTheConfirmedVoiceWithinTheTask() {
        val tts = CountingVoiceTextToSpeech(voice("Offline", Locale.US, 300))
        val engine = AndroidTtsSpeechEngine(tts, enginePackage = "engine.a", requireOfflineVoice = true)
        engine.setLanguage(Locale.US)
        tts.voiceReads = 0

        engine.speak("Before stop.", "u1")
        engine.stop()
        engine.speak("After stop.", "u2")

        assertEquals(2, tts.voiceReads)
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

    /** Counts framework voice reads and accepted utterances for an engine with one active voice. */
    private class CountingVoiceTextToSpeech(
        var activeVoice: Voice,
    ) : TextToSpeech(RuntimeEnvironment.getApplication(), {}) {
        var voiceReads = 0
        var spoken = 0
            private set

        /** Models an engine that accepts the requested locale. */
        override fun setLanguage(locale: Locale?): Int = TextToSpeech.LANG_AVAILABLE

        /** Offers only the active voice. */
        override fun getVoices(): MutableSet<Voice> = mutableSetOf(activeVoice)

        /** Counts each binder-backed voice read. */
        override fun getVoice(): Voice {
            voiceReads++
            return activeVoice
        }

        /** Accepts the offered voice. */
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
            spoken++
            return TextToSpeech.SUCCESS
        }

        /** Stops without a framework connection. */
        override fun stop(): Int = TextToSpeech.SUCCESS
    }

    /** Models a usable engine that relies on its default voice and exposes no catalog. */
    private class EmptyCatalogTextToSpeech : TextToSpeech(RuntimeEnvironment.getApplication(), {}) {
        override fun setLanguage(locale: Locale?): Int = TextToSpeech.LANG_COUNTRY_AVAILABLE

        override fun getVoices(): MutableSet<Voice> = mutableSetOf()
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
